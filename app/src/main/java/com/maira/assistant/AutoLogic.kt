package com.maira.assistant

import org.json.JSONObject

/** A flattened accessibility node (pure data, so the decision logic below is unit-testable). */
data class UiNode(
    val text: String = "",
    val desc: String = "",
    val viewId: String = "",
    val clickable: Boolean = false,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val enabled: Boolean = true,
    val className: String = ""
) {
    val label: String get() = text.ifBlank { desc }.trim()
    val isSwitch: Boolean get() = checkable || className.endsWith("Switch") || className.endsWith("SwitchCompat") || className.endsWith("CheckBox")
}

private fun norm(s: String) = s.lowercase().replace(Regex("[\\u200B-\\u200F\\u2060\\uFEFF]"), "").replace(Regex("\\s+"), " ").trim()

/**
 * Safe, tightly scoped auto-taps done by the accessibility service:
 *  1) the system package installer's "Install/Update" button — only while Maira's OWN update is armed and the dialog names Maira;
 *  2) "Allow" on runtime-permission dialogs — only while Auto Setup armed it and the dialog names Maira.
 * Never taps anything else.
 */
object AutoClick {
    val APP_NAMES = listOf("maira", "मायरा")

    fun isInstallerPkg(pkg: String) = pkg.contains("packageinstaller")
    fun isPermissionPkg(pkg: String) = pkg.contains("permissioncontroller") || pkg.contains("packageinstaller") || pkg == "com.vivo.permissionmanager"

    private val DENY = listOf("cancel", "don't", "dont", "deny", "not now", "रद्द", "न दें", "नहीं", "अस्वीकार", "only this time", "सिर्फ़ इस बार", "सिर्फ इस बार", "settings", "सेटिंग")
    private val INSTALL_WORDS = setOf("update", "install", "अपडेट करें", "अपडेट", "इंस्टॉल करें", "इंस्टॉल", "continue installing", "continue installation", "continue to install", "install update", "update app")
    private val ALLOW_IDS = listOf("permission_allow_foreground_only_button", "permission_allow_button", "permission_allow_always_button")
    private val ALLOW_WORDS = setOf("allow", "while using the app", "allow only while using the app", "अनुमति दें", "ऐप का इस्तेमाल करते समय", "ऐप इस्तेमाल करते समय",
        "ऐप के इस्तेमाल के दौरान", "इस्तेमाल करते समय अनुमति दें", "ऐप का इस्तेमाल करते समय ही")

    fun mentionsMaira(nodes: List<UiNode>): Boolean = nodes.any { n -> val s = norm(n.text + " " + n.desc); APP_NAMES.any { s.contains(it) } }

    private fun denied(s: String) = DENY.any { s.contains(it) }

    /** Index of the Install/Update button to tap, or null. Caller must also check that Maira's update is armed. */
    fun installerTarget(pkg: String, nodes: List<UiNode>): Int? {
        if (!isInstallerPkg(pkg) || !mentionsMaira(nodes)) return null
        nodes.forEachIndexed { i, n ->
            val s = norm(n.label)
            if (n.enabled && s in INSTALL_WORDS && !denied(s)) return i
        }
        return null
    }

    /** Index of the "Allow" button on Maira's own runtime-permission dialog, or null. Caller must check setup armed it. */
    fun permissionTarget(pkg: String, nodes: List<UiNode>): Int? {
        if (!isPermissionPkg(pkg) || !mentionsMaira(nodes)) return null
        for (id in ALLOW_IDS) {
            val i = nodes.indexOfFirst { it.enabled && it.viewId.endsWith(":id/$id") }
            if (i >= 0) return i
        }
        nodes.forEachIndexed { i, n ->
            val s = norm(n.label)
            if (n.enabled && s in ALLOW_WORDS && !denied(s)) return i
        }
        return null
    }
}

/** Reads the current settings screen during Auto Setup and says in Hindi exactly what to tap next. */
object SetupGuide {
    data class Spec(val targets: List<String>, val categories: List<String> = emptyList(), val fallback: String)

    private val ALLOW = listOf("allow", "अनुमति दें", "ok", "ठीक है", "turn on", "चालू करें", "agree", "सहमत", "yes", "हाँ", "हां")
    private val CATEGORIES_A11Y = listOf("installed apps", "installed services", "downloaded apps", "downloaded services", "downloaded",
        "डाउनलोड किए गए ऐप", "डाउनलोड की गई ऐप्स", "इंस्टॉल की गई सेवाएं", "इंस्टॉल की गई सेवाएँ", "इंस्टॉल किए गए ऐप", "डाउनलोड की गई सेवाएं",
        "more downloaded services")
    private val RESTRICTED_MSG = listOf("restricted setting", "प्रतिबंधित सेटिंग")
    private val ALLOW_RESTRICTED = listOf("allow restricted settings", "प्रतिबंधित सेटिंग की अनुमति दें", "प्रतिबंधित सेटिंग्स की अनुमति दें")
    private val MORE = listOf("more options", "अधिक विकल्प", "और विकल्प", "more")

    val SPECS = mapOf(
        "a11y" to Spec(listOf("मायरा फ़ोन कंट्रोल", "मायरा फोन कंट्रोल", "maira"), CATEGORIES_A11Y,
            "नीचे स्क्रॉल करके 'मायरा फ़ोन कंट्रोल' ढूँढिए। अगर नहीं दिखे तो 'Installed apps' या 'डाउनलोड किए गए ऐप' खोलिए।"),
        "notif" to Spec(listOf("मायरा नोटिफिकेशन पढ़ना", "maira", "मायरा"), emptyList(), "नीचे स्क्रॉल करके 'Maira' ढूँढिए और उसे चालू कीजिए।"),
        "dnd" to Spec(listOf("maira", "मायरा"), emptyList(), "नीचे स्क्रॉल करके 'Maira' ढूँढिए और उसे चालू कीजिए।"),
        "unknown" to Spec(listOf("allow from this source", "इस स्रोत से अनुमति दें", "इस सोर्स से अनुमति दें", "maira"), emptyList(), "'Allow from this source' वाला स्विच चालू कीजिए।"),
        "autostart" to Spec(listOf("maira", "मायरा"), emptyList(), "सूची में 'Maira' ढूँढकर उसका स्विच चालू कीजिए।"),
        "battery" to Spec(listOf("maira"), emptyList(), "'Allow' या 'अनुमति दें' दबाइए।"),
        "restricted" to Spec(ALLOW_RESTRICTED, emptyList(), "ऊपर दाएँ कोने में तीन बिंदु (⋮) दबाइए, फिर 'Allow restricted settings' दबाइए।")
    )

    private fun has(nodes: List<UiNode>, words: List<String>) = nodes.any { n -> val s = norm(n.text + " " + n.desc); words.any { s.contains(norm(it)) } }

    /** Hindi instruction for what to tap next on this screen, or null if nothing useful. */
    fun hint(step: String, nodes: List<UiNode>): String? {
        val spec = SPECS[step] ?: return null
        val visible = nodes.filter { it.label.isNotBlank() || it.isSwitch }
        if (visible.isEmpty()) return null

        // 0) Android's "Restricted setting" pop-up (sideloaded app on Android 13+)
        if ((step == "a11y" || step == "notif") && has(visible, RESTRICTED_MSG) && !has(visible, ALLOW_RESTRICTED)) {
            return "यह 'Restricted setting' वाला संदेश है। 'OK' दबाइए। उसके बाद मैं आपको ऐप की सेटिंग में ले जाकर बताऊँगी कि इसे कैसे खोलें।"
        }
        if (step == "restricted") {
            visible.firstOrNull { n -> ALLOW_RESTRICTED.any { norm(n.label).contains(norm(it)) } }?.let {
                return "'${it.label}' लिखा है — उस पर टैप कीजिए। फिर फ़ोन का लॉक या फिंगरप्रिंट माँगे तो दे दीजिए।"
            }
            if (visible.any { n -> n.clickable && MORE.any { norm(n.desc) == norm(it) || norm(n.desc).startsWith(norm(it)) } })
                return "ऊपर दाएँ कोने में तीन बिंदु (⋮) वाला बटन दबाइए।"
            return spec.fallback
        }

        val mentionsTarget = has(visible, spec.targets)
        // 1) A confirmation dialog with an Allow / OK button
        val buttons = visible.filter { it.clickable && !it.isSwitch }
        val allowBtn = buttons.firstOrNull { n -> val s = norm(n.label); ALLOW.any { s == it } }
        val hasDeny = buttons.any { n -> val s = norm(n.label); listOf("deny", "don't allow", "cancel", "अस्वीकार", "रद्द करें", "न दें", "अनुमति न दें", "stop", "नहीं").any { s == it || s.startsWith(it) } }
        if (allowBtn != null && (hasDeny || step == "battery")) return "'${allowBtn.label}' बटन दबाइए।"

        // 2) An OFF switch on a page about Maira
        val switches = visible.filter { it.isSwitch }
        val generic = listOf("use", "इस्तेमाल", "allow", "अनुमति", "चालू", "permit", "access")
        val offSwitch = switches.firstOrNull { s ->
            !s.checked && s.enabled && (spec.targets.any { norm(s.label).contains(norm(it)) } ||
                (switches.size <= 2 && (s.label.isBlank() || generic.any { g -> norm(s.label).contains(g) })))
        }
        if (offSwitch != null && mentionsTarget) {
            val lbl = offSwitch.label.takeIf { it.isNotBlank() && it.length < 60 }
                ?: visible.firstOrNull { n -> !n.isSwitch && spec.targets.any { norm(n.label).contains(norm(it)) } }?.label
            return if (lbl != null) "'$lbl' वाला स्विच दबाकर चालू (ON) कीजिए।" else "स्क्रीन पर बंद (OFF) स्विच को दबाकर चालू कीजिए।"
        }
        // 3) The Maira item itself in a list
        visible.firstOrNull { n -> !n.isSwitch && spec.targets.any { norm(n.label).contains(norm(it)) } && n.label.length < 60 }?.let {
            return "स्क्रीन पर '${it.label}' लिखा है — उस पर टैप कीजिए।"
        }
        // 4) A category that leads there
        visible.firstOrNull { n -> spec.categories.any { norm(n.label) == norm(it) || (norm(it).length > 10 && norm(n.label).contains(norm(it))) } }?.let {
            return "'${it.label}' पर टैप कीजिए।"
        }
        return spec.fallback
    }
}

/** "Screen bolo" mode helpers: what to skip and what to say. */
object ScreenBolo {
    private val SENSITIVE_PKG = listOf("bank", "upi", "paytm", "phonepe", "nbu.paisa", "npci", "mobikwik", "freecharge", "dreamplug", "sbi.", ".sbi",
        "hdfc", "icici", "axis", "kotak", "bankofbaroda", "bobcard", "pnb", "canara", "unionbank", "idbi", "yesbank", "indusind", "federal", "airtel.money",
        "amazon.pay", "paypal", "bhim", "navi", "groww", "zerodha", "upstox", "angelone", "lastpass", "bitwarden", "1password", "authenticator")
    private val SENSITIVE_LABEL = Regex("(?i)(\\bbank|\\bupi\\b|\\bpay\\b|wallet|बैंक|पेमेंट|password|पासवर्ड)")
    private val SKIP_PKG = setOf("com.android.systemui", "android")

    fun isSensitive(pkg: String, label: String): Boolean {
        val p = pkg.lowercase()
        return SENSITIVE_PKG.any { p.contains(it) } || SENSITIVE_LABEL.containsMatchIn(label)
    }

    fun shouldSkip(pkg: String, ownPkg: String, label: String, isKeyboard: Boolean): Boolean =
        pkg.isBlank() || pkg == ownPkg || pkg in SKIP_PKG || isKeyboard || isSensitive(pkg, label)

    /** Short spoken line: app name + first lines of the screen. */
    fun brief(appName: String, text: String, maxChars: Int = 220): String {
        val lines = text.split('\n').map { ScreenText.clean(it) }.filter { it.length > 1 && !it.equals(appName, true) }
        if (lines.isEmpty()) return "$appName खुला है।"
        val sb = StringBuilder()
        for (l in lines) {
            if (sb.isNotEmpty() && sb.length + l.length > maxChars) break
            if (sb.isNotEmpty()) sb.append("। ")
            sb.append(l.trimEnd('.', '।').take(maxChars))
        }
        return "$appName खुला है। स्क्रीन पर: $sb।"
    }
}

/** GitHub release parsing for auto-update. */
object UpdateInfo {
    data class Remote(val versionCode: Int, val versionName: String?, val apkUrl: String?, val versionTxtUrl: String?)

    private val CODE_RE = Regex("versionCode\\s*[=:]\\s*(\\d+)")
    private val NAME_RE = Regex("versionName\\s*[=:]\\s*([0-9A-Za-z._-]+)")

    fun parseVersionTxt(s: String): Pair<Int?, String?> =
        CODE_RE.find(s)?.groupValues?.get(1)?.toIntOrNull() to NAME_RE.find(s)?.groupValues?.get(1)

    /** Parses the release JSON. versionCode comes from the body/name ("versionCode=123"); -1 if absent (then read version.txt). */
    fun parseRelease(json: String): Remote? = try {
        val j = JSONObject(json)
        val text = j.optString("body") + "\n" + j.optString("name")
        val (code, name) = parseVersionTxt(text)
        var apk: String? = null; var ver: String? = null
        val assets = j.optJSONArray("assets")
        if (assets != null) for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val n = a.optString("name"); val u = a.optString("browser_download_url")
            if (n.equals("Maira.apk", true)) apk = u
            else if (n.equals("version.txt", true)) ver = u
            else if (apk == null && n.endsWith(".apk", true)) apk = u
        }
        Remote(code ?: -1, name, apk, ver)
    } catch (_: Exception) { null }

    fun isNewer(remoteCode: Int, localCode: Int) = remoteCode > localCode
}
