package com.maira.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenFeatureTest {
    private fun p(s: String) = CommandParser.parse(s)
    private val READ = Command.ReadScreen(false)
    private val SUM = Command.ReadScreen(true)

    @Test fun readScreenCommands() {
        assertEquals(READ, p("screen padho"))
        assertEquals(READ, p("Maira screen padho"))
        assertEquals(READ, p("screen par kya hai"))
        assertEquals(READ, p("screen pe kya likha hai"))
        assertEquals(READ, p("ye kya likha hai"))
        assertEquals(READ, p("yeh kya likha hai"))
        assertEquals(READ, p("स्क्रीन पढ़ो"))
        assertEquals(READ, p("स्क्रीन पर क्या है"))
        assertEquals(READ, p("ये क्या लिखा है"))
        assertEquals(READ, p("padh ke sunao"))
        assertEquals(READ, p("पढ़ के सुनाओ"))
        assertEquals(READ, p("isko padho"))
        assertEquals(READ, p("इसे पढ़ो"))
    }

    @Test fun summarizeCommands() {
        assertEquals(SUM, p("screen ka summary batao"))
        assertEquals(SUM, p("isme kya likha hai samjhao"))
        assertEquals(SUM, p("screen samjhao"))
        assertEquals(SUM, p("स्क्रीन समझाओ"))
        assertEquals(SUM, p("स्क्रीन का सारांश बताओ"))
        assertEquals(SUM, p("इसमें क्या लिखा है समझाओ"))
        assertEquals(SUM, p("iska matlab samjhao"))
    }

    @Test fun notificationCommands() {
        assertEquals(Command.ReadNotifications, p("notification padho"))
        assertEquals(Command.ReadNotifications, p("notifications sunao"))
        assertEquals(Command.ReadNotifications, p("नोटिफिकेशन पढ़ो"))
        assertEquals(Command.ReadNotifications, p("notification mein kya aaya"))
        assertEquals(Command.Global("notifications"), p("notification kholo"))
    }

    @Test fun noFalseScreenMatches() {
        assertEquals(Command.Global("screenshot"), p("screenshot lo"))
        assertEquals(Command.Global("lock"), p("screen lock karo"))
        assertEquals(Command.Global("home"), p("home screen par jao"))
        assertEquals(Command.SettingsPanel("display"), p("screen brightness kam karo"))
        assertNull(p("isme price kitna hai"))       // follow-up → LLM with screen context
        assertNull(p("isme kiska message hai"))
        assertNull(p("भारत की राजधानी क्या है"))
    }

    @Test fun composeDedupesSortsAndCaps() {
        val items = listOf(
            ScreenItem("Second line", 200, 0),
            ScreenItem("Title", 10, 0),
            ScreenItem("Title", 10, 50),               // duplicate
            ScreenItem("  ", 30, 0),                    // blank
            ScreenItem("Price ₹499", 100, 0),
            ScreenItem("price ₹499", 101, 300),         // case/space duplicate
            ScreenItem("Dialog OK", 500, 0, group = 0),
            ScreenItem("Behind dialog", 5, 0, group = 1)
        )
        val out = ScreenText.compose(items)
        assertEquals(listOf("Title", "Price ₹499", "Second line", "Dialog OK", "Behind dialog"), out.split("\n"))
        val long = (1..400).map { ScreenItem("line number $it", it * 20, 0) }
        val capped = ScreenText.compose(long)
        assertTrue(capped.length <= ScreenText.MAX_CHARS + 2)
        assertTrue(capped.endsWith("…"))
    }

    @Test fun tokenFit() {
        val hindi = "नमस्ते ".repeat(500)
        val fitted = ScreenText.fitTokens(hindi, 300)
        assertTrue(ScreenText.estimateTokens(fitted) <= 302)
        assertEquals("short", ScreenText.fitTokens("short", 300))
    }

    @Test fun notificationSpeech() {
        assertEquals("अभी कोई नया नोटिफिकेशन नहीं है।", ScreenText.notifications(emptyList()))
        val s = ScreenText.notifications(listOf(
            NotifItem("WhatsApp", "Rahul", "Kal milte hain", 2),
            NotifItem("WhatsApp", "Rahul", "Kal milte hain", 2),
            NotifItem("Gmail", "Bank", "Statement ready", 1)
        ))
        assertTrue(s.startsWith("आपके 2 नोटिफिकेशन हैं।"))
        assertTrue(s.indexOf("Rahul") < s.indexOf("Bank"))
    }

    // ---------------- app matching
    private val apps = listOf(
        AppMatcher.App("WhatsApp", "com.whatsapp"),
        AppMatcher.App("WhatsApp Business", "com.whatsapp.w4b"),
        AppMatcher.App("Instagram", "com.instagram.android"),
        AppMatcher.App("PhonePe", "com.phonepe.app"),
        AppMatcher.App("Phone", "com.android.dialer"),
        AppMatcher.App("GPay", "com.google.android.apps.nbu.paisa.user"),
        AppMatcher.App("YouTube", "com.google.android.youtube"),
        AppMatcher.App("YouTube Music", "com.google.android.apps.youtube.music"),
        AppMatcher.App("Albums", "com.vivo.gallery"),
        AppMatcher.App("Calculator", "com.android.bbkcalculator"),
        AppMatcher.App("Google", "com.google.android.googlequicksearchbox"),
        AppMatcher.App("Google Maps", "com.google.android.apps.maps"),
        AppMatcher.App("Zomato", "com.application.zomato"),
        AppMatcher.App("Truecaller", "com.truecaller"),
        AppMatcher.App("Teams", "com.microsoft.teams"),
        AppMatcher.App("Team Viewer", "com.teamviewer")
    )
    private fun found(q: String): String? = (AppMatcher.resolve(q, apps) as? AppMatcher.Result.Found)?.app?.pkg

    @Test fun appMatching() {
        assertEquals("com.whatsapp", found("व्हाट्सएप"))
        assertEquals("com.whatsapp", found("watsap"))
        assertEquals("com.whatsapp", found("WhatsApp"))
        assertEquals("com.whatsapp.w4b", found("whatsapp business"))
        assertEquals("com.instagram.android", found("insta"))
        assertEquals("com.instagram.android", found("इंस्टाग्राम"))
        assertEquals("com.phonepe.app", found("PhonePe"))
        assertEquals("com.phonepe.app", found("फोनपे"))
        assertEquals("com.phonepe.app", found("phone pe"))
        assertEquals("com.android.dialer", found("phone"))
        assertEquals("com.google.android.apps.nbu.paisa.user", found("gpay"))
        assertEquals("com.google.android.apps.nbu.paisa.user", found("गूगल पे"))
        assertEquals("com.google.android.youtube", found("यूट्यूब"))
        assertEquals("com.vivo.gallery", found("gallery"))
        assertEquals("com.vivo.gallery", found("गैलरी"))
        assertEquals("com.android.bbkcalculator", found("कैलकुलेटर"))
        assertEquals("com.application.zomato", found("zomatto"))
        assertEquals("com.truecaller", found("ट्रूकॉलर"))
        assertEquals("com.google.android.googlequicksearchbox", found("google"))
        assertTrue(AppMatcher.resolve("snapchat", apps) is AppMatcher.Result.None)
        assertTrue(AppMatcher.resolve("random xyz", apps) is AppMatcher.Result.None)
    }

    @Test fun ambiguousAndChoice() {
        val r = AppMatcher.resolve("team", apps)
        assertTrue("expected ambiguous, got $r", r is AppMatcher.Result.Ambiguous)
        val opts = (r as AppMatcher.Result.Ambiguous).options
        assertTrue(opts.size >= 2)
        assertEquals(opts[1], AppMatcher.pickChoice("doosra", opts))
        assertEquals(opts[0], AppMatcher.pickChoice("पहला वाला", opts))
        val wa = listOf(apps[0], apps[1])
        assertEquals(apps[1], AppMatcher.pickChoice("business wala", wa))
        assertEquals(apps[0], AppMatcher.pickChoice("WhatsApp", wa))
    }

    @Test fun openAppParsing() {
        assertEquals(Command.OpenApp("व्हाट्सएप"), p("व्हाट्सएप खोलो"))
        assertEquals(Command.OpenApp("insta"), p("insta kholo"))
        assertEquals(Command.OpenApp("gpay"), p("gpay open karo"))
        assertEquals(Command.OpenApp("फोनपे"), p("फोनपे खोलो"))
    }
}
