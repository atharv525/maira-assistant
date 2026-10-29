package com.maira.assistant

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.telephony.SmsManager
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Result of an action: what Maira says, and optionally something to run after Maira's screen is hidden. */
data class ActionResult(
    val say: String,
    val deferred: (() -> Unit)? = null,
    val expectReply: Boolean = false,
    /** Runs after Maira's screen is hidden; its return value is what Maira then says (e.g. screen reading). */
    val deferredSay: (suspend () -> String)? = null
)

data class Contact(val name: String, val number: String)

class PhoneActions(private val ctx: Context) {

    private fun has(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    /**
     * Starts activities through the accessibility service when it is running: Android blocks activity starts from
     * a background service ("Hey Maira"), but apps with a bound AccessibilityService are exempt.
     */
    private fun start(i: Intent): Boolean = try {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        (MairaAccessibilityService.instance ?: ctx).startActivity(i); true
    } catch (e: ActivityNotFoundException) { false } catch (e: SecurityException) { false } catch (e: Exception) { false }

    // ---------------- flashlight
    @Volatile private var torchOn = false
    fun flash(on: Boolean): String {
        return try {
            val cm = ctx.getSystemService(CameraManager::class.java)
            val id = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return "इस फ़ोन में टॉर्च नहीं मिली।"
            cm.setTorchMode(id, on)
            torchOn = on
            if (on) "टॉर्च चालू कर दी।" else "टॉर्च बंद कर दी।"
        } catch (e: Exception) { "टॉर्च नहीं चला पाई, शायद कैमरा कोई और ऐप इस्तेमाल कर रहा है।" }
    }

    // ---------------- volume
    fun volume(dir: String): String {
        val am = ctx.getSystemService(AudioManager::class.java)
        val s = AudioManager.STREAM_MUSIC
        return try {
            when (dir) {
                "up" -> { repeat(2) { am.adjustStreamVolume(s, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI) }; "आवाज़ बढ़ा दी।" }
                "down" -> { repeat(2) { am.adjustStreamVolume(s, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI) }; "आवाज़ कम कर दी।" }
                "mute" -> {
                    am.adjustStreamVolume(s, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                    try { am.ringerMode = AudioManager.RINGER_MODE_VIBRATE } catch (_: Exception) {}
                    "फ़ोन म्यूट कर दिया।"
                }
                "unmute" -> {
                    am.adjustStreamVolume(s, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
                    try { am.ringerMode = AudioManager.RINGER_MODE_NORMAL } catch (_: Exception) {}
                    "आवाज़ वापस चालू कर दी।"
                }
                "max" -> { am.setStreamVolume(s, am.getStreamMaxVolume(s), AudioManager.FLAG_SHOW_UI); "आवाज़ पूरी कर दी।" }
                else -> "ठीक है।"
            }
        } catch (e: SecurityException) { "यह बदलने के लिए 'Do Not Disturb' की अनुमति चाहिए।" }
    }

    // ---------------- media
    fun media(key: String): String {
        val am = ctx.getSystemService(AudioManager::class.java)
        val code = when (key) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "prev" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        }
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return when (key) {
            "play" -> "गाना चला दिया।"
            "pause" -> "गाना रोक दिया।"
            "next" -> "अगला गाना।"
            "prev" -> "पिछला गाना।"
            else -> "ठीक है।"
        }
    }

    fun youtube(q: String): String {
        val yt = Intent(Intent.ACTION_SEARCH).setPackage("com.google.android.youtube").putExtra("query", q)
        if (start(yt)) return "यूट्यूब पर $q चला रही हूँ।"
        val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q)))
        return if (start(web)) "यूट्यूब पर $q खोज रही हूँ।" else "यूट्यूब नहीं खुल पाया।"
    }

    fun webSearch(q: String): String {
        val i = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, q)
        if (start(i)) return "$q खोज रही हूँ।"
        return if (start(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))))) "$q खोज रही हूँ।" else "सर्च नहीं खुल पाया।"
    }

    // ---------------- alarm & timer
    fun alarm(h: Int, m: Int): String {
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, h)
            .putExtra(AlarmClock.EXTRA_MINUTES, m)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "मायरा अलार्म")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        return if (start(i)) "${hindiTime(h, m)} का अलार्म लगा दिया।" else "अलार्म वाला ऐप नहीं मिला।"
    }

    fun timer(sec: Int): String {
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, sec)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "मायरा टाइमर")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        val txt = buildString {
            val h = sec / 3600; val mi = (sec % 3600) / 60; val s = sec % 60
            if (h > 0) append("$h घंटे ")
            if (mi > 0) append("$mi मिनट ")
            if (s > 0) append("$s सेकंड ")
        }.trim()
        return if (start(i)) "$txt का टाइमर शुरू कर दिया।" else "टाइमर वाला ऐप नहीं मिला।"
    }

    // ---------------- settings panels
    fun settings(panel: String): String {
        val (intent, say) = when (panel) {
            "wifi" -> (if (Build.VERSION.SDK_INT >= 29) Intent(Settings.Panel.ACTION_WIFI) else Intent(Settings.ACTION_WIFI_SETTINGS)) to "वाई-फ़ाई की सेटिंग खोल दी, यहाँ से चालू या बंद कीजिए।"
            "bluetooth" -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS) to "ब्लूटूथ की सेटिंग खोल दी।"
            "data" -> (if (Build.VERSION.SDK_INT >= 29) Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY) else Intent(Settings.ACTION_WIRELESS_SETTINGS)) to "इंटरनेट की सेटिंग खोल दी।"
            "hotspot" -> Intent(Settings.ACTION_WIRELESS_SETTINGS) to "हॉटस्पॉट के लिए नेटवर्क सेटिंग खोल दी।"
            "display" -> Intent(Settings.ACTION_DISPLAY_SETTINGS) to "डिस्प्ले और ब्राइटनेस की सेटिंग खोल दी।"
            "location" -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS) to "लोकेशन की सेटिंग खोल दी।"
            "alarm" -> Intent(AlarmClock.ACTION_SHOW_ALARMS) to "अलार्म खोल दिए। समय भी बोलिए, जैसे सुबह 6 बजे का अलार्म लगाओ।"
            "timer" -> Intent(AlarmClock.ACTION_SET_TIMER) to "टाइमर खोल दिया। जैसे बोलिए, 5 मिनट का टाइमर लगाओ।"
            else -> Intent(Settings.ACTION_SETTINGS) to "सेटिंग्स खोल दी।"
        }
        return if (start(intent)) say else if (start(Intent(Settings.ACTION_SETTINGS))) "सेटिंग्स खोल दी।" else "सेटिंग नहीं खुल पाई।"
    }

    // ---------------- battery / time / date
    fun battery(): String {
        val bm = ctx.getSystemService(BatteryManager::class.java)
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val st = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = st?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return "बैटरी $pct प्रतिशत है" + if (charging) ", और फ़ोन चार्ज हो रहा है।" else "।"
    }

    fun hindiTime(h: Int, m: Int): String {
        val part = when (h) { in 4..11 -> "सुबह"; in 12..15 -> "दोपहर"; in 16..19 -> "शाम"; else -> "रात" }
        val h12 = if (h % 12 == 0) 12 else h % 12
        return if (m == 0) "$part $h12 बजे" else "$part $h12 बजकर $m मिनट"
    }

    fun time(): String {
        val c = Calendar.getInstance()
        val h = c.get(Calendar.HOUR_OF_DAY); val m = c.get(Calendar.MINUTE)
        return "अभी ${hindiTime(h, m)} हुए हैं।".replace("बजे हुए", "बजे हैं").replace("बजे हैं हैं", "बजे हैं")
    }

    fun date(): String {
        val f = SimpleDateFormat("EEEE, d MMMM yyyy", Locale("hi", "IN"))
        return "आज ${f.format(Date())} है।"
    }

    // ---------------- camera
    fun camera(selfie: Boolean, video: Boolean): String {
        val i = Intent(if (video) MediaStore.INTENT_ACTION_VIDEO_CAMERA else MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
        if (selfie) {
            i.putExtra("android.intent.extras.CAMERA_FACING", 1)
            i.putExtra("android.intent.extras.LENS_FACING_FRONT", 1)
            i.putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
        }
        return if (start(i)) (if (video) "कैमरा वीडियो मोड में खोल दिया।" else "कैमरा खोल दिया, मुस्कुराइए!") else "कैमरा नहीं खुल पाया।"
    }

    // ---------------- accessibility
    private fun a11y(): MairaAccessibilityService? = MairaAccessibilityService.instance
    private val needA11y = "इसके लिए सेटअप में जाकर 'मायरा फ़ोन कंट्रोल' (Accessibility) चालू कीजिए।"

    fun global(action: String): ActionResult {
        val svc = a11y() ?: return ActionResult(needA11y)
        val code = when (action) {
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "quick" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            "lock" -> if (Build.VERSION.SDK_INT >= 28) AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN else -1
            "screenshot" -> if (Build.VERSION.SDK_INT >= 28) AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT else -1
            else -> -1
        }
        if (code == -1) return ActionResult("यह आपके Android वर्ज़न पर नहीं हो सकता।")
        val say = when (action) {
            "home" -> "होम स्क्रीन पर जा रही हूँ।"
            "back" -> "पीछे जा रही हूँ।"
            "recents" -> "हाल के ऐप दिखा रही हूँ।"
            "notifications" -> "नोटिफिकेशन खोल दिए।"
            "quick" -> "क्विक सेटिंग्स खोल दीं।"
            "lock" -> "फ़ोन लॉक कर रही हूँ।"
            "screenshot" -> "स्क्रीनशॉट ले रही हूँ।"
            else -> "ठीक है।"
        }
        // Actions that should affect the app *behind* Maira run after Maira is hidden.
        val behind = action in setOf("back", "screenshot")
        return if (behind) ActionResult(say, deferred = { svc.global(code) })
        else ActionResult(say, deferred = { svc.global(code) })
    }

    fun type(text: String): ActionResult {
        val svc = a11y() ?: return ActionResult(needA11y)
        if (text.isBlank()) return ActionResult("क्या लिखना है, बताइए।")
        return ActionResult("लिख रही हूँ: $text", deferred = { svc.typeText(text) })
    }

    fun scroll(down: Boolean): ActionResult {
        val svc = a11y() ?: return ActionResult(needA11y)
        return ActionResult(if (down) "नीचे स्क्रॉल कर रही हूँ।" else "ऊपर स्क्रॉल कर रही हूँ।", deferred = { svc.scroll(down) })
    }

    fun click(text: String): ActionResult {
        val svc = a11y() ?: return ActionResult(needA11y)
        return ActionResult("$text पर क्लिक कर रही हूँ।", deferred = { svc.clickText(text) })
    }

    // ---------------- contacts / call / sms
    private val aliasGroups = listOf(
        listOf("mummy", "mumma", "mom", "mommy", "maa", "ma", "mother", "ammi", "mammi", "मम्मी", "मम्मा", "माँ", "मां", "मा", "अम्मी", "मॉम", "माता जी", "माताजी", "आई", "aai"),
        listOf("papa", "pappa", "dad", "daddy", "father", "pitaji", "abbu", "पापा", "पप्पा", "डैड", "डैडी", "पिताजी", "पिता जी", "अब्बू", "बाबा", "baba"),
        listOf("bhai", "bhaiya", "bhaiyya", "brother", "bro", "भाई", "भैया", "भइया", "दादा", "dada"),
        listOf("didi", "sister", "behen", "dii", "दीदी", "दी", "बहन", "ताई", "tai"),
        listOf("nani", "नानी"), listOf("nana", "नाना"), listOf("dadi", "दादी"),
        listOf("chacha", "चाचा"), listOf("chachi", "चाची"), listOf("mama", "मामा"), listOf("mami", "मामी"),
        listOf("bua", "बुआ"), listOf("mausi", "मौसी"), listOf("husband", "pati", "पति", "hubby"), listOf("wife", "patni", "पत्नी")
    )

    private var contactsCache: List<Contact>? = null

    fun contacts(): List<Contact> {
        contactsCache?.let { return it }
        if (!has(Manifest.permission.READ_CONTACTS)) return emptyList()
        val out = ArrayList<Contact>()
        try {
            ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val n = c.getString(0) ?: continue
                    val num = c.getString(1) ?: continue
                    out.add(Contact(n, num))
                }
            }
        } catch (_: Exception) {}
        val res = out.distinctBy { it.name.lowercase() + it.number.filter { ch -> ch.isDigit() }.takeLast(10) }
        contactsCache = res
        return res
    }

    fun findContact(spoken: String): Contact? {
        val q = spoken.trim().lowercase()
        val all = contacts()
        if (all.isEmpty()) return null
        val queries = mutableListOf(q)
        aliasGroups.firstOrNull { g -> g.any { it == q } }?.let { queries.addAll(it) }
        var best: Contact? = null; var bestScore = 0.0
        for (c in all) {
            for (qq in queries) {
                val sc = Translit.similarity(qq, c.name)
                if (sc > bestScore) { bestScore = sc; best = c }
            }
        }
        return if (bestScore >= 0.62) best else null
    }

    private fun numberIn(s: String): String? {
        val d = s.filter { it.isDigit() || it == '+' }
        return if (d.count { it.isDigit() } >= 3) d else null
    }

    fun call(who: String): String {
        val number: String; val name: String
        val direct = numberIn(who)
        if (direct != null) { number = direct; name = direct } else {
            if (!has(Manifest.permission.READ_CONTACTS)) return "कॉन्टैक्ट देखने की अनुमति नहीं है। सेटअप में जाकर अनुमति दीजिए।"
            val c = findContact(who) ?: return "'$who' नाम का कॉन्टैक्ट नहीं मिला।"
            number = c.number; name = c.name
        }
        val uri = Uri.parse("tel:" + Uri.encode(number))
        return if (has(Manifest.permission.CALL_PHONE) && start(Intent(Intent.ACTION_CALL, uri))) "$name को कॉल कर रही हूँ।"
        else if (start(Intent(Intent.ACTION_DIAL, uri))) "$name का नंबर डायल पर खोल दिया, हरा बटन दबाइए।"
        else "कॉल नहीं हो पाई।"
    }

    /** Resolves a contact for SMS. Returns null-contact message on failure. */
    fun resolveSmsTarget(who: String): Pair<Contact?, String?> {
        numberIn(who)?.let { return Contact(it, it) to null }
        if (!has(Manifest.permission.READ_CONTACTS)) return null to "कॉन्टैक्ट देखने की अनुमति नहीं है। सेटअप में जाकर अनुमति दीजिए।"
        val c = findContact(who) ?: return null to "'$who' नाम का कॉन्टैक्ट नहीं मिला।"
        return c to null
    }

    fun sendSms(c: Contact, text: String): String {
        if (!has(Manifest.permission.SEND_SMS)) {
            val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(c.number))).putExtra("sms_body", text)
            return if (start(i)) "मैसेज ऐप में लिख दिया है, भेजने का बटन दबाइए।" else "मैसेज नहीं भेज पाई।"
        }
        return try {
            val sm: SmsManager = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java) else @Suppress("DEPRECATION") SmsManager.getDefault()
            val parts = sm.divideMessage(text)
            if (parts.size > 1) sm.sendMultipartTextMessage(c.number, null, parts, null, null)
            else sm.sendTextMessage(c.number, null, text, null, null)
            "${c.name} को मैसेज भेज दिया।"
        } catch (e: Exception) {
            "मैसेज नहीं भेज पाई: ${e.message ?: ""}"
        }
    }

    // ---------------- open app
    private fun launchableApps(): List<AppMatcher.App> {
        val pm = ctx.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(i, 0).map { AppMatcher.App(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .filter { it.pkg != ctx.packageName }
            .distinctBy { it.pkg }
    }

    fun findApp(spoken: String): AppMatcher.Result = AppMatcher.resolve(spoken, launchableApps())

    fun launch(app: AppMatcher.App): String {
        val i = ctx.packageManager.getLaunchIntentForPackage(app.pkg) ?: return "${app.label} नहीं खुल पाया।"
        return if (start(i)) "${app.label} खोल रही हूँ।" else "${app.label} नहीं खुल पाया।"
    }

    // ---------------- screen & notifications
    /** Captures the screen of the app under Maira (call after Maira has hidden itself). */
    fun captureScreen(): ScreenCapture? = a11y()?.readScreen()

    fun accessibilityOn() = a11y() != null
    val needA11yText get() = needA11y

    fun notifications(): Pair<String, ScreenCapture?> {
        val nl = MairaNotificationListener.instance
        val list = when {
            nl != null -> nl.current()
            a11y() != null -> a11y()!!.recentNotifications()
            else -> return "नोटिफिकेशन पढ़ने के लिए सेटअप में '🔔 नोटिफिकेशन पढ़ना' चालू कीजिए।" to null
        }
        val say = ScreenText.notifications(list)
        val ctxText = list.sortedByDescending { it.time }.take(10).joinToString("\n") { "${it.app}: ${it.title} - ${it.text}" }
        return say to (if (list.isEmpty()) null else ScreenCapture("नोटिफिकेशन", "", ScreenText.cap(ctxText, ScreenText.MAX_CHARS)))
    }

    fun help(): String =
        "मैं ऐप खोल सकती हूँ, स्क्रीन और नोटिफिकेशन पढ़कर सुना सकती हूँ, स्क्रीन का सार बता सकती हूँ, कॉल और मैसेज कर सकती हूँ, टॉर्च, आवाज़, अलार्म, टाइमर, वाई-फ़ाई, बैटरी, समय, फोटो, गाना, होम, बैक, स्क्रीनशॉट और लॉक संभाल सकती हूँ। और कोई भी सवाल पूछिए, मैं हिंदी में जवाब दूँगी।"
}
