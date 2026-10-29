package com.maira.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationTest {
    private fun p(s: String) = CommandParser.parse(s)

    // ---------------- Screen bolo mode commands
    @Test fun screenBoloOn() {
        assertEquals(Command.ScreenBolo(true), p("screen bolo mode chalu karo"))
        assertEquals(Command.ScreenBolo(true), p("Maira screen bolo mode on karo"))
        assertEquals(Command.ScreenBolo(true), p("स्क्रीन बोलो मोड चालू करो"))
        assertEquals(Command.ScreenBolo(true), p("screen reader mode chalu karo"))
        assertEquals(Command.ScreenBolo(true), p("screen bolo mode"))
    }

    @Test fun screenBoloOff() {
        assertEquals(Command.ScreenBolo(false), p("screen bolo mode band karo"))
        assertEquals(Command.ScreenBolo(false), p("स्क्रीन बोलो मोड बंद करो"))
        assertEquals(Command.ScreenBolo(false), p("screen bolo mode off"))
        assertEquals(Command.ScreenBolo(false), p("screen bolna band karo"))
    }

    @Test fun screenBoloDoesNotBreakOtherScreenCommands() {
        assertEquals(Command.ReadScreen(false), p("screen padho"))
        assertEquals(Command.ReadScreen(true), p("screen samjhao"))
        assertEquals(Command.Global("screenshot"), p("screenshot lo"))
        assertEquals(Command.Global("lock"), p("screen lock karo"))
        assertEquals(Command.ReadScreen(false), p("screen par kya hai"))
    }

    @Test fun updateAndSetupCommands() {
        assertEquals(Command.CheckUpdate, p("update check karo"))
        assertEquals(Command.CheckUpdate, p("naya update aaya kya"))
        assertEquals(Command.CheckUpdate, p("अपडेट चेक करो"))
        assertEquals(Command.AutoSetup, p("auto setup karo"))
        assertEquals(Command.AutoSetup, p("सेटअप करो"))
        assertEquals(Command.OpenApp("youtube"), p("youtube kholo"))
    }

    // ---------------- Screen bolo helpers
    @Test fun sensitiveAppsSkipped() {
        assertTrue(ScreenBolo.isSensitive("com.phonepe.app", "PhonePe"))
        assertTrue(ScreenBolo.isSensitive("net.one97.paytm", "Paytm"))
        assertTrue(ScreenBolo.isSensitive("com.google.android.apps.nbu.paisa.user", "Google Pay"))
        assertTrue(ScreenBolo.isSensitive("com.sbi.lotusintouch", "YONO SBI"))
        assertTrue(ScreenBolo.isSensitive("com.snapwork.hdfc", "HDFC Bank"))
        assertTrue(ScreenBolo.isSensitive("in.org.npci.upiapp", "BHIM"))
        assertFalse(ScreenBolo.isSensitive("com.google.android.youtube", "YouTube"))
        assertFalse(ScreenBolo.isSensitive("com.android.vending", "Play Store"))
        assertFalse(ScreenBolo.isSensitive("com.whatsapp", "WhatsApp"))
        assertTrue(ScreenBolo.shouldSkip("com.maira.assistant", "com.maira.assistant", "Maira", false))
        assertTrue(ScreenBolo.shouldSkip("com.google.android.inputmethod.latin", "com.maira.assistant", "Gboard", true))
        assertFalse(ScreenBolo.shouldSkip("com.whatsapp", "com.maira.assistant", "WhatsApp", false))
    }

    @Test fun boloBriefIsShort() {
        val b = ScreenBolo.brief("WhatsApp", "WhatsApp\nChats\nRahul: kal milte hain\n" + "x".repeat(500))
        assertTrue(b.startsWith("WhatsApp खुला है।"))
        assertTrue(b.contains("Chats"))
        assertTrue(b.length < 300)
        assertEquals("YouTube खुला है।", ScreenBolo.brief("YouTube", ""))
    }

    // ---------------- Auto-click safety
    private val installerNodes = listOf(
        UiNode(text = "Maira"), UiNode(text = "Do you want to update this app?"),
        UiNode(text = "Cancel", clickable = true, viewId = "android:id/button2"),
        UiNode(text = "Update", clickable = true, viewId = "android:id/button1")
    )

    @Test fun installerClicksOnlyMairaUpdate() {
        assertEquals(3, AutoClick.installerTarget("com.google.android.packageinstaller", installerNodes))
        // another app's install dialog: never
        val other = listOf(UiNode(text = "Instagram"), UiNode(text = "Cancel", clickable = true), UiNode(text = "Install", clickable = true))
        assertNull(AutoClick.installerTarget("com.google.android.packageinstaller", other))
        // not the installer package: never
        assertNull(AutoClick.installerTarget("com.evil.app", installerNodes))
        // only a Cancel button: never
        assertNull(AutoClick.installerTarget("com.android.packageinstaller", listOf(UiNode(text = "Maira"), UiNode(text = "Cancel", clickable = true))))
    }

    @Test fun permissionAllowOnlyForMaira() {
        val dlg = listOf(
            UiNode(text = "Allow Maira to record audio?"),
            UiNode(text = "While using the app", clickable = true, viewId = "com.android.permissioncontroller:id/permission_allow_foreground_only_button"),
            UiNode(text = "Only this time", clickable = true, viewId = "com.android.permissioncontroller:id/permission_allow_one_time_button"),
            UiNode(text = "Don't allow", clickable = true, viewId = "com.android.permissioncontroller:id/permission_deny_button")
        )
        assertEquals(1, AutoClick.permissionTarget("com.google.android.permissioncontroller", dlg))
        val hindi = listOf(UiNode(text = "Maira को संपर्क देखने की अनुमति दें?"), UiNode(text = "अनुमति न दें", clickable = true), UiNode(text = "अनुमति दें", clickable = true))
        assertEquals(2, AutoClick.permissionTarget("com.android.permissioncontroller", hindi))
        val other = listOf(UiNode(text = "Allow Camera to take pictures?"), UiNode(text = "Allow", clickable = true, viewId = "com.android.permissioncontroller:id/permission_allow_button"))
        assertNull(AutoClick.permissionTarget("com.android.permissioncontroller", other))
        assertNull(AutoClick.permissionTarget("com.whatsapp", dlg))
    }

    // ---------------- Setup screen guide
    @Test fun guideFindsMairaInAccessibilityList() {
        val nodes = listOf(UiNode(text = "Accessibility"), UiNode(text = "TalkBack", clickable = true),
            UiNode(text = "मायरा फ़ोन कंट्रोल", clickable = true), UiNode(text = "Select to Speak", clickable = true))
        assertEquals("स्क्रीन पर 'मायरा फ़ोन कंट्रोल' लिखा है — उस पर टैप कीजिए।", SetupGuide.hint("a11y", nodes))
    }

    @Test fun guideCategoryThenSwitchThenAllow() {
        val list = listOf(UiNode(text = "Accessibility"), UiNode(text = "Installed apps", clickable = true), UiNode(text = "Display", clickable = true))
        assertEquals("'Installed apps' पर टैप कीजिए।", SetupGuide.hint("a11y", list))
        val detail = listOf(UiNode(text = "मायरा फ़ोन कंट्रोल"), UiNode(text = "Use मायरा फ़ोन कंट्रोल", checkable = true, checked = false, clickable = true))
        assertEquals("'Use मायरा फ़ोन कंट्रोल' वाला स्विच दबाकर चालू (ON) कीजिए।", SetupGuide.hint("a11y", detail))
        val dlg = listOf(UiNode(text = "Allow मायरा फ़ोन कंट्रोल to have full control of your device?"),
            UiNode(text = "Allow", clickable = true), UiNode(text = "Deny", clickable = true))
        assertEquals("'Allow' बटन दबाइए।", SetupGuide.hint("a11y", dlg))
    }

    @Test fun guideRestrictedFlow() {
        val msg = listOf(UiNode(text = "Restricted setting"), UiNode(text = "For your security, this setting is currently unavailable."), UiNode(text = "OK", clickable = true))
        assertTrue(SetupGuide.hint("a11y", msg)!!.contains("'OK' दबाइए"))
        val appInfo = listOf(UiNode(text = "App info"), UiNode(desc = "More options", clickable = true), UiNode(text = "Maira"))
        assertEquals("ऊपर दाएँ कोने में तीन बिंदु (⋮) वाला बटन दबाइए।", SetupGuide.hint("restricted", appInfo))
        val menu = listOf(UiNode(text = "Uninstall for all users", clickable = true), UiNode(text = "Allow restricted settings", clickable = true))
        assertTrue(SetupGuide.hint("restricted", menu)!!.startsWith("'Allow restricted settings' लिखा है"))
    }

    @Test fun guideUnknownSourcesAndBattery() {
        val unk = listOf(UiNode(text = "Install unknown apps"), UiNode(text = "Maira"), UiNode(text = "Allow from this source", checkable = true, checked = false, clickable = true))
        assertEquals("'Allow from this source' वाला स्विच दबाकर चालू (ON) कीजिए।", SetupGuide.hint("unknown", unk))
        val bat = listOf(UiNode(text = "Let app always run in background?"), UiNode(text = "Deny", clickable = true), UiNode(text = "Allow", clickable = true))
        assertEquals("'Allow' बटन दबाइए।", SetupGuide.hint("battery", bat))
        assertNull(SetupGuide.hint("nope", bat))
    }

    // ---------------- Model pick + update parsing
    @Test fun modelPickByRam() {
        assertEquals(ModelManager.SMALL, ModelManager.pickDefault(3_800_000_000L))
        assertEquals(ModelManager.SMALL, ModelManager.pickDefault(4_900_000_000L))
        assertEquals(ModelManager.BIG, ModelManager.pickDefault(5_700_000_000L))
        assertEquals(ModelManager.BIG, ModelManager.pickDefault(7_600_000_000L))
        assertEquals(ModelManager.BIG, ModelManager.pickDefault(0L))   // unknown → default big
        assertEquals(60_000L, ModelManager.retryDelayMs(0))
        assertEquals(60 * 60_000L, ModelManager.retryDelayMs(10))
    }

    @Test fun releaseParsing() {
        val json = """{"tag_name":"latest","name":"Maira v1.2 (build 57)","body":"Auto build\nversionCode=157\nversionName=1.2",
            "assets":[{"name":"version.txt","browser_download_url":"https://x/version.txt"},{"name":"Maira.apk","browser_download_url":"https://x/Maira.apk"}]}"""
        val r = UpdateInfo.parseRelease(json)
        assertNotNull(r)
        assertEquals(157, r!!.versionCode)
        assertEquals("1.2", r.versionName)
        assertEquals("https://x/Maira.apk", r.apkUrl)
        assertEquals("https://x/version.txt", r.versionTxtUrl)
        assertTrue(UpdateInfo.isNewer(157, 3))
        assertFalse(UpdateInfo.isNewer(157, 157))
        val old = UpdateInfo.parseRelease("""{"name":"Maira (latest build)","body":"","assets":[{"name":"Maira.apk","browser_download_url":"u"}]}""")
        assertEquals(-1, old!!.versionCode)
        assertEquals(158 to "1.3", UpdateInfo.parseVersionTxt("versionCode=158\nversionName=1.3\n"))
        assertNull(UpdateInfo.parseRelease("not json"))
    }
}
