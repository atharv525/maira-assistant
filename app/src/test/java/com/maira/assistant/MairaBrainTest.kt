package com.maira.assistant

import com.maira.assistant.brain.MairaCommand
import com.maira.assistant.brain.MairaTokenizer
import com.maira.assistant.brain.MairaUtil
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MairaBrainTest {
    private val tok by lazy { MairaTokenizer(File("src/main/assets/maira_tokenizer.json").readText()) }

    /** Golden ids/normalisation/detokenisation produced by maira-brain/tokenizer.py (Python) for 300 sentences. */
    @Test fun tokenizerMatchesPython() {
        val lines = javaClass.classLoader!!.getResourceAsStream("maira_tokenizer_golden.jsonl")!!.bufferedReader(Charsets.UTF_8).readLines().filter { it.isNotBlank() }
        assertTrue(lines.size >= 300)
        for (l in lines) {
            val o = JSONObject(l); val src = o.getString("src")
            assertEquals("normalize: $src", o.getString("norm"), tok.normalize(src))
            val ids = o.getJSONArray("ids").let { a -> List(a.length()) { a.getInt(it) } }
            assertEquals("encode: $src", ids, tok.encodeSrcFull(src))
            assertEquals(ids.take(62).map { it.toLong() }, tok.encodeSrc(src).toList())
            if (o.has("tgt")) {
                val t = o.getJSONArray("tgt_ids").let { a -> List(a.length()) { a.getInt(it) } }
                assertEquals(o.getString("tgt"), tok.decodeTgt(t))
            }
        }
    }

    private fun cmd(json: String, conf: Float = 0.99f): MairaCommand {
        val o = JSONObject(json); val args = LinkedHashMap<String, Any>()
        o.keys().forEach { if (it != "action") args[it] = o.get(it) }
        return MairaCommand(o.getString("action"), args, json, conf, 5, true)
    }
    private fun plan(json: String, raw: String = "") = BrainRouter.plan(cmd(json), raw)

    @Test fun routesToExistingActions() {
        assertEquals(BrainPlan.Exec(Command.Alarm(6, 30)), plan("""{"action":"set_alarm","time":"06:30"}"""))
        assertEquals(BrainPlan.Exec(Command.Timer(5400)), plan("""{"action":"set_timer","duration":"1h30m"}"""))
        assertEquals(BrainPlan.Exec(Command.Flash(true)), plan("""{"action":"flashlight","state":"on"}"""))
        assertEquals(BrainPlan.Exec(Command.Call("papa")), plan("""{"action":"call_contact","name":"papa"}"""))
        assertEquals(BrainPlan.Exec(Command.OpenApp("instagram")), plan("""{"action":"open_app","app":"instagram"}"""))
        assertEquals(BrainPlan.Exec(Command.OpenApp("play store")), plan("""{"action":"open_app","app":"playstore"}"""))
        assertEquals(BrainPlan.Exec(Command.Sms("rahul", "Main late aaunga")),
            plan("""{"action":"send_message","app":"sms","contact":"rahul","text":"main late aaunga"}""", "Rahul ko message karo ki Main late aaunga"))
        assertEquals(BrainPlan.Exec(Command.Volume("down")), plan("""{"action":"volume","dir":"down"}"""))
        assertEquals(BrainPlan.Exec(Command.Global("lock")), plan("""{"action":"lock_screen"}"""))
        assertEquals(BrainPlan.Exec(Command.Media("prev")), plan("""{"action":"media_previous"}"""))
        assertEquals(BrainPlan.Exec(Command.Camera(selfie = true, video = false)), plan("""{"action":"take_photo"}""", "ek selfie lo"))
        assertEquals(BrainPlan.Exec(Command.YouTubeSearch("arijit singh")), plan("""{"action":"play_song","app":"youtube","query":"arijit singh"}"""))
        assertEquals(BrainPlan.Exec(Command.Type("Hello Rahul")), plan("""{"action":"type_text","text":"hello rahul"}""", "type karo Hello Rahul"))
        assertEquals(BrainPlan.Exec(Command.Scroll(true)), plan("""{"action":"scroll","dir":"down"}"""))
        assertEquals(BrainPlan.Exec(Command.ReadScreen(false)), plan("""{"action":"read_screen"}"""))
        assertEquals(BrainPlan.Chat, plan("""{"action":"chat"}"""))
    }

    @Test fun newActionsGoToBrainActions() {
        for (j in listOf("""{"action":"wifi","state":"off"}""", """{"action":"airplane_mode","state":"on"}""", """{"action":"dnd","state":"on"}""",
                """{"action":"sound_mode","mode":"silent"}""", """{"action":"volume","level":40}""", """{"action":"brightness","dir":"up"}""",
                """{"action":"navigate","place":"aundh"}""", """{"action":"set_reminder","text":"chai","duration":"10m"}""",
                """{"action":"set_reminder","text":"dawai","time":"17:00"}""", """{"action":"calculate","expression":"25 * 4"}""",
                """{"action":"send_message","app":"whatsapp","contact":"rahul","text":"hi"}""", """{"action":"missed_calls"}""",
                """{"action":"open_settings","page":"wifi"}""", """{"action":"read_messages","app":"whatsapp"}""", """{"action":"video_call","name":"karan"}"""))
            assertTrue(j, plan(j) is BrainPlan.Brain)
    }

    @Test fun badOutputFallsBackToRules() {
        assertTrue(BrainRouter.plan(MairaCommand("", emptyMap(), "{\"action\":\"set_al", 0.9f, 1, false), "x") is BrainPlan.Fallback)
        assertTrue(plan("""{"action":"fly_to_moon"}""") is BrainPlan.Fallback)
        assertTrue(plan("""{"action":"call_contact","name":""}""") is BrainPlan.Fallback)
        assertTrue(plan("""{"action":"set_alarm","time":"25:99"}""") is BrainPlan.Fallback)
        assertTrue(plan("""{"action":"set_timer","duration":"x"}""") is BrainPlan.Fallback)
        assertTrue(plan("""{"action":"wifi","state":"maybe"}""") is BrainPlan.Fallback)
        assertTrue(plan("""{"action":"send_message","app":"whatsapp","contact":"chacha","text":"happy <unk> birthday"}""") is BrainPlan.Fallback)
        assertTrue(plan("""{"action":"calculate","expression":"25 guna"}""") is BrainPlan.Fallback)
    }

    @Test fun metaCommandsStayWithRules() {
        assertTrue(BrainRouter.isMetaCommand(CommandParser.parse("screen bolo mode chalu karo")))
        assertTrue(BrainRouter.isMetaCommand(CommandParser.parse("update check karo")))
        assertTrue(BrainRouter.isMetaCommand(Command.StopTalking))
        assertTrue(!BrainRouter.isMetaCommand(Command.Flash(true)))
    }

    @Test fun utils() {
        assertEquals(100.0, MairaUtil.evalExpression("25 * 4")!!, 1e-9)
        assertEquals(14.0, MairaUtil.evalExpression("2 + 3 * 4")!!, 1e-9)
        assertEquals(8.0, MairaUtil.evalExpression("2 ^ 3")!!, 1e-9)
        assertNull(MairaUtil.evalExpression("2 +"))
        assertEquals(450, MairaUtil.durationSeconds("7m30s"))
        assertEquals(6 to 30, MairaUtil.clock("06:30"))
        assertNull(MairaUtil.clock("24:00"))
        assertEquals("100", MairaUtil.formatNumber(100.0))
        assertEquals("3.33", MairaUtil.formatNumber(10.0 / 3))
        assertEquals("Hello Rahul", MairaUtil.recoverCase("type karo Hello Rahul", "hello rahul"))
        assertEquals("abc", MairaUtil.recoverCase("xyz", "abc"))
    }
}
