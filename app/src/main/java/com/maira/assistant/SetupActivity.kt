package com.maira.assistant

import android.Manifest
import android.app.DownloadManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class SetupActivity : AppCompatActivity() {
    private lateinit var tvModel: TextView
    private lateinit var pb: ProgressBar
    private lateinit var btnCancel: Button
    private lateinit var permList: LinearLayout
    private lateinit var sw: MaterialSwitch
    private val h = Handler(Looper.getMainLooper())
    private val app get() = application as MairaApp
    @Volatile private var importing = false

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refreshPerms() }

    private val pickModel = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importModel(uri)
    }

    private val poller = object : Runnable {
        override fun run() { refreshModel(); h.postDelayed(this, 1000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)
        tvModel = findViewById(R.id.tvModelStatus)
        pb = findViewById(R.id.pbModel)
        btnCancel = findViewById(R.id.btnCancelDownload)
        permList = findViewById(R.id.permList)
        sw = findViewById(R.id.swWake)

        findViewById<Button>(R.id.btnDownloadBig).setOnClickListener { confirmDownload(ModelManager.BIG) }
        findViewById<Button>(R.id.btnDownloadSmall).setOnClickListener { confirmDownload(ModelManager.SMALL) }
        findViewById<Button>(R.id.btnPickModel).setOnClickListener {
            try { pickModel.launch(arrayOf("*/*")) } catch (e: Exception) { toast("फ़ाइल चुनने वाला ऐप नहीं मिला") }
        }
        btnCancel.setOnClickListener { ModelManager.cancelDownload(this); refreshModel() }
        findViewById<Button>(R.id.btnOfflineSpeech).setOnClickListener { openOfflineSpeech() }
        findViewById<Button>(R.id.btnTtsData).setOnClickListener { openTtsSettings() }
        findViewById<Button>(R.id.btnTestVoice).setOnClickListener {
            if (!app.tts.hindiAvailable) toast("हिंदी आवाज़ इंस्टॉल नहीं लगती — 'हिंदी आवाज़ (TTS)' बटन दबाइए")
            app.tts.speak("नमस्ते! मैं मायरा हूँ। मैं आपकी हर बात हिंदी में सुनूँगी और जवाब दूँगी।")
        }

        sw.isChecked = Prefs.wakeEnabled(this) && WakeWordService.running
        sw.setOnCheckedChangeListener { _, on ->
            if (on) {
                if (!granted(Manifest.permission.RECORD_AUDIO)) {
                    sw.isChecked = false
                    permLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO)); return@setOnCheckedChangeListener
                }
                if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
                    permLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                }
                Prefs.setWakeEnabled(this, true)
                WakeWordService.start(this)
                toast("ऐप बंद करने के बाद \"मायरा\" बोलकर बुलाइए")
            } else {
                Prefs.setWakeEnabled(this, false)
                WakeWordService.stop(this)
            }
        }
        buildPermRows()
    }

    override fun onResume() {
        super.onResume()
        WakeWordService.uiActive = true
        WakeWordService.send(this, WakeWordService.ACTION_PAUSE)
        refreshPerms()
        h.post(poller)
    }

    override fun onPause() { super.onPause(); h.removeCallbacks(poller); WakeWordService.uiActive = false; WakeWordService.send(this, WakeWordService.ACTION_RESUME) }

    // ---------------- model
    private fun confirmDownload(info: ModelManager.ModelInfo) {
        val mb = info.sizeBytes / (1024 * 1024)
        AlertDialog.Builder(this)
            .setTitle("मॉडल डाउनलोड")
            .setMessage("${info.name}\nसाइज़: लगभग $mb MB\n\nWi-Fi पर डाउनलोड करें। यह सिर्फ़ एक बार होगा, उसके बाद मायरा बिना इंटरनेट के चलेगी।")
            .setPositiveButton("डाउनलोड करें") { _, _ ->
                try { ModelManager.startDownload(this, info); toast("डाउनलोड शुरू — नोटिफिकेशन में भी दिखेगा") }
                catch (e: Exception) { toast("डाउनलोड शुरू नहीं हुआ: ${e.message}") }
                refreshModel()
            }
            .setNegativeButton("रहने दें", null)
            .show()
    }

    private fun refreshModel() {
        if (importing) return
        val st = ModelManager.poll(this)
        if (st != null && st.status != DownloadManager.STATUS_SUCCESSFUL && st.status != DownloadManager.STATUS_FAILED) {
            pb.visibility = View.VISIBLE; btnCancel.visibility = View.VISIBLE
            val pct = if (st.total > 0) (st.done * 1000 / st.total).toInt() else 0
            pb.isIndeterminate = st.total <= 0
            pb.progress = pct
            val doneMb = st.done / (1024 * 1024); val totMb = if (st.total > 0) st.total / (1024 * 1024) else 0
            val s = when (st.status) {
                DownloadManager.STATUS_PAUSED -> "रुका हुआ (इंटरनेट का इंतज़ार)…"
                DownloadManager.STATUS_PENDING -> "शुरू हो रहा है…"
                else -> "डाउनलोड हो रहा है…"
            }
            tvModel.text = "$s $doneMb / $totMb MB (${pct / 10}%)"
            return
        }
        pb.visibility = View.GONE; btnCancel.visibility = View.GONE
        if (st?.status == DownloadManager.STATUS_FAILED) {
            tvModel.text = "डाउनलोड फ़ेल हो गया (कोड ${st.reason})। इंटरनेट और फ़ोन की जगह (storage) जाँचकर फिर कोशिश करें।"
            return
        }
        val path = Prefs.modelPath(this)
        tvModel.text = if (path != null && File(path).exists()) {
            val f = File(path)
            "✓ मॉडल तैयार: ${f.name} (${f.length() / (1024 * 1024)} MB)\nअब मायरा पूरी तरह ऑफ़लाइन चलेगी।"
        } else "अभी कोई मॉडल नहीं है। फ़ोन में 6 GB RAM के लिए 1.5B (बेहतर) या 0.5B (तेज़) चुनें।"
    }

    private fun importModel(uri: Uri) {
        val name = ModelManager.displayName(this, uri)
        if (!name.endsWith(".task", true)) {
            toast("कृपया .task फ़ाइल चुनें (MediaPipe मॉडल)"); return
        }
        importing = true
        pb.visibility = View.VISIBLE; pb.isIndeterminate = false
        tvModel.text = "फ़ाइल कॉपी हो रही है… $name"
        app.appScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    ModelManager.importModel(applicationContext, uri) { done, total ->
                        runOnUiThread {
                            if (total > 0) pb.progress = (done * 1000 / total).toInt()
                            tvModel.text = "कॉपी: ${done / (1024 * 1024)} / ${if (total > 0) total / (1024 * 1024) else 0} MB"
                        }
                    }
                }
                toast("मॉडल सेट हो गया ✓")
            } catch (e: Exception) {
                toast("कॉपी नहीं हो पाई: ${e.message}")
            } finally {
                importing = false
                refreshModel()
            }
        }
    }

    // ---------------- permissions
    private data class PermRow(val title: String, val check: () -> Boolean, val action: () -> Unit)
    private val rows = mutableListOf<Pair<PermRow, Button>>()

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun buildPermRows() {
        val list = mutableListOf(
            PermRow("🎤 माइक (सुनने के लिए)", { granted(Manifest.permission.RECORD_AUDIO) }) { permLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO)) },
            PermRow("👥 कॉन्टैक्ट (नाम से कॉल/मैसेज)", { granted(Manifest.permission.READ_CONTACTS) }) { permLauncher.launch(arrayOf(Manifest.permission.READ_CONTACTS)) },
            PermRow("📞 कॉल करना", { granted(Manifest.permission.CALL_PHONE) }) { permLauncher.launch(arrayOf(Manifest.permission.CALL_PHONE)) },
            PermRow("✉️ SMS भेजना", { granted(Manifest.permission.SEND_SMS) }) { permLauncher.launch(arrayOf(Manifest.permission.SEND_SMS)) }
        )
        if (Build.VERSION.SDK_INT >= 33) list.add(
            PermRow("🔔 नोटिफिकेशन", { granted(Manifest.permission.POST_NOTIFICATIONS) }) { permLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) })
        list.add(PermRow("♿ फ़ोन कंट्रोल (Accessibility) — स्क्रीन पढ़ना/ऐप खोलना/होम/बैक/लॉक/टाइप", { MairaAccessibilityService.isEnabled(this) }) {
            AlertDialog.Builder(this).setTitle("फ़ोन कंट्रोल चालू करें")
                .setMessage("अगली स्क्रीन में 'Installed apps / डाउनलोड की गई ऐप्स' में \"मायरा फ़ोन कंट्रोल\" चुनकर चालू (ON) कीजिए।\n\nVivo पर: Settings → Accessibility (सुलभता) → Installed services।\nअगर 'Restricted setting' लिखा आए: Settings → Apps → Maira → ऊपर ⋮ → 'Allow restricted settings' दबाइए, फिर दोबारा कोशिश करें।")
                .setPositiveButton("खोलें") { _, _ -> safeStart(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                .setNegativeButton("बाद में", null).show()
        })
        list.add(PermRow("🔔 नोटिफिकेशन पढ़ना (\"notification padho\")", { MairaNotificationListener.isEnabled(this) }) {
            AlertDialog.Builder(this).setTitle("नोटिफिकेशन पढ़ने की अनुमति")
                .setMessage("अगली स्क्रीन में \"मायरा नोटिफिकेशन पढ़ना\" / Maira को चालू (Allow) कीजिए। मायरा सिर्फ़ आपके कहने पर नोटिफिकेशन पढ़कर सुनाएगी, कुछ भी फ़ोन से बाहर नहीं जाता।\n\nअगर 'Restricted setting' लिखा आए: Settings → Apps → Maira → ऊपर ⋮ → 'Allow restricted settings', फिर दोबारा कोशिश करें।\n\n(इसके बिना भी फ़ोन कंट्रोल चालू होने पर मायरा उसके बाद आए नए नोटिफिकेशन पढ़ सकती है।)")
                .setPositiveButton("खोलें") { _, _ ->
                    if (!safeStart(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))) safeStart(Intent(Settings.ACTION_SETTINGS))
                }
                .setNegativeButton("बाद में", null).show()
        })
        list.add(PermRow("🔋 बैटरी ऑप्टिमाइज़ेशन बंद (Hey Maira के लिए)", {
            getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        }) {
            val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            if (!safeStart(i)) safeStart(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        })
        list.add(PermRow("🔕 Do Not Disturb (फ़ोन साइलेंट करने के लिए)", {
            getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted
        }) { safeStart(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) })
        list.add(PermRow("⚙️ ऐप की सारी सेटिंग (Vivo: Autostart / Background)", { true }) {
            safeStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        })

        val auto = Button(this).apply { text = "🪄 ऑटो सेटअप (मायरा खुद बताएगी)" }
        auto.setOnClickListener { AutoSetupActivity.start(this, restart = true) }
        permList.addView(auto)
        val upd = Button(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { text = "⬆️ अपडेट चेक करें (अभी: v${BuildConfig.VERSION_NAME}, ${BuildConfig.VERSION_CODE})"; isAllCaps = false }
        upd.setOnClickListener {
            toast("अपडेट देख रही हूँ…")
            app.appScope.launch(Dispatchers.IO) {
                val found = try { Updater.checkBlocking(applicationContext, force = true) } catch (_: Exception) { false }
                withContext(Dispatchers.Main) { toast(if (found) "नया अपडेट मिला — डाउनलोड हो रहा है" else "आप सबसे नए वर्शन पर हैं") }
            }
        }
        permList.addView(upd)
        val bolo = Button(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { isAllCaps = false }
        fun boloText() { bolo.text = (if (Prefs.screenBolo(this)) "✅ " else "⬜ ") + "स्क्रीन बोलो मोड (हर नई स्क्रीन अपने आप बोले)" }
        boloText()
        bolo.setOnClickListener {
            if (!Prefs.screenBolo(this) && !MairaAccessibilityService.isEnabled(this)) { toast("पहले फ़ोन कंट्रोल (Accessibility) चालू कीजिए"); return@setOnClickListener }
            Prefs.setScreenBolo(this, !Prefs.screenBolo(this)); boloText()
        }
        permList.addView(bolo)
        val all = Button(this).apply { text = "सारी ज़रूरी अनुमतियाँ एक साथ दें" }
        all.setOnClickListener {
            val p = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE, Manifest.permission.SEND_SMS)
            if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.POST_NOTIFICATIONS)
            permLauncher.launch(p.toTypedArray())
        }
        permList.addView(all)
        for (r in list) {
            val b = Button(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
            b.isAllCaps = false
            b.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            b.setOnClickListener { r.action() }
            permList.addView(b, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            rows.add(r to b)
        }
    }

    private fun refreshPerms() {
        for ((r, b) in rows) {
            val ok = try { r.check() } catch (_: Exception) { false }
            b.text = (if (ok) "✅ " else "⬜ ") + r.title
        }
    }

    // ---------------- voice helpers
    private fun openOfflineSpeech() {
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                if (SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
                    val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
                    r.triggerModelDownload(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN"))
                    toast("हिंदी ऑफ़लाइन पैक डाउनलोड के लिए भेजा गया")
                }
            } catch (_: Exception) {}
        }
        val tries = listOf(
            Intent().setComponent(ComponentName("com.google.android.googlequicksearchbox", "com.google.android.apps.gsa.settingsui.VoiceSearchPreferences")),
            Intent("com.google.android.voicesearch.action.OFFLINE_LANGUAGE_DOWNLOAD"),
            Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
            Intent(Settings.ACTION_LOCALE_SETTINGS)
        )
        for (i in tries) if (safeStart(i)) {
            toast("Offline speech recognition → हिंदी (भारत) डाउनलोड करें")
            return
        }
        toast("Google ऐप की Voice सेटिंग में जाकर ऑफ़लाइन हिंदी डाउनलोड करें")
    }

    private fun openTtsSettings() {
        if (!safeStart(Intent("com.android.settings.TTS_SETTINGS"))) {
            safeStart(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA))
        }
        toast("Speech Services by Google चुनें → हिंदी (भारत) आवाज़ डाउनलोड करें")
    }

    private fun safeStart(i: Intent): Boolean = try { startActivity(i); true } catch (_: Exception) { false }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
