package com.maira.assistant

/** Pure (unit-tested) fuzzy matching of a spoken app name against installed launcher apps. */
object AppMatcher {
    data class App(val label: String, val pkg: String)

    sealed class Result {
        data class Found(val app: App) : Result()
        data class Ambiguous(val options: List<App>) : Result()
        object None : Result()
    }

    /** canonical name -> (spoken variants, known package names, other labels the app may have). */
    private data class Alias(val variants: List<String>, val pkgs: List<String> = emptyList(), val labels: List<String> = emptyList())

    private val ALIASES: Map<String, Alias> = mapOf(
        "whatsapp" to Alias(listOf("whatsapp", "whats app", "watsap", "watsapp", "whatsap", "watsaap", "vatsap", "wattsapp", "whatapp", "व्हाट्सएप", "व्हाट्सऐप", "वॉट्सऐप", "वाट्सएप", "व्हाट्सअप", "वाट्सअप", "व्हाट्सप", "वॉट्सएप", "व्हाट्स ऐप", "व्हाट्स एप"), listOf("com.whatsapp")),
        "whatsapp business" to Alias(listOf("whatsapp business", "business whatsapp", "व्हाट्सएप बिजनेस", "बिजनेस व्हाट्सएप"), listOf("com.whatsapp.w4b")),
        "instagram" to Alias(listOf("instagram", "insta", "instagam", "instagraam", "इंस्टाग्राम", "इंस्टा", "इन्स्टाग्राम", "इंस्टाग्राम"), listOf("com.instagram.android")),
        "youtube" to Alias(listOf("youtube", "you tube", "utube", "यूट्यूब", "यू ट्यूब", "युट्यूब", "यूटूब"), listOf("com.google.android.youtube")),
        "phonepe" to Alias(listOf("phonepe", "phone pe", "phonepay", "phone pay", "fonpe", "फोनपे", "फोन पे", "फ़ोनपे", "फोनपै", "फोन पै"), listOf("com.phonepe.app")),
        "google pay" to Alias(listOf("gpay", "g pay", "google pay", "googlepay", "tez", "जीपे", "जी पे", "गूगल पे", "गूगलपे"), listOf("com.google.android.apps.nbu.paisa.user"), listOf("GPay", "Google Pay")),
        "paytm" to Alias(listOf("paytm", "pay tm", "पेटीएम", "पेटियम"), listOf("net.one97.paytm")),
        "bhim" to Alias(listOf("bhim", "भीम", "bhim upi"), listOf("in.org.npci.upiapp")),
        "facebook" to Alias(listOf("facebook", "fb", "फेसबुक", "फ़ेसबुक"), listOf("com.facebook.katana", "com.facebook.lite")),
        "messenger" to Alias(listOf("messenger", "मैसेंजर"), listOf("com.facebook.orca")),
        "chrome" to Alias(listOf("chrome", "crome", "क्रोम", "गूगल क्रोम"), listOf("com.android.chrome")),
        "maps" to Alias(listOf("maps", "map", "google maps", "मैप", "मैप्स", "गूगल मैप", "नक्शा"), listOf("com.google.android.apps.maps")),
        "gmail" to Alias(listOf("gmail", "g mail", "जीमेल", "ईमेल", "email", "mail"), listOf("com.google.android.gm")),
        "play store" to Alias(listOf("play store", "playstore", "प्ले स्टोर", "प्लेस्टोर"), listOf("com.android.vending"), listOf("Google Play Store", "Play Store")),
        "telegram" to Alias(listOf("telegram", "टेलीग्राम"), listOf("org.telegram.messenger")),
        "snapchat" to Alias(listOf("snapchat", "snap", "स्नैपचैट", "स्नैप"), listOf("com.snapchat.android")),
        "spotify" to Alias(listOf("spotify", "स्पॉटिफाई", "स्पोटिफाई"), listOf("com.spotify.music")),
        "chatgpt" to Alias(listOf("chatgpt", "chat gpt", "चैट जीपीटी", "चैटजीपीटी"), listOf("com.openai.chatgpt")),
        "gemini" to Alias(listOf("gemini", "जेमिनी"), listOf("com.google.android.apps.bard")),
        "camera" to Alias(listOf("camera", "कैमरा", "केमरा"), labels = listOf("Camera")),
        "gallery" to Alias(listOf("gallery", "galary", "photos", "photo", "albums", "गैलरी", "फोटो", "फ़ोटो", "एल्बम", "अल्बम"), labels = listOf("Gallery", "Albums", "Photos")),
        "phone" to Alias(listOf("phone", "dialer", "फोन", "फ़ोन", "डायलर"), labels = listOf("Phone", "Dialer")),
        "contacts" to Alias(listOf("contacts", "contact", "कॉन्टैक्ट", "कांटेक्ट", "कॉन्टेक्ट्स", "कॉन्टैक्ट्स"), labels = listOf("Contacts")),
        "messages" to Alias(listOf("messages", "message", "sms", "मैसेज", "मैसेजेस", "मेसेज"), labels = listOf("Messages", "Messaging")),
        "calculator" to Alias(listOf("calculator", "कैलकुलेटर", "केलकुलेटर"), labels = listOf("Calculator")),
        "clock" to Alias(listOf("clock", "ghadi", "घड़ी", "घडी", "क्लॉक"), labels = listOf("Clock")),
        "calendar" to Alias(listOf("calendar", "कैलेंडर"), labels = listOf("Calendar")),
        "files" to Alias(listOf("files", "file manager", "फाइल", "फाइल्स", "फाइल मैनेजर"), labels = listOf("File Manager", "Files", "My Files")),
        "music" to Alias(listOf("music", "म्यूजिक"), labels = listOf("Music", "i Music")),
        "notes" to Alias(listOf("notes", "नोट्स"), labels = listOf("Notes")),
        "settings" to Alias(listOf("settings", "setting", "सेटिंग", "सेटिंग्स"), labels = listOf("Settings")),
        "amazon" to Alias(listOf("amazon", "अमेजन", "अमेज़न", "एमेजॉन"), listOf("in.amazon.mShop.android.shopping", "com.amazon.mShop.android.shopping")),
        "flipkart" to Alias(listOf("flipkart", "फ्लिपकार्ट"), listOf("com.flipkart.android")),
        "zomato" to Alias(listOf("zomato", "जोमैटो", "ज़ोमैटो"), listOf("com.application.zomato")),
        "swiggy" to Alias(listOf("swiggy", "स्विगी"), listOf("in.swiggy.android")),
        "meesho" to Alias(listOf("meesho", "मीशो"), listOf("com.meesho.supply")),
        "netflix" to Alias(listOf("netflix", "नेटफ्लिक्स"), listOf("com.netflix.mediaclient")),
        "hotstar" to Alias(listOf("hotstar", "jiohotstar", "jio hotstar", "हॉटस्टार", "जिओ हॉटस्टार", "जियो हॉटस्टार"), listOf("in.startv.hotstar")),
        "truecaller" to Alias(listOf("truecaller", "true caller", "ट्रूकॉलर", "ट्रू कॉलर"), listOf("com.truecaller")),
        "twitter" to Alias(listOf("twitter", "x", "ट्विटर"), listOf("com.twitter.android")),
        "sharechat" to Alias(listOf("sharechat", "शेयरचैट", "शेयर चैट"), listOf("in.mohalla.sharechat")),
        "ola" to Alias(listOf("ola", "ओला"), listOf("com.olacabs.customer")),
        "uber" to Alias(listOf("uber", "उबर"), listOf("com.ubercab")),
        "rapido" to Alias(listOf("rapido", "रैपिडो", "रेपिडो"), listOf("com.rapido.passenger")),
        "i manager" to Alias(listOf("i manager", "imanager", "आई मैनेजर"), listOf("com.iqoo.secure")),
        "jio" to Alias(listOf("myjio", "my jio", "jio", "जियो", "जिओ", "माय जियो"), listOf("com.jio.myjio")),
        "weather" to Alias(listOf("weather", "mausam", "मौसम", "वेदर"), labels = listOf("Weather")),
        "recorder" to Alias(listOf("recorder", "voice recorder", "रिकॉर्डर", "रिकॉर्डिंग"), labels = listOf("Recorder", "Voice Recorder")),
        "browser" to Alias(listOf("browser", "ब्राउजर", "ब्राउज़र"), labels = listOf("Browser")),
        "themes" to Alias(listOf("themes", "theme", "थीम"), labels = listOf("Themes", "i Theme")),
        "google" to Alias(listOf("google", "गूगल"), listOf("com.google.android.googlequicksearchbox")),
    )

    private val variantIndex: Map<String, String> by lazy {
        val m = HashMap<String, String>()
        for ((canon, a) in ALIASES) for (v in a.variants + canon) m[norm(v)] = canon
        m
    }

    private val FILLER = setOf("app", "application", "ऐप", "एप", "एप्प", "एप्लीकेशन", "wala", "वाला")

    fun norm(s: String): String = CommandParser.normalize(s).trim()
        .split(" ").filter { it.isNotBlank() && it !in FILLER }.joinToString(" ")

    private fun compact(s: String) = norm(s).replace(" ", "")

    /** Canonical alias for a spoken name, or null. Exact variant first, then phonetic key equality. */
    fun canonical(spoken: String): String? {
        val q = norm(spoken)
        if (q.isEmpty()) return null
        variantIndex[q]?.let { return it }
        variantIndex.entries.firstOrNull { it.key.replace(" ", "") == q.replace(" ", "") }?.let { return it.value }
        val kq = Translit.key(q)
        if (kq.length >= 3) variantIndex.entries.firstOrNull { Translit.key(it.key) == kq }?.let { return it.value }
        return null
    }

    fun score(query: String, label: String): Double {
        val q = norm(query); val l = norm(label)
        if (q.isEmpty() || l.isEmpty()) return 0.0
        if (compact(q) == compact(l)) return 1.0
        var sc = Translit.similarity(q, l)
        val lq = Translit.key(q); val ll = Translit.key(l)
        if (lq.length >= 3 && (ll.startsWith(lq) || l.split(" ").any { Translit.key(it) == lq })) sc = maxOf(sc, 0.9)
        return sc
    }

    fun resolve(spoken: String, apps: List<App>, threshold: Double = 0.62): Result {
        val q = norm(spoken)
        if (q.isEmpty() || apps.isEmpty()) return Result.None
        val canon = canonical(q)
        val alias = canon?.let { ALIASES[it] }
        // 1) known package for the alias
        if (alias != null) {
            for (p in alias.pkgs) apps.firstOrNull { it.pkg == p }?.let { return Result.Found(it) }
        }
        // 2) exact label match (spoken, canonical, or known alternative labels)
        val names = listOfNotNull(q, canon) + (alias?.labels ?: emptyList())
        val exact = apps.filter { a -> names.any { compact(it) == compact(a.label) } }
        if (exact.size == 1) return Result.Found(exact[0])
        if (exact.size > 1) return Result.Ambiguous(exact.take(3))
        // 3) fuzzy
        val scored = apps.map { a -> a to names.maxOf { score(it, a.label) } }
            .filter { it.second >= threshold }
            .sortedByDescending { it.second }
        if (scored.isEmpty()) return Result.None
        val top = scored[0].second
        val close = scored.filter { top - it.second < 0.06 }.map { it.first }.distinctBy { it.pkg }
        return if (close.size == 1) Result.Found(close[0]) else Result.Ambiguous(close.take(3))
    }

    private val ORDINALS = listOf(
        listOf("pehla", "pahla", "pehle", "pahle", "first", "1", "ek", "पहला", "पहले", "पहली", "एक", "फर्स्ट"),
        listOf("doosra", "dusra", "doosre", "dusre", "second", "2", "do", "दूसरा", "दूसरे", "दूसरी", "दो", "सेकंड"),
        listOf("teesra", "tisra", "teesre", "third", "3", "teen", "तीसरा", "तीसरे", "तीसरी", "तीन", "थर्ड")
    )

    /** Picks one of [options] from the user's reply ("doosra", "business wala", "WhatsApp Business"). */
    fun pickChoice(reply: String, options: List<App>): App? {
        val t = " " + norm(reply) + " "
        for ((i, words) in ORDINALS.withIndex()) {
            if (i < options.size && words.any { t.contains(" $it ") }) return options[i]
        }
        val exact = options.firstOrNull { compact(it.label) == compact(reply) }
        if (exact != null) return exact
        val best = options.map { it to score(reply, it.label) }.maxByOrNull { it.second } ?: return null
        // prefer the option whose extra words the user said ("business")
        val words = norm(reply).split(" ").filter { it.length >= 3 }
        val byWord = options.filter { o -> words.any { w -> norm(o.label).split(" ").any { lw -> Translit.similarity(w, lw) >= 0.8 } } }
        if (byWord.size == 1) return byWord[0]
        if (byWord.size > 1) return byWord.maxByOrNull { norm(it.label).split(" ").count { lw -> words.any { w -> Translit.similarity(w, lw) >= 0.8 } } - it.label.length / 100.0 }
        return if (best.second >= 0.6) best.first else null
    }
}
