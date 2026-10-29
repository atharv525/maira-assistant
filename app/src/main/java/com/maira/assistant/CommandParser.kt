package com.maira.assistant

import java.util.Calendar

sealed class Command {
    object StopTalking : Command()
    data class Flash(val on: Boolean) : Command()
    data class Volume(val dir: String) : Command()          // up, down, mute, unmute, max
    data class Media(val key: String) : Command()           // play, pause, toggle, next, prev
    data class YouTubeSearch(val query: String) : Command()
    data class WebSearch(val query: String) : Command()
    data class Alarm(val hour: Int, val minute: Int) : Command()
    data class Timer(val seconds: Int) : Command()
    data class SettingsPanel(val panel: String) : Command() // wifi, bluetooth, data, settings, display, location, sound
    object Battery : Command()
    object Time : Command()
    object Date : Command()
    data class Camera(val selfie: Boolean, val video: Boolean) : Command()
    data class Global(val action: String) : Command()       // home, back, recents, notifications, quick, lock, screenshot
    data class Type(val text: String) : Command()
    data class Scroll(val down: Boolean) : Command()
    data class Click(val text: String) : Command()
    data class Call(val who: String) : Command()
    data class Sms(val who: String, val text: String) : Command()
    data class OpenApp(val name: String) : Command()
    object Help : Command()
    data class ReadScreen(val summarize: Boolean) : Command()
    object ReadNotifications : Command()
}

/** Rule based Hinglish + Hindi command parser. Runs before the LLM. */
object CommandParser {

    fun normalize(s: String): String {
        var t = s.lowercase()
        val nukta = mapOf('\u0958' to 'क', '\u0959' to 'ख', '\u095A' to 'ग', '\u095B' to 'ज', '\u095C' to 'ड', '\u095D' to 'ढ', '\u095E' to 'फ')
        t = t.map { nukta[it] ?: it }.joinToString("")
        t = t.replace("\u093C", "").replace('ँ', 'ं')
        t = t.replace(Regex("(\\d)[.:](\\d)"), "$1:$2")
        t = t.replace(Regex("[?!,।;\"“”'‘’]"), " ")
        t = t.replace(Regex("(?<!\\d)[.:]|[.:](?!\\d)"), " ")
        // remove wake word / politeness fillers
        t = t.replace(Regex("\\b(hey|hi|hello|ok|okay|ओके|हे|हाय|हेलो)\\s+(maira|myra|mayra|maaira|मायरा|माइरा|मैरा|मयरा)\\b"), " ")
        t = t.replace(Regex("(^|\\s)(maira|myra|mayra|maaira|मायरा|माइरा|मैरा|मयरा)(\\s|$)"), " ")
        return " " + t.replace(Regex("\\s+"), " ").trim() + " "
    }

    private fun String.hasAny(vararg w: String): Boolean = w.any { word ->
        if (word.any { it in '\u0900'..'\u097F' }) this.contains(word)
        else this.contains(" $word ") || this.contains(" $word") && word.length >= 4
    }

    private fun String.hasWord(vararg w: String) = w.any { this.contains(" $it ") }

    private val ON = arrayOf("on", "chalu", "chaalu", "jalao", "jala", "jala do", "start", "kholo", "ऑन", "आन", "चालू", "जलाओ", "जला दो", "जला", "शुरू", "चालु")
    private val OFF = arrayOf("off", "band", "bujhao", "bujha", "stop", "ऑफ", "आफ", "बंद", "बुझाओ", "बुझा", "बन्द")
    private val OPEN_VERBS = listOf(
        "open karo", "open kar do", "open kro", "khol do", "kholo", "khol", "open", "chalao", "chala do", "chalu karo",
        "start karo", "launch karo", "launch", "start", "ओपन करो", "ओपन कर दो", "ओपन", "खोल दो", "खोलो", "खोल", "चलाओ",
        "चला दो", "चालू करो", "स्टार्ट करो", "स्टार्ट", "लॉन्च करो", "शुरू करो", "करो", "kar do", "karo", "कर दो"
    )
    private val FILLERS = listOf(
        "please", "plz", "zara", "jara", "jaldi", "mera", "meri", "mere", "app", "application", "ko", "ka", "ki", "ke", "ek", "wala", "vala",
        "प्लीज", "जरा", "जल्दी", "मेरा", "मेरी", "मेरे", "ऐप", "एप", "एप्प", "एप्लीकेशन", "को", "का", "की", "के", "एक", "वाला", "तो", "to", "na", "ना"
    )

    private val hindiNums = mapOf(
        "तीन" to 3, "चार" to 4, "पांच" to 5, "पाँच" to 5, "छह" to 6, "छः" to 6, "छे" to 6, "सात" to 7, "आठ" to 8,
        "नौ" to 9, "दस" to 10, "ग्यारह" to 11, "बारह" to 12, "पंद्रह" to 15, "बीस" to 20, "पच्चीस" to 25, "तीस" to 30, "चालीस" to 40,
        "पैंतालीस" to 45, "पचास" to 50,
        "teen" to 3, "char" to 4, "chaar" to 4, "paanch" to 5, "panch" to 5, "chhe" to 6, "che" to 6, "saat" to 7,
        "aath" to 8, "nau" to 9, "das" to 10, "gyarah" to 11, "barah" to 12, "baarah" to 12, "pandrah" to 15, "bees" to 20, "tees" to 30,
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10,
        "eleven" to 11, "twelve" to 12, "fifteen" to 15, "twenty" to 20, "thirty" to 30
    )

    /** Replace Hindi/Hinglish number words with digits. */
    private fun numify(t: String): String {
        var s = t
        for ((w, n) in hindiNums.entries.sortedByDescending { it.key.length }) {
            s = s.replace(Regex("(?<=\\s)" + Regex.escape(w) + "(?=\\s)"), n.toString())
        }
        s = s.replace(Regex("(?<=\\s)(ek|एक)(?=\\s+(baje|बजे|minute|मिनट|ghant|घंट|second|सेकंड|hour))"), "1")
        s = s.replace(Regex("(?<=\\s)(do|दो)(?=\\s+(baje|बजे|minute|मिनट|ghant|घंट|second|सेकंड|hour))"), "2")
        // Devanagari digits
        val d = "०१२३४५६७८९"
        s = s.map { if (it in d) ('0' + d.indexOf(it)) else it }.joinToString("")
        return s
    }

    fun parse(raw: String): Command? {
        val t = normalize(raw)
        if (t.isBlank()) return null
        val n = numify(t)

        // --- stop speaking
        if (t.trim().split(" ").size <= 3 && t.hasWord("chup", "ruko", "ruk", "stop", "bas", "चुप", "रुको", "रुक", "बस", "स्टॉप", "shut")) {
            if (!t.hasAny("gaana", "gana", "song", "music", "गाना", "म्यूजिक", "timer", "टाइमर", "alarm", "अलार्म"))
                return Command.StopTalking
        }

        // --- help
        if (t.hasAny("kya kya kar sakti", "क्या क्या कर सकती", "help", "हेल्प", "commands", "कमांड")) return Command.Help

        // --- notifications read aloud ("notification padho"); "notification kholo" stays a global action below
        if (t.hasAny("notification", "notifications", "notifiction", "नोटिफिकेशन", "नोटिफिकेशंस", "नोटिफिकेशन्स", "सूचना", "सूचनाएं", "सूचनाएँ") &&
            t.hasAny("padho", "padh", "parho", "sunao", "suna", "batao", "bata", "read", "kya aaya", "kya aya", "kiska", "किसका",
                "पढो", "पढ", "सुनाओ", "सुना", "बताओ", "बता", "क्या आया", "क्या आए", "रीड")) return Command.ReadNotifications

        // --- screen reading
        parseScreen(t)?.let { return it }

        // --- SMS: "papa ko message bhejo ki main late aaunga"
        Regex("^\\s*(.+?)\\s+(?:ko|को)\\s+(?:message|msg|sms|massage|मैसेज|मेसेज|मसेज|संदेश|एसएमएस)\\s*(?:bhejo|bhej do|bhejna|karo|kar do|send karo|भेजो|भेज दो|भेजना|करो|कर दो|सेंड करो)?\\s*(?:ki|kee|kii|कि|की|that)?\\s*(.*)$").find(t)?.let {
            val who = cleanName(it.groupValues[1]); val msg = it.groupValues[2].trim()
            if (who.isNotBlank()) return Command.Sms(who, msg)
        }
        Regex("^\\s*(?:message|msg|sms|मैसेज|मेसेज)\\s+(?:bhejo|karo|भेजो|करो)\\s+(.+?)\\s+(?:ko|को)\\s*(?:ki|कि|की)?\\s*(.*)$").find(t)?.let {
            val who = cleanName(it.groupValues[1]); if (who.isNotBlank()) return Command.Sms(who, it.groupValues[2].trim())
        }

        // --- Call: "mummy ko call karo", "call karo papa ko", "9876543210 par call karo"
        val callWords = "call|kol|phone|fone|ring|dial|कॉल|काल|कोल|फोन|डायल"
        Regex("^\\s*(.+?)\\s+(?:ko|को|par|pe|पर|पे)\\s+(?:$callWords)").find(t)?.let {
            val who = cleanName(it.groupValues[1]); if (who.isNotBlank()) return Command.Call(who)
        }
        Regex("^\\s*(.+?)\\s+(?:$callWords)\\s+(?:karo|kar do|lagao|laga do|milao|करो|कर दो|लगाओ|लगा दो|मिलाओ)\\s*$").find(t)?.let {
            val who = cleanName(it.groupValues[1]); if (who.isNotBlank()) return Command.Call(who)
        }

        // --- flashlight
        if (t.hasAny("torch", "flashlight", "flash light", "टॉर्च", "टार्च", "फ्लैशलाइट", "फ्लैश लाइट", "फ्लैश", "flash", "बत्ती", "light jala", "लाइट जला")) {
            return Command.Flash(!t.hasAny(*OFF))
        }

        // --- YouTube search: "youtube pe arijit ke gaane chalao"
        Regex("(?:youtube|you tube|यूट्यूब|यूट्यूब)\\s+(?:par|pe|per|mein|me|पर|पे|में|मे)\\s+(.+?)\\s+(?:chalao|chala do|lagao|laga do|bajao|search karo|search|dikhao|play karo|play|चलाओ|चला दो|लगाओ|लगा दो|बजाओ|सर्च करो|सर्च|दिखाओ|प्ले करो|प्ले)\\s").find(t)?.let {
            return Command.YouTubeSearch(it.groupValues[1].trim())
        }
        Regex("\\s(.+?)\\s+(?:youtube|यूट्यूब)\\s+(?:par|pe|per|पर|पे)\\s+(?:chalao|lagao|bajao|search karo|dikhao|play karo|चलाओ|लगाओ|बजाओ|सर्च करो|दिखाओ|प्ले करो)\\s").find(t)?.let {
            return Command.YouTubeSearch(it.groupValues[1].trim())
        }
        // --- Google search
        Regex("(?:google|गूगल)\\s+(?:par|pe|पर|पे|mein|में)\\s+(.+?)\\s+(?:search karo|search|dhundho|khojo|सर्च करो|सर्च|ढूंढो|खोजो)\\s").find(t)?.let {
            return Command.WebSearch(it.groupValues[1].trim())
        }

        // --- volume
        if (t.hasAny("volume", "awaaz", "awaz", "aawaz", "sound", "वॉल्यूम", "वोल्यूम", "आवाज", "साउंड")) {
            when {
                t.hasAny("unmute", "अनम्यूट", "wapas", "वापस", "chalu", "चालू") -> return Command.Volume("unmute")
                t.hasAny("mute", "म्यूट", "band", "बंद", "silent", "साइलेंट", "बन्द") -> return Command.Volume("mute")
                t.hasAny("full", "poora", "pura", "max", "फुल", "पूरा", "पूरी", "मैक्स") -> return Command.Volume("max")
                t.hasAny("badhao", "badha", "tez", "tej", "zyada", "jyada", "up", "increase", "high", "बढ़ाओ", "बढाओ", "बढ़ा", "बढा", "तेज", "ज्यादा", "अप", "ऊपर") -> return Command.Volume("up")
                t.hasAny("kam", "dheere", "dhire", "ghatao", "down", "low", "decrease", "कम", "धीरे", "घटाओ", "डाउन", "नीचे") -> return Command.Volume("down")
            }
        }
        if (t.hasAny("silent mode", "साइलेंट मोड", "phone silent", "फोन साइलेंट", "साइलेंट करो", "silent karo") || t.hasWord("mute", "म्यूट")) return Command.Volume(if (t.hasWord("unmute")) "unmute" else "mute")

        // --- media
        val music = t.hasAny("gaana", "gana", "gaane", "song", "music", "गाना", "गाने", "म्यूजिक", "संगीत", "सॉन्ग", "audio")
        if (music || t.hasWord("pause", "पॉज", "resume", "next", "previous")) {
            when {
                t.hasAny("agla", "next", "अगला", "नेक्स्ट", "badlo", "बदलो", "dusra", "दूसरा") -> return Command.Media("next")
                t.hasAny("pichla", "previous", "पिछला", "प्रीवियस") -> return Command.Media("prev")
                t.hasAny("roko", "rok do", "band", "pause", "stop", "रोको", "रोक दो", "बंद", "पॉज", "स्टॉप", "बन्द") -> return Command.Media("pause")
                t.hasAny("chalao", "chala do", "bajao", "baja do", "play", "lagao", "resume", "चलाओ", "चला दो", "बजाओ", "बजा दो", "प्ले", "लगाओ", "शुरू") -> return Command.Media("play")
            }
        }

        // --- alarm
        if (t.hasAny("alarm", "अलार्म", "अलार्म", "jagana", "jaga dena", "उठा देना", "जगा देना", "जगाना")) {
            parseClock(n)?.let { return Command.Alarm(it.first, it.second) }
            return Command.SettingsPanel("alarm")
        }
        // --- timer
        if (t.hasAny("timer", "टाइमर", "टाईमर")) {
            parseDuration(n)?.let { return Command.Timer(it) }
            return Command.SettingsPanel("timer")
        }

        // --- settings panels
        if (t.hasAny("wifi", "wi-fi", "wi fi", "वाईफाई", "वाई फाई", "वाईफ़ाई", "वायफाय")) return Command.SettingsPanel("wifi")
        if (t.hasAny("bluetooth", "ब्लूटूथ", "ब्लू टूथ")) return Command.SettingsPanel("bluetooth")
        if (t.hasAny("mobile data", "internet", "इंटरनेट", "मोबाइल डेटा", "मोबाइल डाटा", "data on", "डेटा")) return Command.SettingsPanel("data")
        if (t.hasAny("hotspot", "हॉटस्पॉट")) return Command.SettingsPanel("hotspot")
        if (t.hasAny("brightness", "ब्राइटनेस", "display setting", "डिस्प्ले")) return Command.SettingsPanel("display")
        if (t.hasAny("location", "gps", "लोकेशन", "जीपीएस")) return Command.SettingsPanel("location")

        // --- battery
        if (t.hasAny("battery", "बैटरी", "बेटरी", "charging", "चार्जिंग", "charge kitna", "चार्ज कितना")) return Command.Battery

        // --- time / date
        if (t.hasAny("kitne baje", "kitna baja", "time kya", "time kitna", "samay", "टाइम", "समय", "कितने बजे", "कितना बजा", "क्या बजा", "what time", "current time")) return Command.Time
        if (t.hasAny("date", "tareekh", "tarikh", "taarikh", "तारीख", "डेट", "aaj kya din", "kaun sa din", "आज क्या दिन", "कौन सा दिन", "कौनसा दिन", "दिनांक", "day today")) return Command.Date

        // --- screenshot / lock / notifications / recents / home / back  (accessibility)
        if (t.hasAny("screenshot", "screen shot", "स्क्रीनशॉट", "स्क्रीन शॉट", "स्क्रीनशॉर्ट")) return Command.Global("screenshot")
        if (t.hasAny("lock", "लॉक", "लोक") && t.hasAny("phone", "screen", "फोन", "स्क्रीन", "karo", "kar do", "करो", "कर दो")) return Command.Global("lock")
        if (t.hasAny("quick setting", "क्विक सेटिंग")) return Command.Global("quick")
        if (t.hasAny("notification", "नोटिफिकेशन", "सूचना")) return Command.Global("notifications")
        if (t.hasAny("recent", "रीसेंट", "हाल के ऐप", "multitask")) return Command.Global("recents")
        if (t.hasAny("home screen", "होम स्क्रीन", "home jao", "home par jao", "home pe jao", "home karo", "होम जाओ", "होम पर जाओ", "होम पे जाओ", "होम करो", "go home", "home button", "होम बटन")) return Command.Global("home")
        if (t.trim() == "home" || t.trim() == "होम") return Command.Global("home")
        if (t.hasAny("peeche jao", "piche jao", "back jao", "back karo", "wapas jao", "go back", "पीछे जाओ", "पीछे", "बैक करो", "बैक जाओ", "वापस जाओ", "बैक")) return Command.Global("back")
        if (t.trim() == "back") return Command.Global("back")

        // --- scroll
        if (t.hasAny("scroll", "स्क्रॉल", "स्क्रोल")) {
            val up = t.hasAny("upar", "up", "ऊपर", "उपर", "अप")
            return Command.Scroll(!up)
        }

        // --- type / likho
        Regex("^\\s*(?:type karo|type kro|type|likho|likh do|टाइप करो|टाइप|लिखो|लिख दो)\\s+(.+)$").find(t)?.let {
            return Command.Type(raw.trim().let { r -> stripLeadingVerb(r) })
        }
        Regex("^\\s*(.+?)\\s+(?:type karo|type kar do|likho|likh do|टाइप करो|टाइप कर दो|लिखो|लिख दो)\\s*$").find(t)?.let {
            return Command.Type(it.groupValues[1].trim())
        }
        // --- click
        Regex("^\\s*(.+?)\\s+(?:par|pe|पर|पे)\\s+(?:click karo|click|tap karo|dabao|क्लिक करो|क्लिक|टैप करो|दबाओ)\\s*$").find(t)?.let {
            return Command.Click(it.groupValues[1].trim())
        }

        // --- camera
        if (t.hasAny("photo", "selfie", "camera", "फोटो", "सेल्फी", "कैमरा", "तस्वीर", "picture", "video record", "वीडियो रिकॉर्ड")) {
            if (t.hasAny("kheecho", "khincho", "kheencho", "khicho", "lo", "le lo", "click", "khol", "kholo", "open", "kar", "record",
                    "खींचो", "खीचो", "खिंचो", "लो", "ले लो", "क्लिक", "खोलो", "खोल", "ओपन", "करो", "रिकॉर्ड", "निकालो", "nikalo")) {
                return Command.Camera(selfie = t.hasAny("selfie", "सेल्फी", "front", "फ्रंट"), video = t.hasAny("video", "वीडियो"))
            }
        }

        val callWords2 = "call|kol|phone|fone|ring|dial|कॉल|काल|कोल|फोन|डायल"
        Regex("^\\s*(?:$callWords2)\\s+(?:karo|kar do|lagao|laga do|milao|करो|कर दो|लगाओ|लगा दो|मिलाओ)?\\s*(.+?)\\s*(?:ko|को)?\\s*$").find(t)?.let {
            val who = cleanName(it.groupValues[1]); if (who.isNotBlank() && OPEN_VERBS.none { v -> who == v || who.endsWith(" $v") }) return Command.Call(who)
        }
        // --- Settings app
        if (t.hasAny("settings", "setting", "सेटिंग", "सेटिंग्स") && t.hasAny(*OPEN_VERBS.toTypedArray())) return Command.SettingsPanel("settings")

        // --- open app
        for (v in OPEN_VERBS.sortedByDescending { it.length }) {
            val idx = t.lastIndexOf(" $v ")
            if (idx >= 0) {
                val before = t.substring(0, idx)
                val after = t.substring(idx + v.length + 1)
                var name = cleanName(if (before.isNotBlank()) before else after)
                if (name.isNotBlank() && name.length <= 40) {
                    // don't treat questions as app names
                    if (!name.hasQuestionWord()) return Command.OpenApp(name)
                }
                break
            }
        }
        return null
    }

    private val SCREEN_WORDS = arrayOf("screen", "scren", "skrin", "स्क्रीन", "स्क्रिन", "page", "पेज")
    private val THIS_WORDS = arrayOf("isme", "ismein", "ismen", "isko", "ise", "iska", "iske", "इसका", "इसके", "इसमें", "इसमे", "इस में", "इसको", "इसे")
    private val SUMMARY_WORDS = arrayOf("summary", "summarize", "saransh", "samjhao", "samjha do", "samjhaao", "explain", "matlab", "saar",
        "सारांश", "समरी", "समझाओ", "समझा दो", "समझाइए", "मतलब", "सार", "एक्सप्लेन")
    private val READ_WORDS = arrayOf("padho", "padh", "parho", "padhke", "sunao", "read", "kya hai", "kya likha", "kya dikh", "dikh raha", "batao",
        "पढो", "पढ", "सुनाओ", "क्या है", "क्या लिखा", "क्या दिख", "दिख रहा", "बताओ", "रीड")

    /** "screen padho", "screen par kya hai", "ye kya likha hai", "padh ke sunao" / "screen ka summary batao", "screen samjhao". */
    private fun parseScreen(t: String): Command? {
        if (t.hasAny("screenshot", "screen shot", "स्क्रीनशॉट", "स्क्रीन शॉट", "स्क्रीनशॉर्ट", "home screen", "होम स्क्रीन", "lock screen", "लॉक स्क्रीन",
                "screen lock", "स्क्रीन लॉक", "screen record", "स्क्रीन रिकॉर्ड", "brightness", "ब्राइटनेस", "screen time")) return null
        if (t.hasAny("lock", "लॉक", "scroll", "स्क्रॉल")) return null
        val screen = t.hasAny(*SCREEN_WORDS)
        val thisWord = t.hasAny(*THIS_WORDS)
        val likha = t.hasAny("kya likha", "kya likhaa", "क्या लिखा", "kya lika")
        val readOnly = t.hasAny("padh ke sunao", "padhke sunao", "padh kar sunao", "padhkar sunao", "parh ke sunao", "पढ के सुनाओ", "पढके सुनाओ", "पढ कर सुनाओ", "पढकर सुनाओ") ||
            t.trim() in setOf("ye padho", "yeh padho", "ise padho", "isko padho", "ये पढो", "यह पढो", "इसे पढो", "इसको पढो", "padho", "पढो")
        if ((screen || likha || thisWord) && t.hasAny(*SUMMARY_WORDS)) return Command.ReadScreen(true)
        if (likha || readOnly) return Command.ReadScreen(false)
        if (thisWord && t.hasAny("padho", "padh", "sunao", "पढो", "पढ", "सुनाओ")) return Command.ReadScreen(false)
        if (screen && t.hasAny(*READ_WORDS)) return Command.ReadScreen(false)
        return null
    }

    private fun String.hasQuestionWord(): Boolean {
        val s = " $this "
        return listOf(" kya ", " kaise ", " kyun ", " kyon ", " kaun ", " kab ", " kahan ", " क्या ", " कैसे ", " क्यों ", " कौन ", " कब ", " कहाँ ", " कहां ")
            .any { s.contains(it) }
    }

    private fun stripLeadingVerb(raw: String): String {
        var r = raw.trim()
        val re = Regex("^(?i)(hey\\s+maira\\s+|maira\\s+|मायरा\\s+)?(type karo|type kro|type|likho|likh do|टाइप करो|टाइप|लिखो|लिख दो)\\s+")
        r = r.replace(re, "")
        return r.trim()
    }

    fun cleanName(s: String): String {
        var words = s.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        words = words.filter { it !in FILLERS }
        return words.joinToString(" ").trim()
    }

    /** Parses "6 baje", "6:30", "subah 6 baje", "shaam saade 7 baje" -> (hour24, minute) */
    fun parseClock(t: String): Pair<Int, Int>? {
        var h: Int; var m = 0
        val hm = Regex("(\\d{1,2})\\s*[:.]\\s*(\\d{2})").find(t)
        if (hm != null) {
            h = hm.groupValues[1].toInt(); m = hm.groupValues[2].toInt()
        } else {
            val b = Regex("(\\d{1,2})\\s*(?:baje|bje|bajeh|बजे|बज|o'?clock|am|pm|बजकर)").find(t)
                ?: Regex("(\\d{1,2})\\s").find(t) ?: return null
            h = b.groupValues[1].toInt()
            Regex("(?:bajkar|बजकर|baj ke|बज के)\\s*(\\d{1,2})").find(t)?.let { m = it.groupValues[1].toInt() }
            if (t.hasAny("saade", "sade", "साढ़े", "साढे")) m = 30
            if (t.hasAny("sava", "sawa", "सवा")) m = 15
            if (t.hasAny("paune", "पौने")) { m = 45; h = if (h == 1) 12 else h - 1 }
        }
        if (h > 23 || m > 59) return null
        val morning = t.hasAny("subah", "suba", "सुबह", "morning", "am", "सवेरे")
        val noon = t.hasAny("dopahar", "dopehar", "दोपहर", "afternoon")
        val evening = t.hasAny("shaam", "sham", "शाम", "evening", "pm")
        val night = t.hasAny("raat", "रात", "night")
        if (h in 1..11) {
            when {
                morning -> {}
                noon -> h += 12
                evening -> h += 12
                night -> if (h >= 6) h += 12
                else -> {
                    val now = Calendar.getInstance()
                    val nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
                    if (h * 60 + m <= nowMin && (h + 12) * 60 + m > nowMin) h += 12
                }
            }
        } else if (h == 12 && night) h = 0
        return h to m
    }

    /** "5 minute", "30 second", "1 ghanta", "aadha ghanta", "डेढ़ घंटे" -> seconds */
    fun parseDuration(t: String): Int? {
        var total = 0
        if (t.hasAny("aadha ghanta", "aadhe ghante", "आधा घंटा", "आधे घंटे")) total += 1800
        if (t.hasAny("dedh ghanta", "dedh ghante", "डेढ़ घंटा", "डेढ घंटा", "डेढ़ घंटे", "डेढ घंटे")) total += 5400
        Regex("(\\d+)\\s*(?:ghante|ghanta|ghanton|hour|hours|hr|घंटे|घंटा|घंटों)").find(t)?.let { total += it.groupValues[1].toInt() * 3600 }
        Regex("(\\d+)\\s*(?:minute|minutes|min|mins|minat|मिनट|मिनिट)").find(t)?.let { total += it.groupValues[1].toInt() * 60 }
        Regex("(\\d+)\\s*(?:second|seconds|sec|secs|सेकंड|सेकेंड|सेकण्ड)").find(t)?.let { total += it.groupValues[1].toInt() }
        if (total == 0) Regex("(\\d+)").find(t)?.let { total = it.groupValues[1].toInt() * 60 }
        return if (total > 0) total else null
    }

    private val yesWords = listOf("haan", "han", "ha", "haa", "ji", "ji haan", "yes", "ok", "okay", "theek hai", "thik hai", "bhejo", "bhej do", "kar do", "sure",
        "हाँ", "हां", "हा", "जी", "जी हाँ", "जी हां", "यस", "ओके", "ठीक है", "भेजो", "भेज दो", "कर दो", "बिल्कुल")
    private val noWords = listOf("nahi", "nahin", "na", "no", "mat", "cancel", "rehne do", "rahne do", "नहीं", "नही", "ना", "नो", "मत", "कैंसल", "रहने दो")

    fun isYes(s: String): Boolean { val t = normalize(s); return !isNo(s) && yesWords.any { t.contains(" $it ") } }
    fun isNo(s: String): Boolean { val t = normalize(s); return noWords.any { t.contains(" $it ") } }
}
