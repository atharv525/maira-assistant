package com.maira.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandParserTest {
    private fun p(s: String) = CommandParser.parse(s)

    @Test fun openApps() {
        assertEquals(Command.OpenApp("youtube"), p("YouTube kholo"))
        assertEquals(Command.OpenApp("यूट्यूब"), p("यूट्यूब खोलो"))
        assertEquals(Command.OpenApp("whatsapp"), p("WhatsApp open karo"))
        assertEquals(Command.OpenApp("इंस्टाग्राम"), p("मायरा इंस्टाग्राम खोल दो"))
        assertEquals(Command.OpenApp("calculator"), p("calculator chalao"))
    }

    @Test fun calls() {
        assertEquals(Command.Call("mummy"), p("mummy ko call karo"))
        assertEquals(Command.Call("मम्मी"), p("मम्मी को कॉल करो"))
        assertEquals(Command.Call("papa"), p("papa ko phone lagao"))
        assertEquals(Command.Call("राहुल"), p("राहुल को फोन लगाओ"))
        assertEquals(Command.Call("rahul"), p("call karo rahul ko"))
        assertEquals(Command.Call("9876543210"), p("9876543210 par call karo"))
    }

    @Test fun sms() {
        assertEquals(Command.Sms("papa", "main late aaunga"), p("papa ko message bhejo ki main late aaunga"))
        assertEquals(Command.Sms("दीदी", "मैं घर पहुंच गई"), p("दीदी को मैसेज भेजो कि मैं घर पहुंच गई"))
        assertEquals(Command.Sms("mummy", ""), p("mummy ko message bhejo"))
    }

    @Test fun torchVolume() {
        assertEquals(Command.Flash(true), p("torch jalao"))
        assertEquals(Command.Flash(true), p("टॉर्च ऑन करो"))
        assertEquals(Command.Flash(false), p("टॉर्च बंद करो"))
        assertEquals(Command.Flash(false), p("flashlight off"))
        assertEquals(Command.Volume("up"), p("volume badhao"))
        assertEquals(Command.Volume("down"), p("आवाज़ कम करो"))
        assertEquals(Command.Volume("mute"), p("phone mute karo volume"))
        assertEquals(Command.Volume("max"), p("volume full karo"))
        assertEquals(Command.Volume("mute"), p("phone mute karo"))
        assertEquals(Command.Volume("mute"), p("फोन साइलेंट करो"))
    }

    @Test fun alarmTimer() {
        assertEquals(Command.Alarm(6, 0), p("subah 6 baje ka alarm lagao"))
        assertEquals(Command.Alarm(6, 30), p("सुबह साढ़े 6 बजे का अलार्म लगाओ"))
        assertEquals(Command.Alarm(19, 15), p("shaam 7:15 ka alarm"))
        assertEquals(Command.Timer(300), p("5 minute ka timer lagao"))
        assertEquals(Command.Timer(600), p("दस मिनट का टाइमर लगा दो"))
        assertEquals(Command.Timer(120), p("do minute ka timer"))
    }

    @Test fun misc() {
        assertEquals(Command.SettingsPanel("wifi"), p("wifi on karo"))
        assertEquals(Command.SettingsPanel("bluetooth"), p("ब्लूटूथ चालू करो"))
        assertEquals(Command.Battery, p("battery kitni hai"))
        assertEquals(Command.Time, p("kitne baje hain"))
        assertEquals(Command.Time, p("टाइम क्या हुआ है"))
        assertEquals(Command.Date, p("aaj ki date kya hai"))
        assertEquals(Command.Camera(false, false), p("photo khincho"))
        assertEquals(Command.Camera(true, false), p("selfie lo"))
        assertEquals(Command.Media("play"), p("gaana chalao"))
        assertEquals(Command.Media("pause"), p("गाना रोको"))
        assertEquals(Command.Media("next"), p("agla gaana"))
        assertEquals(Command.YouTubeSearch("arijit singh ke gaane"), p("youtube pe arijit singh ke gaane chalao"))
        assertEquals(Command.Global("home"), p("home screen par jao"))
        assertEquals(Command.Global("back"), p("पीछे जाओ"))
        assertEquals(Command.Global("notifications"), p("notification kholo"))
        assertEquals(Command.Global("lock"), p("phone lock karo"))
        assertEquals(Command.Global("screenshot"), p("screenshot lo"))
        assertEquals(Command.Global("recents"), p("recent apps dikhao"))
        assertEquals(Command.Scroll(true), p("neeche scroll karo"))
        assertEquals(Command.Scroll(false), p("upar scroll karo"))
        assertEquals(Command.Type("hello kaise ho"), p("type karo hello kaise ho"))
        assertEquals(Command.SettingsPanel("settings"), p("settings kholo"))
    }

    @Test fun questionsGoToLlm() {
        assertNull(p("भारत की राजधानी क्या है"))
        assertNull(p("mujhe ek kahani sunao"))
        assertNull(p("aloo paratha kaise banate hain"))
    }

    @Test fun yesNo() {
        assertTrue(CommandParser.isYes("हाँ भेज दो"))
        assertTrue(CommandParser.isNo("नहीं रहने दो"))
    }

    @Test fun translit() {
        assertTrue(Translit.similarity("मम्मी", "Mummy") > 0.62)
        assertTrue(Translit.similarity("यूट्यूब", "YouTube") > 0.6)
        assertTrue(Translit.similarity("व्हाट्सएप", "WhatsApp") > 0.6)
        assertTrue(Translit.similarity("राहुल", "Rahul Sharma") > 0.62)
        assertTrue(Translit.similarity("पापा", "Mummy") < 0.62)
    }
}
