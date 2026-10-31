package com.maira.assistant

import android.util.Log
import com.maira.assistant.brain.MairaBrainLoader
import com.maira.assistant.brain.MairaCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Routes user text. Maira runs ONLY on the user's own model + rules (no Qwen / third-party model):
 *   0. pending follow-ups (SMS yes/no, "which app?")
 *   1. app-only rule commands the model was never trained on (chup, help, screen bolo mode, update, setup, notification shade)
 *   2. Maira Brain (own ONNX model) -> JSON action -> existing action code / [BrainActions]
 *   3. old rule-based Hinglish parser (when the model output is invalid, unknown, has an empty slot, or is unsure)
 *   4. not understood / "chat" -> short Hindi "main seekh rahi hoon" line
 */
class AssistantBrain(private val app: MairaApp) {
    val actions = PhoneActions(app)
    private val brainActions = BrainActions(app, actions)
    private data class PendingSms(val contact: Contact, val text: String)
    private var pendingSms: PendingSms? = null
    private var pendingSmsWho: Contact? = null   // asked "kya message bhejna hai?"
    private var pendingApps: List<AppMatcher.App>? = null

    /** Last decision, for logs / debugging ("brain", "rules", "learning"). */
    @Volatile var lastRoute: String = ""
        private set

    @Suppress("UNUSED_PARAMETER")
    suspend fun handle(text: String, onPartial: ((String) -> Unit)? = null): ActionResult {
        val input = text.trim()
        if (input.isEmpty()) return ActionResult("जी, बोलिए।", expectReply = true)

        // 0) Pending SMS confirmation
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
        // 0b) Pending "which app?" question
        pendingApps?.let { opts ->
            pendingApps = null
            if (CommandParser.isNo(input)) return ActionResult("ठीक है, कोई ऐप नहीं खोला।")
            AppMatcher.pickChoice(input, opts)?.let { return ActionResult(actions.launch(it)) }
            // not an answer: fall through and treat it as a new command
        }

        val rule = CommandParser.parse(input)

        // 1) App-only commands (the model doesn't know these features)
        if (BrainRouter.isMetaCommand(rule)) { lastRoute = "rules"; return execute(rule!!) }

        // 2) Maira Brain (own model)
        if (Prefs.brainEnabled(app)) {
            val cmd: MairaCommand? = try {
                withContext(Dispatchers.Default) { MairaBrainLoader.get(app)?.parse(input) }
            } catch (t: Throwable) { Log.e(TAG, "brain failed", t); null }
            if (cmd != null) {
                Log.i(TAG, "brain: ${cmd.json} conf=${cmd.confidence} ${cmd.millis}ms")
                val plan = BrainRouter.plan(cmd, input)
                val unsure = cmd.confidence < BrainRouter.LOW_CONFIDENCE && rule != null
                if (!unsure) {
                    val res: ActionResult? = when (plan) {
                        is BrainPlan.Exec -> runBrainExec(plan.cmd)
                        is BrainPlan.Brain -> { lastRoute = "brain"; brainActions.run(plan.cmd, input) }
                        is BrainPlan.Chat -> when {
                            rule == null -> learning()
                            // rule parser's catch-all "<x> karo" -> OpenApp(x): if no such app, it was just talk
                            rule is Command.OpenApp && actions.findApp(rule.name) is AppMatcher.Result.None -> learning()
                            else -> null
                        }
                        is BrainPlan.Fallback -> { Log.i(TAG, "fallback: ${plan.why}"); null }
                    }
                    if (res != null) return res
                }
            }
        }

        // 3) Rule based commands
        if (rule != null) { lastRoute = "rules"; return execute(rule) }

        // 4) Not understood (no third-party model is used any more)
        return learning()
    }

    private fun learning(): ActionResult {
        lastRoute = "learning"
        return ActionResult(LEARNING_LINE)
    }

    /** Brain -> existing command. An app name the phone doesn't have goes back to the rule parser (it uses the spoken name). */
    private fun runBrainExec(cmd: Command): ActionResult? {
        lastRoute = "brain"
        if (cmd is Command.OpenApp && actions.findApp(cmd.name) is AppMatcher.Result.None) return null
        return execute(cmd)
    }

    fun clearHistory() {}

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
            app.appScope.launch(Dispatchers.IO) {
                val found = try { Updater.checkBlocking(app, force = true) } catch (_: Exception) { false }
                if (!found) withContext(Dispatchers.Main) { app.tts.speak("मायरा का नया अपडेट अभी नहीं है। आप सबसे नए वर्शन पर हैं।") }
            }
            ActionResult("अपडेट देख रही हूँ…")
        }
        is Command.AutoSetup -> ActionResult("ऑटो सेटअप खोल रही हूँ।", deferred = { AutoSetupActivity.start(app, restart = true) })
        is Command.ReadNotifications -> ActionResult(actions.notifications().first)
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

    /** Plain read-aloud of the screen. ("Summary" requests are read aloud too: no third-party AI model any more.) */
    private fun readScreen(summarize: Boolean): ActionResult {
        if (!actions.accessibilityOn()) return ActionResult("स्क्रीन पढ़ने के लिए " + actions.needA11yText.removePrefix("इसके लिए "))
        return ActionResult("स्क्रीन पढ़ रही हूँ…", deferredSay = {
            val cap = actions.captureScreen()
            when {
                cap == null -> "स्क्रीन नहीं पढ़ पाई। फ़ोन कंट्रोल (Accessibility) दोबारा चालू करके देखिए।"
                cap.text.isBlank() -> "${cap.appName} की स्क्रीन पर पढ़ने लायक कोई लिखावट नहीं मिली। तस्वीर के अंदर लिखा टेक्स्ट मैं नहीं पढ़ सकती, और कुछ ऐप पढ़ने नहीं देते।"
                summarize -> "सार बताना मैं अभी सीख रही हूँ, इसलिए पढ़कर सुना रही हूँ। ${cap.appName} की स्क्रीन पर लिखा है: " + ScreenText.forSpeech(cap.text)
                else -> "${cap.appName} की स्क्रीन पर लिखा है: " + ScreenText.forSpeech(cap.text)
            }
        })
    }

    companion object {
        private const val TAG = "Maira"
        const val LEARNING_LINE = "यह बात अभी मैं नहीं समझती, मैं सीख रही हूँ।"
    }
}
