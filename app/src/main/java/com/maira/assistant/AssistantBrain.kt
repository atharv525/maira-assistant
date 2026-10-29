package com.maira.assistant

import kotlinx.coroutines.launch
import org.json.JSONObject

/** Routes user text: rule-based phone commands first, then the offline LLM. */
class AssistantBrain(private val app: MairaApp) {
    val actions = PhoneActions(app)
    private data class PendingSms(val contact: Contact, val text: String)
    private var pendingSms: PendingSms? = null
    private var pendingSmsWho: Contact? = null   // asked "kya message bhejna hai?"
    private val history = ArrayDeque<Pair<String, String>>()
    private var pendingApps: List<AppMatcher.App>? = null
    /** Last screen / notification text Maira read, used as context for follow-up questions ("isme price kitna hai"). */
    @Volatile private var lastScreen: ScreenCapture? = null
    private var turnsSinceScreen = 0

    private fun freshScreen(): ScreenCapture? {
        val s = lastScreen ?: return null
        if (System.currentTimeMillis() - s.time > SCREEN_CONTEXT_MS || turnsSinceScreen > SCREEN_CONTEXT_TURNS) { lastScreen = null; return null }
        return s
    }

    suspend fun handle(text: String, onPartial: ((String) -> Unit)? = null): ActionResult {
        val input = text.trim()
        if (input.isEmpty()) return ActionResult("जी, बोलिए।", expectReply = true)

        // 1) Pending SMS confirmation
        pendingSms?.let { p ->
            pendingSms = null
            if (CommandParser.isYes(input)) return ActionResult(actions.sendSms(p.contact, p.text))
            if (CommandParser.isNo(input)) return ActionResult("ठीक है, मैसेज नहीं भेजा।")
        }
        pendingSmsWho?.let { c ->
            pendingSmsWho = null
            if (CommandParser.isNo(input)) return ActionResult("ठीक है, मैसेज नहीं भेजा।")
            pendingSms = PendingSms(c, input)
            return ActionResult("${c.name} को यह मैसेज भेजूँ: \"$input\"? हाँ या ना बोलिए।", expectReply = true)
        }

        // 1b) Pending "which app?" question
        pendingApps?.let { opts ->
            pendingApps = null
            if (CommandParser.isNo(input)) return ActionResult("ठीक है, कोई ऐप नहीं खोला।")
            AppMatcher.pickChoice(input, opts)?.let { return ActionResult(actions.launch(it)) }
            // not an answer: fall through and treat it as a new command
        }

        // 2) Rule based commands
        val cmd = CommandParser.parse(input)
        if (cmd != null) {
            if (cmd !is Command.ReadScreen && cmd !is Command.ReadNotifications) turnsSinceScreen++
            return execute(cmd)
        }

        // 3) Offline LLM
        if (Prefs.modelPath(app) == null) {
            return ActionResult("यह मैं अभी नहीं समझ पाई। सवालों के जवाब के लिए पहले सेटअप में AI मॉडल डाउनलोड कीजिए।")
        }
        return try {
            val screen = freshScreen()
            turnsSinceScreen++
            // keep the prompt inside the model's small context: with screen text, keep only the last turn
            val hist = if (screen != null) history.toList().takeLast(1) else history.toList()
            val prompt = LlmEngine.buildPrompt(hist, input, screen)
            val raw = app.llm.generate(prompt) { partial -> onPartial?.invoke(LlmEngine.cleanOutput(partial)) }
            val out = LlmEngine.cleanOutput(raw)
            val asAction = tryJsonAction(out)
            if (asAction != null) return execute(asAction)
            if (out.trimStart().startsWith("{")) return ActionResult("माफ़ कीजिए, यह काम मैं समझ नहीं पाई। दोबारा बोलिए।")
            val reply = out.ifBlank { "माफ़ कीजिए, मैं जवाब नहीं दे पाई।" }
            history.addLast(input to reply)
            while (history.size > 3) history.removeFirst()
            ActionResult(reply)
        } catch (t: Throwable) {
            ActionResult("माफ़ कीजिए, AI मॉडल नहीं चल पाया। ${t.message?.take(80) ?: ""}")
        }
    }

    fun clearHistory() = history.clear()

    fun execute(cmd: Command): ActionResult = when (cmd) {
        is Command.StopTalking -> { app.tts.stop(); ActionResult("") }
        is Command.Help -> ActionResult(actions.help())
        is Command.Flash -> ActionResult(actions.flash(cmd.on))
        is Command.Volume -> ActionResult(actions.volume(cmd.dir))
        is Command.Media -> ActionResult(actions.media(cmd.key))
        is Command.YouTubeSearch -> ActionResult(actions.youtube(cmd.query))
        is Command.WebSearch -> ActionResult(actions.webSearch(cmd.query))
        is Command.Alarm -> ActionResult(actions.alarm(cmd.hour, cmd.minute))
        is Command.Timer -> ActionResult(actions.timer(cmd.seconds))
        is Command.SettingsPanel -> ActionResult(actions.settings(cmd.panel))
        is Command.Battery -> ActionResult(actions.battery())
        is Command.Time -> ActionResult(actions.time())
        is Command.Date -> ActionResult(actions.date())
        is Command.Camera -> ActionResult(actions.camera(cmd.selfie, cmd.video))
        is Command.Global -> actions.global(cmd.action)
        is Command.Type -> actions.type(cmd.text)
        is Command.Scroll -> actions.scroll(cmd.down)
        is Command.Click -> actions.click(cmd.text)
        is Command.Call -> ActionResult(actions.call(cmd.who))
        is Command.OpenApp -> openApp(cmd.name)
        is Command.ReadScreen -> readScreen(cmd.summarize)
        is Command.ScreenBolo -> screenBolo(cmd.on)
        is Command.CheckUpdate -> {
            app.appScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val found = try { Updater.checkBlocking(app, force = true) } catch (_: Exception) { false }
                if (!found) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { app.tts.speak("मायरा का नया अपडेट अभी नहीं है। आप सबसे नए वर्शन पर हैं।") }
            }
            ActionResult("अपडेट देख रही हूँ…")
        }
        is Command.AutoSetup -> ActionResult("ऑटो सेटअप खोल रही हूँ।", deferred = { AutoSetupActivity.start(app, restart = true) })
        is Command.ReadNotifications -> {
            val (say, cap) = actions.notifications()
            if (cap != null) { lastScreen = cap; turnsSinceScreen = 0 }
            ActionResult(say)
        }
        is Command.Sms -> {
            val (c, err) = actions.resolveSmsTarget(cmd.who)
            when {
                c == null -> ActionResult(err ?: "कॉन्टैक्ट नहीं मिला।")
                cmd.text.isBlank() -> { pendingSmsWho = c; ActionResult("${c.name} को क्या मैसेज भेजना है?", expectReply = true) }
                else -> { pendingSms = PendingSms(c, cmd.text); ActionResult("${c.name} को यह मैसेज भेजूँ: \"${cmd.text}\"? हाँ या ना बोलिए।", expectReply = true) }
            }
        }
    }

    private fun screenBolo(on: Boolean): ActionResult {
        if (!on) { Prefs.setScreenBolo(app, false); return ActionResult("स्क्रीन बोलो मोड बंद कर दिया।") }
        if (!actions.accessibilityOn()) return ActionResult("स्क्रीन बोलो मोड के लिए " + actions.needA11yText.removePrefix("इसके लिए "))
        Prefs.setScreenBolo(app, true)
        return ActionResult("स्क्रीन बोलो मोड चालू। अब जो भी ऐप या स्क्रीन खुलेगी, मैं उसका नाम और ऊपर लिखी बातें बोलूँगी। बैंक, UPI और पासवर्ड वाली स्क्रीन नहीं पढ़ूँगी। बंद करने के लिए बोलिए: स्क्रीन बोलो मोड बंद करो।")
    }

    private fun openApp(name: String): ActionResult = when (val r = actions.findApp(name)) {
        is AppMatcher.Result.Found -> ActionResult(actions.launch(r.app))
        is AppMatcher.Result.None -> ActionResult("'$name' नाम का ऐप नहीं मिला। ऐप का नाम वैसे बोलिए जैसा फ़ोन में लिखा है।")
        is AppMatcher.Result.Ambiguous -> {
            pendingApps = r.options
            val ord = listOf("पहला", "दूसरा", "तीसरा")
            val list = r.options.withIndex().joinToString(", ") { (i, a) -> "${ord.getOrElse(i) { "${i + 1}" }} ${a.label}" }
            ActionResult("कौन सा खोलूँ? $list। नाम या पहला, दूसरा बोलिए।", expectReply = true)
        }
    }

    private fun readScreen(summarize: Boolean): ActionResult {
        if (!actions.accessibilityOn()) return ActionResult("स्क्रीन पढ़ने के लिए " + actions.needA11yText.removePrefix("इसके लिए "))
        return ActionResult(if (summarize) "स्क्रीन देखकर समझ रही हूँ…" else "स्क्रीन पढ़ रही हूँ…", deferredSay = {
            val cap = actions.captureScreen()
            when {
                cap == null -> "स्क्रीन नहीं पढ़ पाई। फ़ोन कंट्रोल (Accessibility) दोबारा चालू करके देखिए।"
                cap.text.isBlank() -> "${cap.appName} की स्क्रीन पर पढ़ने लायक कोई लिखावट नहीं मिली। तस्वीर के अंदर लिखा टेक्स्ट मैं नहीं पढ़ सकती, और कुछ ऐप पढ़ने नहीं देते।"
                else -> {
                    lastScreen = cap; turnsSinceScreen = 0
                    if (!summarize) "${cap.appName} की स्क्रीन पर लिखा है: " + ScreenText.forSpeech(cap.text)
                    else summarizeScreen(cap)
                }
            }
        })
    }

    private suspend fun summarizeScreen(cap: ScreenCapture): String {
        if (Prefs.modelPath(app) == null)
            return "सार बताने के लिए AI मॉडल चाहिए, इसलिए सीधे पढ़ रही हूँ। " + ScreenText.forSpeech(cap.text)
        return try {
            if (!app.llm.isLoaded) app.tts.speak("एक पल, AI दिमाग लोड हो रहा है।")
            val out = LlmEngine.cleanOutput(app.llm.generate(LlmEngine.buildSummaryPrompt(cap)))
            if (out.isBlank() || out.trimStart().startsWith("{")) "माफ़ कीजिए, सार नहीं बना पाई। " + ScreenText.forSpeech(ScreenText.cap(cap.text, 500))
            else out
        } catch (t: Throwable) {
            "माफ़ कीजिए, AI मॉडल नहीं चल पाया, इसलिए सीधे पढ़ रही हूँ। " + ScreenText.forSpeech(cap.text)
        }
    }

    /** LLM may answer with {"action": ...} when the rules did not match. */
    private fun tryJsonAction(out: String): Command? {
        val s = out.indexOf('{'); val e = out.lastIndexOf('}')
        if (s < 0 || e <= s) return null
        return try {
            val j = JSONObject(out.substring(s, e + 1))
            when (j.optString("action").lowercase()) {
                "read_screen" -> Command.ReadScreen(false)
                "summarize_screen" -> Command.ReadScreen(true)
                "read_notifications" -> Command.ReadNotifications
                "open_app" -> j.optString("name").takeIf { it.isNotBlank() }?.let { Command.OpenApp(it) }
                "call" -> j.optString("name").takeIf { it.isNotBlank() }?.let { Command.Call(it) }
                "sms" -> j.optString("name").takeIf { it.isNotBlank() }?.let { Command.Sms(it, j.optString("text")) }
                "flashlight" -> Command.Flash(j.optString("state", "on") != "off")
                "volume" -> Command.Volume(j.optString("dir", "up"))
                "alarm" -> if (j.has("hour")) Command.Alarm(j.optInt("hour"), j.optInt("minute", 0)) else null
                "timer" -> j.optInt("seconds", 0).takeIf { it > 0 }?.let { Command.Timer(it) }
                "settings" -> Command.SettingsPanel(j.optString("panel", "settings"))
                "home", "back", "notifications", "lock", "screenshot", "recents" -> Command.Global(j.optString("action").lowercase())
                else -> null
            }
        } catch (_: Exception) { null }
    }

    companion object {
        const val SCREEN_CONTEXT_MS = 5 * 60 * 1000L
        const val SCREEN_CONTEXT_TURNS = 4
    }
}
