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
    private lateinit var permList: LinearLayout
    private lateinit var sw: MaterialSwitch
    private val h = Handler(Looper.getMainLooper())
    private val app get() = application as MairaApp

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refreshPerms() }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)
        tvModel = findViewById(R.id.tvModelStatus)
        permList = findViewById(R.id.permList)
        sw = findViewById(R.id.swWake)

        val swBrain = findViewById<MaterialSwitch>(R.id.swBrain)
        swBrain.isChecked = Prefs.brainEnabled(this)
        swBrain.setOnCheckedChangeListener { _, on ->
            Prefs.setBrainEnabled(this, on)
            if (on) Thread { com.maira.assistant.brain.MairaBrainLoader.get(applicationContext) }.start()
            refreshModel()
        }
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
        refreshModel()
    }

    override fun onPause() { super.onPause(); WakeWordService.uiActive = false; WakeWordService.send(this, WakeWordService.ACTION_RESUME) }

    /** Qwen .task files downloaded by v1.0–v1.3 (app-owned). Only deleted when the user taps the button. */
    private fun oldModelFiles(): List<File> =
        listOfNotNull(getExternalFilesDir(null)?.let { File(it, "models") }, File(filesDir, "models"))
            .flatMap { it.listFiles()?.toList() ?: emptyList() }
            .filter { it.isFile && (it.name.endsWith(".task") || it.name.endsWith(".part") || it.name.endsWith(".copy")) }

    // ---------------- brain status
    private fun refreshModel() {
        tvModel.text = if (Prefs.brainEnabled(this))
            "✓ मायरा ब्रेन चालू — मायरा का अपना छोटा मॉडल (लगभग 4 MB) ऐप के अंदर ही है। कोई डाउनलोड या इंटरनेट नहीं चाहिए।\nजो बात मायरा ब्रेन न समझे, वो पुराने नियमों से समझी जाती है।"
        else "मायरा ब्रेन बंद है — अभी सिर्फ़ पुराने नियम वाली कमांड चलेंगी। ऊपर का स्विच चालू कीजिए।"
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
            PermRow("✉️ SMS भेजना", { granted(Manifest.permission.SEND_SMS) }) { permLauncher.launch(arrayOf(Manifest.permission.SEND_SMS)) },
            PermRow("📒 कॉल लॉग (\"missed call batao\")", { granted(Manifest.permission.READ_CALL_LOG) }) { permLauncher.launch(arrayOf(Manifest.permission.READ_CALL_LOG)) },
            PermRow("☎️ कॉल उठाना/काटना (\"call uthao\")", { granted(Manifest.permission.ANSWER_PHONE_CALLS) }) { permLauncher.launch(arrayOf(Manifest.permission.ANSWER_PHONE_CALLS)) },
            PermRow("🔆 सिस्टम सेटिंग बदलना (ब्राइटनेस, ऑटो रोटेट)", { Settings.System.canWrite(this) }) {
                safeStart(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
            }
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

        oldModelFiles().takeIf { it.isNotEmpty() }?.let { files ->
            val mb = files.sumOf { it.length() } / (1024 * 1024)
            val clean = Button(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "🗑️ पुराना Qwen AI मॉडल हटाएँ ($mb MB जगह खाली करें)"; isAllCaps = false
            }
            clean.setOnClickListener {
                AlertDialog.Builder(this).setTitle("पुराना AI मॉडल हटाएँ?")
                    .setMessage("v1.4 से मायरा सिर्फ़ अपने दिमाग (मायरा ब्रेन) से चलती है, इसलिए पुरानी $mb MB की मॉडल फ़ाइल की ज़रूरत नहीं है।")
                    .setPositiveButton("हटाएँ") { _, _ ->
                        files.forEach { try { it.delete() } catch (_: Exception) {} }
                        getSharedPreferences("maira", MODE_PRIVATE).edit().remove("model_path").apply()
                        permList.removeView(clean); toast("पुराना मॉडल हटा दिया")
                    }.setNegativeButton("रहने दें", null).show()
            }
            permList.addView(clean)
        }
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
            val p = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE, Manifest.permission.SEND_SMS,
            Manifest.permission.READ_CALL_LOG, Manifest.permission.ANSWER_PHONE_CALLS)
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
