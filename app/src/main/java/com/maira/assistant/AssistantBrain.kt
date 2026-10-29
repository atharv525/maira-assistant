package com.maira.assistant

import org.json.JSONObject

/** Routes user text: rule-based phone commands first, then the offline LLM. */
class AssistantBrain(private val app: MairaApp) {
    val actions = PhoneActions(app)
    private data class PendingSms(val contact: Contact, val text: String)
    private var pendingSms: PendingSms? = null
    private var pendingSmsWho: Contact? = null   // asked "kya message bhejna hai?"
    private val history = ArrayDeque<Pair<String, String>>()

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

        // 2) Rule based commands
        val cmd = CommandParser.parse(input)
        if (cmd != null) return execute(cmd)

        // 3) Offline LLM
        if (Prefs.modelPath(app) == null) {
            return ActionResult("यह मैं अभी नहीं समझ पाई। सवालों के जवाब के लिए पहले सेटअप में AI मॉडल डाउनलोड कीजिए।")
        }
        return try {
            val prompt = LlmEngine.buildPrompt(history.toList(), input)
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
        is Command.OpenApp -> ActionResult(actions.openApp(cmd.name))
        is Command.Sms -> {
            val (c, err) = actions.resolveSmsTarget(cmd.who)
            when {
                c == null -> ActionResult(err ?: "कॉन्टैक्ट नहीं मिला।")
                cmd.text.isBlank() -> { pendingSmsWho = c; ActionResult("${c.name} को क्या मैसेज भेजना है?", expectReply = true) }
                else -> { pendingSms = PendingSms(c, cmd.text); ActionResult("${c.name} को यह मैसेज भेजूँ: \"${cmd.text}\"? हाँ या ना बोलिए।", expectReply = true) }
            }
        }
    }

    /** LLM may answer with {"action": ...} when the rules did not match. */
    private fun tryJsonAction(out: String): Command? {
        val s = out.indexOf('{'); val e = out.lastIndexOf('}')
        if (s < 0 || e <= s) return null
        return try {
            val j = JSONObject(out.substring(s, e + 1))
            when (j.optString("action").lowercase()) {
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
}
