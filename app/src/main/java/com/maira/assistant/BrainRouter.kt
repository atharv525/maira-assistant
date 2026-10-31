package com.maira.assistant

import com.maira.assistant.brain.MairaCommand
import com.maira.assistant.brain.MairaUtil

/** What to do with a Maira Brain result. Pure (unit-tested): no Android calls in here. */
sealed class BrainPlan {
    /** Output unusable (invalid JSON, unknown action, empty/invalid slot) -> use the old rule parser. */
    data class Fallback(val why: String) : BrainPlan()
    /** {"action":"chat"} -> the Qwen chat model. */
    object Chat : BrainPlan()
    /** Run through the app's existing implementation (same code path as the rule parser). */
    data class Exec(val cmd: Command) : BrainPlan()
    /** New in v1.4: implemented in [BrainActions]. */
    data class Brain(val cmd: MairaCommand) : BrainPlan()
}

object BrainRouter {
    /** Below this min-token probability, a matching rule-parser command wins (model is unsure). */
    const val LOW_CONFIDENCE = 0.45f

    private val ON_OFF = setOf("on", "off")
    private val TOGGLES = setOf("wifi", "bluetooth", "mobile_data", "hotspot", "dnd", "airplane_mode", "location", "auto_rotate", "power_saver")
    private val MSG_APPS = setOf("sms", "whatsapp", "telegram", "instagram")
    private val READ_APPS = setOf("sms", "whatsapp", "telegram", "instagram")
    private val SETTINGS_PAGES = setOf("main", "about", "network", "location", "storage", "wifi", "accessibility", "security", "bluetooth",
        "language", "date_time", "battery", "apps", "notifications", "display", "sound")
    private val SELFIE = Regex("(?i)selfie|सेल्फी|सेल्फ़ी|front camera|फ्रंट")

    /** Model app ids -> names the existing AppMatcher understands. */
    fun appName(id: String): String = when (id) {
        "playstore" -> "play store"
        "vivo_store" -> "v-appstore"
        "gpay" -> "google pay"
        "jiocinema" -> "jiocinema"
        else -> id.replace('_', ' ')
    }

    fun plan(c: MairaCommand, raw: String): BrainPlan {
        if (!c.valid || c.action.isBlank()) return BrainPlan.Fallback("invalid json")
        if (c.json.contains("<unk>")) return BrainPlan.Fallback("unk token")
        fun need(vararg keys: String): BrainPlan? = keys.firstOrNull { c.str(it).isEmpty() }?.let { BrainPlan.Fallback("empty $it") }
        val brain = BrainPlan.Brain(c)
        return when (val a = c.action) {
            "chat" -> BrainPlan.Chat
            "open_app" -> need("app") ?: BrainPlan.Exec(Command.OpenApp(appName(c.str("app"))))
            "call_contact" -> need("name") ?: BrainPlan.Exec(Command.Call(c.str("name")))
            "video_call" -> need("name") ?: brain
            "send_message" -> need("contact") ?: run {
                val app = c.str("app").ifEmpty { "sms" }
                if (app !in MSG_APPS) return BrainPlan.Fallback("bad app $app")
                val text = MairaUtil.recoverCase(raw, c.str("text"))
                if (app == "sms") BrainPlan.Exec(Command.Sms(c.str("contact"), text)) else brain
            }
            "set_alarm" -> MairaUtil.clock(c.str("time"))?.let { BrainPlan.Exec(Command.Alarm(it.first, it.second)) } ?: BrainPlan.Fallback("bad time")
            "set_timer" -> MairaUtil.durationSeconds(c.str("duration")).takeIf { it > 0 }?.let { BrainPlan.Exec(Command.Timer(it)) } ?: BrainPlan.Fallback("bad duration")
            "set_reminder" -> need("text") ?: when {
                c.str("time").isNotEmpty() -> if (MairaUtil.clock(c.str("time")) != null) brain else BrainPlan.Fallback("bad time")
                MairaUtil.durationSeconds(c.str("duration")) > 0 -> brain
                else -> BrainPlan.Fallback("no time/duration")
            }
            "make_note" -> need("text") ?: brain
            "flashlight" -> if (c.str("state") in ON_OFF) BrainPlan.Exec(Command.Flash(c.str("state") == "on")) else BrainPlan.Fallback("bad state")
            in TOGGLES -> if (c.str("state") in ON_OFF) brain else BrainPlan.Fallback("bad state")
            "sound_mode" -> if (c.str("mode") in setOf("silent", "vibrate", "normal")) brain else BrainPlan.Fallback("bad mode")
            "volume" -> {
                val lv = c.int("level")
                when {
                    lv != null -> if (lv in 0..100) brain else BrainPlan.Fallback("bad level")
                    c.str("dir") in setOf("up", "down") -> BrainPlan.Exec(Command.Volume(c.str("dir")))
                    else -> BrainPlan.Fallback("no level/dir")
                }
            }
            "brightness" -> {
                val lv = c.int("level")
                if ((lv != null && lv in 0..100) || (lv == null && c.str("dir") in setOf("up", "down"))) brain else BrainPlan.Fallback("no level/dir")
            }
            "take_screenshot" -> BrainPlan.Exec(Command.Global("screenshot"))
            "go_home" -> BrainPlan.Exec(Command.Global("home"))
            "go_back" -> BrainPlan.Exec(Command.Global("back"))
            "recents" -> BrainPlan.Exec(Command.Global("recents"))
            "lock_screen" -> BrainPlan.Exec(Command.Global("lock"))
            "take_photo" -> BrainPlan.Exec(Command.Camera(selfie = SELFIE.containsMatchIn(raw), video = false))
            "record_video" -> BrainPlan.Exec(Command.Camera(selfie = SELFIE.containsMatchIn(raw), video = true))
            "media_play" -> BrainPlan.Exec(Command.Media("play"))
            "media_pause" -> BrainPlan.Exec(Command.Media("pause"))
            "media_next" -> BrainPlan.Exec(Command.Media("next"))
            "media_previous" -> BrainPlan.Exec(Command.Media("prev"))
            "play_song" -> need("query") ?: when (c.str("app")) {
                "", "youtube", "default" -> BrainPlan.Exec(Command.YouTubeSearch(MairaUtil.recoverCase(raw, c.str("query"))))
                "spotify" -> brain
                else -> BrainPlan.Fallback("bad app")
            }
            "search_web" -> need("query") ?: BrainPlan.Exec(Command.WebSearch(MairaUtil.recoverCase(raw, c.str("query"))))
            "navigate" -> need("place") ?: brain
            "open_settings" -> if (c.str("page").ifEmpty { "main" } in SETTINGS_PAGES) brain else BrainPlan.Fallback("bad page")
            "open_gallery" -> brain
            "close_app" -> need("app") ?: brain
            "read_screen" -> BrainPlan.Exec(Command.ReadScreen(false))
            "read_notifications" -> BrainPlan.Exec(Command.ReadNotifications)
            "read_messages" -> if (c.str("app").isEmpty() || c.str("app") in READ_APPS) brain else BrainPlan.Fallback("bad app")
            "call_log", "missed_calls", "answer_call", "reject_call" -> brain
            "scroll" -> if (c.str("dir") in setOf("up", "down")) BrainPlan.Exec(Command.Scroll(c.str("dir") == "down")) else BrainPlan.Fallback("bad dir")
            "tap_text" -> need("text") ?: BrainPlan.Exec(Command.Click(MairaUtil.recoverCase(raw, c.str("text"))))
            "type_text" -> need("text") ?: BrainPlan.Exec(Command.Type(MairaUtil.recoverCase(raw, c.str("text"))))
            "calculate" -> need("expression") ?: if (MairaUtil.evalExpression(c.str("expression")) != null) brain else BrainPlan.Fallback("bad expression")
            "battery_status" -> BrainPlan.Exec(Command.Battery)
            "get_time" -> BrainPlan.Exec(Command.Time)
            "get_date" -> BrainPlan.Exec(Command.Date)
            else -> BrainPlan.Fallback("unknown action $a")
        }
    }

    /** Rule-parser commands for app features the model was never trained on: these always win. */
    fun isMetaCommand(c: Command?): Boolean = when (c) {
        is Command.StopTalking, is Command.Help, is Command.ScreenBolo, is Command.CheckUpdate, is Command.AutoSetup -> true
        is Command.ReadScreen -> c.summarize
        is Command.Global -> c.action == "notifications" || c.action == "quick"
        else -> false
    }
}
