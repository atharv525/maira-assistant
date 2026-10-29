package com.maira.assistant

import android.Manifest
import android.app.AppOpsManager
import android.app.DownloadManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * First-launch Auto Setup: one big button per step, Maira speaks every step in Hindi, steps that are already
 * done are skipped, and the wizard moves on by itself as soon as Android reports the permission granted.
 */
class AutoSetupActivity : AppCompatActivity() {
    private data class Step(
        val key: String, val emoji: String, val title: String, val say: String, val button: String,
        val guide: String?, val done: () -> Boolean, val run: () -> Unit, val speakable: Boolean = true
    )

    private val h = Handler(Looper.getMainLooper())
    private val app get() = application as MairaApp
    private lateinit var tvCount: TextView
    private lateinit var tvEmoji: TextView
    private lateinit var tvTitle: TextView
    private lateinit var tvBody: TextView
    private lateinit var btnMain: MaterialButton
    private lateinit var btnRepeat: MaterialButton
    private lateinit var btnSkip: MaterialButton
    private lateinit var tvModel: TextView
    private lateinit var pbModel: ProgressBar

    private var current: Step? = null
    private val autoLaunches = HashMap<String, Int>()
    private var resumed = false
    private var askingMetered = false
    private var finished = false
    /** 0 unknown, 1 installed (or downloading), 2 missing */
    private var speechState = 0

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        Prefs.armPerms(this, 0)
        h.postDelayed({ refresh() }, 300)
    }

    private val ticker = object : Runnable {
        override fun run() {
            refreshModel()
            val c = current
            if (!finished && c != null) {
                if (resumed) {
                    if (c.key == "wait") refresh()
                } else if (c.guide != null && c.key != "perms" && safeDone(c)) {
                    // user finished the step inside Settings: come back by ourselves
                    Prefs.setGuideStep(this@AutoSetupActivity, null)
                    bringToFront(this@AutoSetupActivity)
                }
            }
            h.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        active = true
        buildUi()
        Prefs.setSetupShown(this)
        Prefs.setFlag(this, "auto_model")
        WakeWordService.uiActive = true
        WakeWordService.send(this, WakeWordService.ACTION_PAUSE)
        checkSpeechPack()
        maybeAskMetered()
        app.appScope.launch(Dispatchers.IO) { try { Updater.checkBlocking(applicationContext) } catch (_: Exception) {} }
        h.post(ticker)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        WakeWordService.uiActive = true
        WakeWordService.send(this, WakeWordService.ACTION_PAUSE)
        Prefs.setGuideStep(this, null)
        app.tts.recheck()
        h.postDelayed({ refresh() }, 250)
    }

    override fun onPause() { super.onPause(); resumed = false }

    override fun onDestroy() {
        active = false
        h.removeCallbacksAndMessages(null)
        Prefs.setGuideStep(this, null)
        Prefs.armPerms(this, 0)
        WakeWordService.uiActive = false
        WakeWordService.send(this, WakeWordService.ACTION_RESUME)
        super.onDestroy()
    }

    // ------------------------------------------------------------------ UI
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(28), dp(22), dp(22)); gravity = Gravity.CENTER_HORIZONTAL
        }
        fun tv(size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
            textSize = size; setTextColor(ContextCompat.getColor(context, color)); gravity = Gravity.CENTER
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }
        root.addView(tv(22f, R.color.maira_primary_dark, true).apply { text = "मायरा ऑटो सेटअप" })
        tvCount = tv(15f, R.color.text_muted).also { root.addView(it) }
        tvEmoji = tv(64f, R.color.text_dark).also { root.addView(it, lp(top = 18)) }
        tvTitle = tv(24f, R.color.text_dark, true).also { root.addView(it, lp(top = 8)) }
        tvBody = tv(18f, R.color.text_dark).also { root.addView(it, lp(top = 12)) }
        btnMain = MaterialButton(this).apply {
            textSize = 22f; isAllCaps = false; minHeight = dp(96); cornerRadius = dp(24)
            setOnClickListener { current?.let { launch(it) } }
        }
        root.addView(btnMain, lp(top = 26).apply { height = LinearLayout.LayoutParams.WRAP_CONTENT })
        btnRepeat = MaterialButton(this, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
            text = "🔊 फिर से सुनाओ"; isAllCaps = false; textSize = 16f
            setOnClickListener { current?.let { if (it.speakable) app.tts.speak(it.say) } }
        }
        root.addView(btnRepeat, lp(top = 6))
        btnSkip = MaterialButton(this, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
            text = "यह कदम छोड़ें"; isAllCaps = false; textSize = 14f
            setTextColor(ContextCompat.getColor(context, R.color.text_muted))
            setOnClickListener { current?.let { s -> Prefs.setFlag(this@AutoSetupActivity, "skip_${s.key}"); refresh() } }
        }
        root.addView(btnSkip)
        tvModel = tv(14f, R.color.text_muted).also { root.addView(it, lp(top = 26)) }
        pbModel = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000; visibility = View.GONE }
        root.addView(pbModel, lp(top = 6))
        setContentView(ScrollView(this).apply {
            setBackgroundColor(ContextCompat.getColor(context, R.color.maira_bg)); isFillViewport = true; addView(root)
        })
    }

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }

    // ------------------------------------------------------------------ checks
    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun runtimePerms(): List<String> {
        val p = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE, Manifest.permission.SEND_SMS)
        if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.POST_NOTIFICATIONS)
        return p
    }

    private fun a11yOn() = MairaAccessibilityService.isEnabled(this)

    /** Android 13+ blocks Accessibility/notification access for sideloaded apps until "Allow restricted settings". null = unknown. */
    private fun restrictedBlocked(): Boolean? {
        if (Build.VERSION.SDK_INT < 33) return false
        return try {
            val m = getSystemService(AppOpsManager::class.java).unsafeCheckOpNoThrow("android:access_restricted_settings", applicationInfo.uid, packageName)
            m == AppOpsManager.MODE_ERRORED || m == AppOpsManager.MODE_IGNORED
        } catch (_: Exception) { null }
    }

    private fun needRestricted(): Boolean {
        if (Build.VERSION.SDK_INT < 33 || a11yOn() || !Prefs.flag(this, "a11y_tried")) return false
        val visits = Prefs.int(this, "restricted_visits")
        if (visits >= 3) return false
        return when (restrictedBlocked()) { true -> true; false -> false; null -> visits == 0 }
    }

    private fun isVivo() = Build.MANUFACTURER.lowercase().let { it.contains("vivo") || it.contains("iqoo") } || Build.BRAND.lowercase().let { it.contains("vivo") || it.contains("iqoo") }

    private fun safeDone(s: Step) = try { s.done() } catch (_: Exception) { false }

    // ------------------------------------------------------------------ steps
    private fun steps(): List<Step> {
        val list = mutableListOf<Step>()
        val a11y = a11yOn()
        list += Step("tts", "🗣️", "हिंदी आवाज़",
            "Hindi voice is missing. On the next screen choose Hindi (India) and download it. Then come back.",
            "हिंदी आवाज़ डाउनलोड करें", null,
            { app.tts.hindiAvailable || Prefs.flag(this, "tts_visited") },
            {
                Prefs.setFlag(this, "tts_visited")
                if (!safeStart(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA))) safeStart(Intent("com.android.settings.TTS_SETTINGS"))
            }, speakable = false)
        list += Step("restricted", "🔓", "पहले यह ताला खोलें",
            "Android ने फ़ोन कंट्रोल पर ताला लगाया है। अब ऐप की जानकारी वाली स्क्रीन खुलेगी। ऊपर दाएँ कोने में तीन बिंदु दबाइए, फिर 'Allow restricted settings' दबाइए। फ़ोन का लॉक माँगे तो दे दीजिए, फिर वापस आ जाइए।",
            "ऐप की सेटिंग खोलें", "restricted", { !needRestricted() },
            {
                Prefs.setInt(this, "restricted_visits", Prefs.int(this, "restricted_visits") + 1)
                safeStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            })
        val maybeRestricted = Build.VERSION.SDK_INT >= 33 && restrictedBlocked() != false && Prefs.int(this, "restricted_visits") == 0
        list += Step("a11y", "♿", "फ़ोन कंट्रोल चालू करें",
            if (maybeRestricted) "अब फ़ोन कंट्रोल चालू करना है। अगली स्क्रीन में 'मायरा फ़ोन कंट्रोल' पर टैप कीजिए, स्विच चालू कीजिए और 'Allow' दबाइए। अगर 'Restricted setting' लिखा आए तो बस 'OK' दबाकर वापस आ जाइए, मैं आगे का रास्ता बताऊँगी।"
            else "अब फ़ोन कंट्रोल चालू करना है। अगली स्क्रीन में 'मायरा फ़ोन कंट्रोल' पर टैप कीजिए, स्विच चालू कीजिए और 'Allow' दबाइए। चालू होते ही मैं खुद यहाँ वापस आ जाऊँगी।",
            "फ़ोन कंट्रोल खोलें", "a11y", { a11yOn() },
            { Prefs.setFlag(this, "a11y_tried"); openAccessibility() })
        list += Step("perms", "🎤", "ज़रूरी अनुमतियाँ",
            if (a11y) "अब माइक, कॉन्टैक्ट, कॉल, SMS और नोटिफिकेशन की अनुमति माँगूँगी। 'Allow' मैं खुद दबा दूँगी, आपको कुछ नहीं करना।"
            else "अब माइक, कॉन्टैक्ट, कॉल, SMS और नोटिफिकेशन की अनुमति माँगूँगी। हर बार 'Allow' या 'अनुमति दें' दबाइए।",
            "अनुमतियाँ दें", null,
            { runtimePerms().all { granted(it) } || Prefs.int(this, "perm_tries") >= 3 },
            {
                Prefs.setInt(this, "perm_tries", Prefs.int(this, "perm_tries") + 1)
                if (a11yOn()) Prefs.armPerms(this, 90_000L)
                permLauncher.launch(runtimePerms().filter { !granted(it) }.toTypedArray())
            })
        list += Step("notif", "🔔", "नोटिफिकेशन पढ़ने की अनुमति",
            "नोटिफिकेशन पढ़कर सुनाने के लिए अगली स्क्रीन में 'Maira' को चालू कीजिए और 'Allow' दबाइए। हो जाने पर मैं खुद वापस आ जाऊँगी।",
            "नोटिफिकेशन सेटिंग खोलें", "notif", { MairaNotificationListener.isEnabled(this) }, { openNotifAccess() })
        list += Step("battery", "🔋", "बैटरी की रोक हटाएँ",
            "मैं हमेशा सुन सकूँ, इसके लिए बैटरी की रोक हटानी है। अगली खिड़की में 'Allow' या 'अनुमति दें' दबाइए।",
            "बैटरी की रोक हटाएँ", "battery",
            { getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName) },
            {
                if (!safeStart(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))))
                    safeStart(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            })
        list += Step("unknown", "⬆️", "अपने आप अपडेट",
            "मैं खुद को अपने आप अपडेट कर सकूँ, इसके लिए अगली स्क्रीन में 'Allow from this source' वाला स्विच चालू कीजिए।",
            "अपडेट की अनुमति दें", "unknown",
            { packageManager.canRequestPackageInstalls() },
            {
                if (!safeStart(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))))
                    safeStart(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES))
            })
        if (isVivo()) list += Step("autostart", "🚀", "Vivo: ऑटो-स्टार्ट",
            "Vivo फ़ोन मुझे बंद कर देता है। अगली स्क्रीन में 'Maira' ढूँढकर उसका स्विच चालू कीजिए, फिर वापस आ जाइए।",
            "ऑटो-स्टार्ट खोलें", "autostart", { Prefs.flag(this, "autostart_visited") },
            { Prefs.setFlag(this, "autostart_visited"); openVivoAutostart() })
        list += Step("dnd", "🔕", "साइलेंट करने की अनुमति",
            "'फ़ोन साइलेंट करो' के लिए अगली स्क्रीन में 'Maira' को चालू कीजिए। हो जाने पर मैं खुद वापस आ जाऊँगी।",
            "Do Not Disturb खोलें", "dnd",
            { getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted },
            {
                if (!safeStart(Intent("android.settings.NOTIFICATION_POLICY_ACCESS_DETAIL_SETTINGS", Uri.parse("package:$packageName"))))
                    safeStart(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            })
        list += Step("speech", "🎧", "बिना इंटरनेट हिंदी सुनना",
            "आख़िरी काम: बिना इंटरनेट हिंदी सुनने के लिए अगली स्क्रीन में 'Offline speech recognition' खोलकर 'हिंदी (भारत)' डाउनलोड कीजिए, फिर वापस आ जाइए।",
            "हिंदी ऑफ़लाइन पैक", null,
            { speechState == 1 || Prefs.flag(this, "speech_visited") },
            { Prefs.setFlag(this, "speech_visited"); openOfflineSpeech() })
        return list
    }

    private fun refresh() {
        if (finished || askingMetered || isFinishing) return
        if (!app.tts.ready && !app.tts.initFailed) { showWait(); return }
        val next = steps().firstOrNull { !safeDone(it) && !Prefs.flag(this, "skip_${it.key}") }
        if (next == null) { finishSetup(); return }
        if (next.key == current?.key) { current = next; return }
        show(next)
        val n = autoLaunches[next.key] ?: 0
        val go = {
            if (n < 2 && resumed && current?.key == next.key && !finished) {
                autoLaunches[next.key] = n + 1
                h.postDelayed({ if (resumed && current?.key == next.key) launch(next) }, 700)
            }
        }
        if (next.speakable && app.tts.hindiAvailable) app.tts.speak(next.say) { go() } else h.postDelayed({ go() }, 2500)
    }

    private fun showWait() {
        current = Step("wait", "⏳", "तैयारी हो रही है…", "", "रुकिए…", null, { false }, {})
        tvCount.text = ""; tvEmoji.text = "⏳"; tvTitle.text = "तैयारी हो रही है…"; tvBody.text = ""; btnMain.text = "रुकिए…"
    }

    private fun show(s: Step) {
        current = s
        val all = steps()
        val idx = all.indexOfFirst { it.key == s.key } + 1
        tvCount.text = "कदम $idx / ${all.size}"
        tvEmoji.text = s.emoji
        tvTitle.text = s.title
        tvBody.text = if (s.key == "tts") "हिंदी आवाज़ (Speech Services by Google → हिंदी (भारत)) डाउनलोड कीजिए, फिर वापस आइए।" else s.say
        btnMain.text = s.button
        btnRepeat.visibility = if (s.speakable) View.VISIBLE else View.GONE
    }

    private fun launch(s: Step) {
        if (s.key == "wait") return
        app.tts.stop()
        if (s.guide != null) Prefs.setGuideStep(this, s.guide)
        try { s.run() } catch (_: Exception) {}
    }

    private fun finishSetup() {
        finished = true
        current = null
        Prefs.setAutoSetupDone(this)
        Prefs.setGuideStep(this, null)
        if (granted(Manifest.permission.RECORD_AUDIO)) {
            Prefs.setWakeEnabled(this, true)
            try { WakeWordService.start(this) } catch (_: Exception) {}
        }
        try { Watchdog.schedule(this) } catch (_: Exception) {}
        tvCount.text = ""; tvEmoji.text = "🎉"; tvTitle.text = "सब तैयार है!"
        tvBody.text = "अब ऐप बंद करके बस \"मायरा\" बोलिए, फिर अपना काम बताइए।\nAI दिमाग अपने आप डाउनलोड होता रहेगा और नए अपडेट भी अपने आप आएँगे।"
        btnMain.text = "मायरा शुरू करें"; btnRepeat.visibility = View.GONE; btnSkip.visibility = View.GONE
        btnMain.setOnClickListener { finish() }
        app.tts.speak("सेटअप पूरा हो गया! अब ऐप बंद करके बस 'मायरा' बोलिए, फिर अपना काम बताइए। मेरा AI दिमाग अपने आप डाउनलोड होता रहेगा, और नए अपडेट भी अपने आप आएँगे।") {
            h.postDelayed({ if (!isFinishing) finish() }, 800)
        }
    }

    // ------------------------------------------------------------------ model download
    private fun maybeAskMetered() {
        if (ModelManager.hasModel(this) || Prefs.downloadId(this) >= 0) return
        if (Prefs.meteredChoice(this) == 0 && ModelManager.unmetered(this) == false) {
            askingMetered = true
            val info = ModelManager.defaultFor(this)
            val mb = info.sizeBytes / (1024 * 1024)
            val msg = "आप अभी मोबाइल डेटा पर हैं। मायरा का AI दिमाग लगभग $mb MB का है। अभी मोबाइल डेटा से डाउनलोड करूँ, या Wi-Fi मिलने पर अपने आप?"
            app.tts.speak(msg)
            AlertDialog.Builder(this).setTitle("AI दिमाग डाउनलोड").setMessage(msg).setCancelable(false)
                .setPositiveButton("हाँ, अभी डाउनलोड करो") { _, _ -> Prefs.setMeteredChoice(this, 1); afterMetered() }
                .setNegativeButton("Wi-Fi पर") { _, _ -> Prefs.setMeteredChoice(this, 2); afterMetered() }
                .show()
        } else ModelManager.ensureAuto(this)
    }

    private fun afterMetered() {
        askingMetered = false
        app.tts.stop()
        ModelManager.ensureAuto(this)
        refresh()
    }

    private var lastEnsure = 0L
    private fun refreshModel() {
        val now = System.currentTimeMillis()
        if (now - lastEnsure > 30_000) { lastEnsure = now; if (!askingMetered) ModelManager.ensureAuto(this) }
        val st = ModelManager.poll(this)
        if (st != null && st.status != DownloadManager.STATUS_SUCCESSFUL && st.status != DownloadManager.STATUS_FAILED) {
            pbModel.visibility = View.VISIBLE
            pbModel.isIndeterminate = st.total <= 0
            if (st.total > 0) pbModel.progress = (st.done * 1000 / st.total).toInt()
            val done = st.done / (1024 * 1024); val tot = if (st.total > 0) st.total / (1024 * 1024) else 0
            tvModel.text = when (st.status) {
                DownloadManager.STATUS_PAUSED -> "🧠 AI दिमाग: Wi-Fi/इंटरनेट का इंतज़ार… $done / $tot MB"
                DownloadManager.STATUS_PENDING -> "🧠 AI दिमाग: डाउनलोड शुरू हो रहा है…"
                else -> "🧠 AI दिमाग डाउनलोड हो रहा है: $done / $tot MB"
            }
            return
        }
        pbModel.visibility = View.GONE
        tvModel.text = when {
            ModelManager.hasModel(this) -> "🧠 AI दिमाग तैयार ✓"
            Prefs.int(this, "dl_fails") > 0 -> "🧠 डाउनलोड रुका — थोड़ी देर में अपने आप फिर कोशिश होगी"
            else -> "🧠 AI दिमाग: ${ModelManager.defaultFor(this).name}"
        }
    }

    // ------------------------------------------------------------------ deep links
    private fun safeStart(i: Intent): Boolean = try { startActivity(i); true } catch (_: Exception) { false }

    private fun openAccessibility() {
        val cn = ComponentName(this, MairaAccessibilityService::class.java).flattenToString()
        val details = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").putExtra(Intent.EXTRA_COMPONENT_NAME, cn)
        if (Build.VERSION.SDK_INT >= 30 && safeStart(details)) return
        val args = Bundle().apply { putString(":settings:fragment_args_key", cn) }
        val list = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).putExtra(":settings:fragment_args_key", cn).putExtra(":settings:show_fragment_args", args)
        if (!safeStart(list)) safeStart(Intent(Settings.ACTION_SETTINGS))
    }

    private fun openNotifAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            val i = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, ComponentName(this, MairaNotificationListener::class.java).flattenToString())
            if (safeStart(i)) return
        }
        if (!safeStart(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))) safeStart(Intent(Settings.ACTION_SETTINGS))
    }

    private fun openVivoAutostart() {
        val tries = listOf(
            Intent().setComponent(ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")),
            Intent().setComponent(ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager")),
            Intent().setComponent(ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")),
            Intent().setComponent(ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.SoftPermissionDetailActivity"))
                .putExtra("packagename", packageName),
            Intent().setComponent(ComponentName("com.iqoo.secure", "com.iqoo.secure.safeguard.PurviewTabActivity")),
            Intent().setComponent(ComponentName("com.vivo.abe", "com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity")),
            Intent().setComponent(ComponentName("com.iqoo.powersaving", "com.iqoo.powersaving.PowerSavingManagerActivity")),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        )
        for (i in tries) if (safeStart(i)) return
    }

    private fun checkSpeechPack() {
        if (Build.VERSION.SDK_INT < 33) return
        try {
            if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) return
            val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
            r.checkRecognitionSupport(intent, mainExecutor, object : RecognitionSupportCallback {
                override fun onSupportResult(s: RecognitionSupport) {
                    val hi = { l: List<String> -> l.any { it.lowercase().startsWith("hi") } }
                    speechState = if (hi(s.installedOnDeviceLanguages) || hi(s.pendingOnDeviceLanguages)) 1 else 2
                    if (speechState == 2 && hi(s.supportedOnDeviceLanguages)) {
                        try { r.triggerModelDownload(intent); speechState = 1 } catch (_: Exception) {}
                    }
                    try { r.destroy() } catch (_: Exception) {}
                }
                override fun onError(error: Int) { speechState = 2; try { r.destroy() } catch (_: Exception) {} }
            })
        } catch (_: Exception) {}
    }

    private fun openOfflineSpeech() {
        val tries = listOf(
            Intent().setComponent(ComponentName("com.google.android.googlequicksearchbox", "com.google.android.apps.gsa.settingsui.VoiceSearchPreferences")),
            Intent("com.google.android.voicesearch.action.OFFLINE_LANGUAGE_DOWNLOAD"),
            Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
            Intent(Settings.ACTION_LOCALE_SETTINGS)
        )
        for (i in tries) if (safeStart(i)) return
    }

    companion object {
        @Volatile var active = false

        fun start(ctx: Context, restart: Boolean = false) {
            if (restart) {
                val p = ctx.getSharedPreferences("maira", Context.MODE_PRIVATE)
                val e = p.edit()
                p.all.keys.filter { it.startsWith("f_skip_") || it == "f_tts_visited" || it == "f_speech_visited" || it == "f_autostart_visited" ||
                    it == "i_perm_tries" || it == "i_restricted_visits" }.forEach { e.remove(it) }
                e.apply()
            }
            ctx.startActivity(Intent(ctx, AutoSetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }

        /** Bring the wizard back after the user finished a step in Settings (works when allowed by Android, e.g. Accessibility is on). */
        fun bringToFront(ctx: Context) {
            if (!active) return
            try {
                ctx.startActivity(Intent(ctx, AutoSetupActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            } catch (_: Exception) {}
        }
    }
}
