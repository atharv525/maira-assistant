package com.maira.assistant

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.NotificationManager
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat
import com.maira.assistant.brain.MairaCommand
import com.maira.assistant.brain.MairaUtil
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * v1.4: actions that only Maira Brain produces (the rule parser never had them). Everything the app already
 * could do runs through [AssistantBrain.execute] instead, so behaviour (and the sensitive-screen protections) stays the same.
 * Things Android does not allow a normal app to switch (airplane mode, mobile data, hotspot, location, battery saver,
 * Wi-Fi/Bluetooth on Android 10+/13+) open the right Settings panel and Maira tells the user what to tap.
 */
class BrainActions(private val ctx: Context, private val actions: PhoneActions) {

    private fun has(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    private fun start(i: Intent) = actions.start(i)
    private fun on(c: MairaCommand) = c.str("state") == "on"

    fun run(c: MairaCommand, raw: String): ActionResult = when (c.action) {
        "video_call" -> ActionResult(videoCall(c.str("name")))
        "send_message" -> ActionResult(message(c.str("app"), c.str("contact"), MairaUtil.recoverCase(raw, c.str("text"))))
        "set_reminder" -> ActionResult(reminder(c, MairaUtil.recoverCase(raw, c.str("text"))))
        "make_note" -> ActionResult(note(MairaUtil.recoverCase(raw, c.str("text"))))
        "wifi" -> ActionResult(wifi(on(c)))
        "bluetooth" -> ActionResult(panel(Intent(Settings.ACTION_BLUETOOTH_SETTINGS), "ब्लूटूथ", on(c)))
        "mobile_data" -> ActionResult(panel(
            if (Build.VERSION.SDK_INT >= 29) Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY) else Intent(Settings.ACTION_DATA_ROAMING_SETTINGS),
            "मोबाइल डेटा", on(c), fallback = Intent(Settings.ACTION_WIRELESS_SETTINGS)))
        "hotspot" -> ActionResult(panel(Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings"), "हॉटस्पॉट", on(c),
            fallback = Intent(Settings.ACTION_WIRELESS_SETTINGS)))
        "airplane_mode" -> ActionResult(panel(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS), "फ़्लाइट मोड", on(c)))
        "location" -> ActionResult(panel(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS), "लोकेशन", on(c)))
        "power_saver" -> ActionResult(panel(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS), "बैटरी सेवर", on(c)))
        "auto_rotate" -> ActionResult(autoRotate(on(c)))
        "dnd" -> ActionResult(dnd(on(c)))
        "sound_mode" -> ActionResult(soundMode(c.str("mode")))
        "volume" -> ActionResult(volumeLevel(c.int("level") ?: 50))
        "brightness" -> ActionResult(brightness(c.int("level"), c.str("dir")))
        "play_song" -> ActionResult(spotify(MairaUtil.recoverCase(raw, c.str("query"))))
        "navigate" -> ActionResult(navigate(c.str("place")))
        "open_settings" -> ActionResult(openSettings(c.str("page").ifEmpty { "main" }))
        "open_gallery" -> ActionResult(gallery())
        "close_app" -> closeApp(c.str("app"))
        "read_messages" -> ActionResult(readMessages(c.str("app")))
        "call_log" -> ActionResult(callLog())
        "missed_calls" -> ActionResult(missedCalls())
        "answer_call" -> ActionResult(answerCall())
        "reject_call" -> ActionResult(rejectCall())
        "calculate" -> ActionResult(calculate(c.str("expression")))
        else -> ActionResult("माफ़ कीजिए, यह काम मैं अभी नहीं कर पाई।")
    }

    // ---------------------------------------------------------------- settings that need the user's tap
    private fun panel(i: Intent, name: String, on: Boolean, fallback: Intent? = null): String {
        val ok = start(i) || (fallback != null && start(fallback)) || start(Intent(Settings.ACTION_SETTINGS))
        if (!ok) return "$name की सेटिंग नहीं खुल पाई।"
        return "Android ऐप को $name खुद ${if (on) "चालू" else "बंद"} नहीं करने देता। सेटिंग खोल दी है, $name वाले स्विच पर टैप करके ${if (on) "चालू" else "बंद"} कीजिए।"
    }

    private fun wifi(on: Boolean): String {
        if (Build.VERSION.SDK_INT < 29) {
            try {
                @Suppress("DEPRECATION")
                val ok = ctx.applicationContext.getSystemService(WifiManager::class.java).setWifiEnabled(on)
                if (ok) return if (on) "वाई-फ़ाई चालू कर दिया।" else "वाई-फ़ाई बंद कर दिया।"
            } catch (_: Exception) {}
        }
        return panel(if (Build.VERSION.SDK_INT >= 29) Intent(Settings.Panel.ACTION_WIFI) else Intent(Settings.ACTION_WIFI_SETTINGS), "वाई-फ़ाई", on,
            fallback = Intent(Settings.ACTION_WIFI_SETTINGS))
    }

    private fun askWriteSettings(what: String): String {
        val i = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${ctx.packageName}"))
        return if (start(i)) "$what बदलने के लिए एक बार मायरा को 'सिस्टम सेटिंग बदलने' की अनुमति दीजिए। स्विच ऑन कीजिए, फिर दोबारा बोलिए।"
        else "$what बदलने की अनुमति नहीं मिली।"
    }

    private fun autoRotate(on: Boolean): String {
        if (!Settings.System.canWrite(ctx)) return askWriteSettings("ऑटो रोटेट")
        return try {
            Settings.System.putInt(ctx.contentResolver, Settings.System.ACCELEROMETER_ROTATION, if (on) 1 else 0)
            if (on) "ऑटो रोटेट चालू कर दिया।" else "ऑटो रोटेट बंद कर दिया।"
        } catch (e: Exception) { panel(Intent(Settings.ACTION_DISPLAY_SETTINGS), "ऑटो रोटेट", on) }
    }

    private fun brightness(level: Int?, dir: String): String {
        if (!Settings.System.canWrite(ctx)) return askWriteSettings("ब्राइटनेस")
        return try {
            val cr = ctx.contentResolver
            val cur = try { Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS) } catch (_: Exception) { 128 }
            val target = when {
                level != null -> (level.coerceIn(0, 100) * 255 / 100.0).roundToInt()
                dir == "up" -> cur + 64
                else -> cur - 64
            }.coerceIn(5, 255)
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, target)
            when {
                level != null -> "ब्राइटनेस $level प्रतिशत कर दी।"
                dir == "up" -> "स्क्रीन की रोशनी बढ़ा दी।"
                else -> "स्क्रीन की रोशनी कम कर दी।"
            }
        } catch (e: Exception) { panel(Intent(Settings.ACTION_DISPLAY_SETTINGS), "ब्राइटनेस", dir != "down") }
    }

    private fun dnd(on: Boolean): String {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted) {
            start(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            return "डू नॉट डिस्टर्ब के लिए मायरा को 'Do Not Disturb' की अनुमति चाहिए। सूची में मायरा पर टैप करके Allow कीजिए, फिर दोबारा बोलिए।"
        }
        return try {
            nm.setInterruptionFilter(if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
            if (on) "डू नॉट डिस्टर्ब चालू कर दिया।" else "डू नॉट डिस्टर्ब बंद कर दिया।"
        } catch (e: Exception) { "डू नॉट डिस्टर्ब नहीं बदल पाई।" }
    }

    private fun soundMode(mode: String): String {
        val am = ctx.getSystemService(AudioManager::class.java)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        return try {
            when (mode) {
                "silent" -> {
                    if (!nm.isNotificationPolicyAccessGranted) {
                        am.ringerMode = AudioManager.RINGER_MODE_VIBRATE
                        start(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                        return "अभी फ़ोन वाइब्रेट पर कर दिया। पूरा साइलेंट करने के लिए मायरा को 'Do Not Disturb' की अनुमति दीजिए: सूची में मायरा पर टैप करके Allow कीजिए।"
                    }
                    am.ringerMode = AudioManager.RINGER_MODE_SILENT; "फ़ोन साइलेंट कर दिया।"
                }
                "vibrate" -> { am.ringerMode = AudioManager.RINGER_MODE_VIBRATE; "फ़ोन वाइब्रेट मोड पर कर दिया।" }
                else -> { am.ringerMode = AudioManager.RINGER_MODE_NORMAL; "फ़ोन की घंटी वापस चालू कर दी।" }
            }
        } catch (e: SecurityException) {
            start(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            "यह बदलने के लिए मायरा को 'Do Not Disturb' की अनुमति चाहिए। सूची में मायरा पर टैप करके Allow कीजिए।"
        }
    }

    private fun volumeLevel(level: Int): String {
        val am = ctx.getSystemService(AudioManager::class.java)
        val s = AudioManager.STREAM_MUSIC
        return try {
            val max = am.getStreamMaxVolume(s)
            am.setStreamVolume(s, (max * level.coerceIn(0, 100) / 100.0).roundToInt(), AudioManager.FLAG_SHOW_UI)
            if (level == 0) "आवाज़ बंद कर दी।" else "आवाज़ $level प्रतिशत कर दी।"
        } catch (e: SecurityException) { "यह बदलने के लिए 'Do Not Disturb' की अनुमति चाहिए।" }
    }

    // ---------------------------------------------------------------- calls & messages
    private fun waNumber(number: String): String {
        var d = number.filter { it.isDigit() }
        if (d.startsWith("00")) d = d.drop(2)
        if (d.length == 11 && d.startsWith("0")) d = d.drop(1)
        if (d.length == 10) d = "91$d"
        return d
    }

    private fun whatsappPkg(): String? = listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull {
        try { ctx.packageManager.getPackageInfo(it, 0); true } catch (_: Exception) { false }
    }

    fun videoCall(who: String): String {
        val (c, err) = actions.resolveSmsTarget(who)
        if (c == null) return err ?: "'$who' नाम का कॉन्टैक्ट नहीं मिला।"
        // WhatsApp registers a "video call" data row per contact; opening it starts the video call directly.
        val mime = "vnd.android.cursor.item/vnd.com.whatsapp.video.call"
        if (has(Manifest.permission.READ_CONTACTS)) try {
            val digits = c.number.filter { it.isDigit() }.takeLast(10)
            ctx.contentResolver.query(ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data._ID, ContactsContract.Data.DISPLAY_NAME, ContactsContract.Data.DATA1),
                "${ContactsContract.Data.MIMETYPE}=?", arrayOf(mime), null)?.use { cur ->
                var id: Long? = null
                while (cur.moveToNext()) {
                    val name = cur.getString(1) ?: ""; val data1 = cur.getString(2) ?: ""
                    if ((digits.length >= 6 && data1.filter { it.isDigit() }.endsWith(digits)) || name.equals(c.name, true)) { id = cur.getLong(0); break }
                }
                if (id != null) {
                    val i = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://com.android.contacts/data/$id"), mime)
                        .setPackage(whatsappPkg() ?: "com.whatsapp")
                    if (start(i)) return "${c.name} को व्हाट्सएप वीडियो कॉल कर रही हूँ।"
                }
            }
        } catch (_: Exception) {}
        val pkg = whatsappPkg()
        if (pkg != null && start(Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=" + waNumber(c.number))).setPackage(pkg)))
            return "${c.name} की व्हाट्सएप चैट खोल दी, ऊपर वीडियो कॉल वाले बटन पर टैप कीजिए।"
        return if (pkg == null) "इस फ़ोन में व्हाट्सएप नहीं मिला, इसलिए वीडियो कॉल नहीं हो पाई। सामान्य कॉल के लिए बोलिए: ${c.name} को कॉल करो।"
        else "व्हाट्सएप वीडियो कॉल नहीं हो पाई।"
    }

    private fun message(app: String, who: String, text: String): String {
        when (app) {
            "whatsapp" -> {
                val (c, err) = actions.resolveSmsTarget(who)
                if (c == null) return err ?: "'$who' नाम का कॉन्टैक्ट नहीं मिला।"
                val pkg = whatsappPkg() ?: return "इस फ़ोन में व्हाट्सएप नहीं मिला।"
                val url = "https://api.whatsapp.com/send?phone=" + waNumber(c.number) + (if (text.isNotBlank()) "&text=" + Uri.encode(text) else "")
                if (!start(Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(pkg))) return "व्हाट्सएप नहीं खुल पाया।"
                return if (text.isBlank()) "${c.name} की व्हाट्सएप चैट खोल दी, मैसेज लिखिए।"
                else "${c.name} के लिए व्हाट्सएप पर मैसेज लिख दिया है: \"$text\"। भेजने वाले बटन पर टैप कीजिए।"
            }
            else -> {
                val (pkg, label) = if (app == "telegram") "org.telegram.messenger" to "टेलीग्राम" else "com.instagram.android" to "इंस्टाग्राम"
                if (text.isNotBlank()) {
                    val share = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).setPackage(pkg)
                    if (start(share)) return "$label में मैसेज तैयार है: \"$text\"। $who को चुनकर भेजिए।"
                }
                val launch = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return "इस फ़ोन में $label नहीं मिला।"
                return if (start(launch)) "$label खोल दिया, $who की चैट चुनिए।" else "$label नहीं खुल पाया।"
            }
        }
    }

    private fun callLog(): String {
        val i = Intent(Intent.ACTION_VIEW).setType(CallLog.Calls.CONTENT_TYPE)
        return if (start(i) || start(Intent(Intent.ACTION_DIAL))) "कॉल लॉग खोल दिया।" else "कॉल लॉग नहीं खुल पाया।"
    }

    private fun missedCalls(): String {
        if (!has(Manifest.permission.READ_CALL_LOG)) {
            callLog()
            return "मिस्ड कॉल पढ़ने के लिए कॉल लॉग की अनुमति चाहिए (सेटअप में '📒 कॉल लॉग' दबाइए)। अभी कॉल लॉग खोल दिया है।"
        }
        return try {
            val since = System.currentTimeMillis() - 24 * 3600_000L
            val out = ArrayList<String>()
            var total = 0
            ctx.contentResolver.query(CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.CACHED_NAME, CallLog.Calls.NUMBER, CallLog.Calls.DATE),
                "${CallLog.Calls.TYPE}=? AND ${CallLog.Calls.DATE}>?", arrayOf(CallLog.Calls.MISSED_TYPE.toString(), since.toString()),
                "${CallLog.Calls.DATE} DESC")?.use { cur ->
                while (cur.moveToNext()) {
                    total++
                    if (out.size < 5) {
                        val name = cur.getString(0)?.takeIf { it.isNotBlank() } ?: cur.getString(1) ?: "अनजान नंबर"
                        val cal = Calendar.getInstance().apply { timeInMillis = cur.getLong(2) }
                        out.add("$name, ${actions.hindiTime(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))}")
                    }
                }
            }
            if (total == 0) "पिछले 24 घंटे में कोई मिस्ड कॉल नहीं है।"
            else "पिछले 24 घंटे में $total मिस्ड कॉल हैं: " + out.joinToString("; ") + (if (total > out.size) "; और ${total - out.size} और।" else "।")
        } catch (e: Exception) { callLog(); "मिस्ड कॉल नहीं पढ़ पाई, कॉल लॉग खोल दिया।" }
    }

    private fun answerCall(): String {
        if (!has(Manifest.permission.ANSWER_PHONE_CALLS)) return "कॉल उठाने के लिए मायरा को 'फ़ोन कॉल संभालने' की अनुमति चाहिए (सेटअप में '☎️ कॉल उठाना/काटना')।"
        return try {
            @Suppress("DEPRECATION")
            ctx.getSystemService(TelecomManager::class.java).acceptRingingCall()
            "कॉल उठा रही हूँ।"
        } catch (e: Exception) { "कॉल नहीं उठा पाई।" }
    }

    private fun rejectCall(): String {
        if (Build.VERSION.SDK_INT < 28) return "आपके Android वर्ज़न पर मायरा कॉल नहीं काट सकती।"
        if (!has(Manifest.permission.ANSWER_PHONE_CALLS)) return "कॉल काटने के लिए मायरा को 'फ़ोन कॉल संभालने' की अनुमति चाहिए (सेटअप में '☎️ कॉल उठाना/काटना')।"
        return try {
            @Suppress("DEPRECATION")
            if (ctx.getSystemService(TelecomManager::class.java).endCall()) "कॉल काट दी।" else "अभी कोई कॉल नहीं चल रही।"
        } catch (e: Exception) { "कॉल नहीं काट पाई।" }
    }

    private fun readMessages(app: String): String {
        val nl = MairaNotificationListener.instance
        val a11y = MairaAccessibilityService.instance
        val list = when {
            nl != null -> nl.current()
            a11y != null -> a11y.recentNotifications()
            else -> return "मैसेज पढ़ने के लिए सेटअप में '🔔 नोटिफिकेशन पढ़ना' चालू कीजिए।"
        }
        val (keys, label) = when (app) {
            "whatsapp" -> listOf("whatsapp") to "व्हाट्सएप"
            "telegram" -> listOf("telegram") to "टेलीग्राम"
            "instagram" -> listOf("instagram") to "इंस्टाग्राम"
            "sms" -> listOf("message", "messaging", "sms", "संदेश", "मैसेज") to "SMS"
            else -> listOf("whatsapp", "telegram", "instagram", "message", "messaging", "sms", "संदेश", "मैसेज", "messenger", "signal") to ""
        }
        val mine = list.filter { n -> val a = n.app.lowercase(); keys.any { a.contains(it) } }
        if (mine.isEmpty()) return if (label.isEmpty()) "अभी कोई नया मैसेज नहीं है।" else "$label का कोई नया मैसेज नहीं है।"
        return ScreenText.notifications(mine).replaceFirst(Regex("^आपके (\\d+) नोटिफिकेशन हैं।"), "आपके $1 नए मैसेज हैं।")
    }

    // ---------------------------------------------------------------- reminders & notes
    private fun reminder(c: MairaCommand, text: String): String {
        val time = MairaUtil.clock(c.str("time"))
        if (time != null) {
            val i = Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, time.first).putExtra(AlarmClock.EXTRA_MINUTES, time.second)
                .putExtra(AlarmClock.EXTRA_MESSAGE, "याद: $text").putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            return if (start(i)) "${actions.hindiTime(time.first, time.second)} याद दिला दूँगी: $text" else "रिमाइंडर नहीं लग पाया, घड़ी वाला ऐप नहीं मिला।"
        }
        val sec = MairaUtil.durationSeconds(c.str("duration"))
        val i = Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, sec)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "याद: $text").putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        val h = sec / 3600; val m = (sec % 3600) / 60; val s = sec % 60
        val txt = listOfNotNull(if (h > 0) "$h घंटे" else null, if (m > 0) "$m मिनट" else null, if (s > 0) "$s सेकंड" else null).joinToString(" ")
        return if (start(i)) "$txt बाद याद दिला दूँगी: $text" else "रिमाइंडर नहीं लग पाया, घड़ी वाला ऐप नहीं मिला।"
    }

    private fun note(text: String): String {
        // Always keep a local copy (works offline, no app needed), then hand it to a notes app if one accepts it.
        try {
            val f = SimpleDateFormat("d MMM yyyy, HH:mm", Locale("hi", "IN")).format(Date())
            File(ctx.filesDir, "maira_notes.txt").appendText("[$f] $text\n")
        } catch (_: Exception) {}
        val create = Intent("com.google.android.gms.actions.CREATE_NOTE").setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text).putExtra("com.google.android.gms.actions.extra.NAME", "मायरा नोट")
        if (start(create)) return "नोट बना दिया: $text"
        val keep = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).setPackage("com.google.android.keep")
        if (start(keep)) return "Keep में नोट बना दिया: $text"
        return "नोट मायरा में सेव कर दिया: $text"
    }

    // ---------------------------------------------------------------- media, maps, settings, apps
    private fun spotify(q: String): String {
        val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).setPackage("com.spotify.music")
            .putExtra(SearchManager.QUERY, q).putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
        if (start(i)) return "स्पॉटिफ़ाई पर $q चला रही हूँ।"
        if (start(Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:" + Uri.encode(q))))) return "स्पॉटिफ़ाई पर $q खोज रही हूँ।"
        return actions.youtube(q)
    }

    private fun navigate(place: String): String {
        val nav = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(place))).setPackage("com.google.android.apps.maps")
        if (start(nav)) return "$place का रास्ता दिखा रही हूँ।"
        return if (start(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(place))))) "$place नक्शे पर दिखा रही हूँ।"
        else "नक्शा नहीं खुल पाया।"
    }

    private fun openSettings(page: String): String {
        val (action, name) = when (page) {
            "about" -> Settings.ACTION_DEVICE_INFO_SETTINGS to "फ़ोन की जानकारी"
            "network" -> Settings.ACTION_WIRELESS_SETTINGS to "नेटवर्क"
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS to "लोकेशन"
            "storage" -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS to "स्टोरेज"
            "wifi" -> Settings.ACTION_WIFI_SETTINGS to "वाई-फ़ाई"
            "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS to "एक्सेसिबिलिटी"
            "security" -> Settings.ACTION_SECURITY_SETTINGS to "सुरक्षा"
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS to "ब्लूटूथ"
            "language" -> Settings.ACTION_LOCALE_SETTINGS to "भाषा"
            "date_time" -> Settings.ACTION_DATE_SETTINGS to "तारीख और समय"
            "battery" -> Intent.ACTION_POWER_USAGE_SUMMARY to "बैटरी"
            "apps" -> Settings.ACTION_APPLICATION_SETTINGS to "ऐप्स"
            "notifications" -> "android.settings.NOTIFICATION_SETTINGS" to "नोटिफिकेशन"
            "display" -> Settings.ACTION_DISPLAY_SETTINGS to "डिस्प्ले"
            "sound" -> Settings.ACTION_SOUND_SETTINGS to "साउंड"
            else -> Settings.ACTION_SETTINGS to ""
        }
        if (start(Intent(action))) return if (name.isEmpty()) "सेटिंग्स खोल दी।" else "$name की सेटिंग खोल दी।"
        return if (start(Intent(Settings.ACTION_SETTINGS))) "सेटिंग्स खोल दी।" else "सेटिंग नहीं खुल पाई।"
    }

    private fun gallery(): String {
        (actions.findApp("gallery") as? AppMatcher.Result.Found)?.let { return actions.launch(it.app) }
        val sel = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_GALLERY)
        if (start(sel)) return "गैलरी खोल रही हूँ।"
        return if (start(Intent(Intent.ACTION_VIEW).setType("image/*"))) "गैलरी खोल रही हूँ।" else "गैलरी नहीं खुल पाई।"
    }

    private fun closeApp(id: String): ActionResult {
        val label = when (val r = actions.findApp(BrainRouter.appName(id))) {
            is AppMatcher.Result.Found -> r.app.label
            is AppMatcher.Result.Ambiguous -> r.options.first().label
            else -> BrainRouter.appName(id)
        }
        val svc = MairaAccessibilityService.instance ?: return ActionResult(actions.needA11yText)
        return ActionResult("$label को सामने से हटा दिया। Android किसी ऐप को पूरी तरह बंद नहीं करने देता; पूरा बंद करना हो तो 'recent apps' बोलिए और उसे ऊपर स्वाइप कीजिए।",
            deferred = { svc.global(AccessibilityService.GLOBAL_ACTION_HOME) })
    }

    private fun calculate(expr: String): String {
        val v = MairaUtil.evalExpression(expr) ?: return "यह हिसाब मैं नहीं कर पाई।"
        val spoken = expr.trim().split(Regex("\\s+")).joinToString(" ") {
            when (it) { "+" -> "जमा"; "-" -> "घटा"; "*" -> "गुणा"; "/" -> "भाग"; "^" -> "की घात"; else -> it }
        }
        return "$spoken बराबर ${MairaUtil.formatNumber(v)}।"
    }
}
