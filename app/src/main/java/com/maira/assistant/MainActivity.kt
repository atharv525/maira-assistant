package com.maira.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private val msgs = mutableListOf<ChatMsg>()
    private lateinit var adapter: ChatAdapter
    private lateinit var rv: RecyclerView
    private lateinit var tvStatus: TextView
    private lateinit var tvPartial: TextView
    private lateinit var fab: FloatingActionButton
    private lateinit var et: EditText
    private lateinit var speech: SpeechHelper
    private val h = Handler(Looper.getMainLooper())
    private val app get() = application as MairaApp
    private var busy = false

    private val micPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startListening() else toast("माइक की अनुमति के बिना मैं सुन नहीं सकती।")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        rv = findViewById(R.id.rvChat)
        tvStatus = findViewById(R.id.tvStatus)
        tvPartial = findViewById(R.id.tvPartial)
        fab = findViewById(R.id.fabMic)
        et = findViewById(R.id.etInput)
        adapter = ChatAdapter(msgs)
        rv.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        rv.adapter = adapter

        speech = SpeechHelper(this,
            onPartial = { p -> tvPartial.visibility = View.VISIBLE; tvPartial.text = p },
            onFinal = { list ->
                setListeningUi(false)
                val t = list.firstOrNull()?.trim().orEmpty()
                if (t.isNotEmpty()) handleInput(t)
            },
            onError = { code ->
                setListeningUi(false)
                val m = SpeechHelper.errorText(code)
                tvPartial.visibility = View.VISIBLE; tvPartial.text = m
            },
            onReady = { tvPartial.visibility = View.VISIBLE; tvPartial.text = "सुन रही हूँ… बोलिए" }
        )

        fab.setOnClickListener {
            if (speech.listening) { speech.stop(); setListeningUi(false) }
            else { app.tts.stop(); startListening() }
        }
        findViewById<ImageButton>(R.id.btnSend).setOnClickListener { sendTyped() }
        et.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { sendTyped(); true } else false }
        findViewById<ImageButton>(R.id.btnSetup).setOnClickListener { startActivity(Intent(this, SetupActivity::class.java)) }

        addBot("नमस्ते! मैं मायरा हूँ। माइक दबाकर बोलिए — जैसे \"YouTube खोलो\", \"मम्मी को कॉल करो\", \"टॉर्च जलाओ\" या कोई भी सवाल पूछिए।")

        if (!Prefs.autoSetupDone(this)) {
            AutoSetupActivity.start(this)
        } else {
            Prefs.setFlag(this, "auto_model")
            ModelManager.ensureAuto(this)
        }
        // auto-update: check on every launch (and daily from the watchdog)
        app.appScope.launch(kotlinx.coroutines.Dispatchers.IO) { try { Updater.checkBlocking(applicationContext, force = true) } catch (_: Exception) {} }
        if (Prefs.wakeEnabled(this) && !WakeWordService.running &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            try { WakeWordService.start(this) } catch (_: Exception) {}
        }
        handleLaunchIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLaunchIntent(intent)
    }

    private fun handleLaunchIntent(i: Intent?) {
        val a = i?.action ?: return
        if (a == Intent.ACTION_ASSIST || a == Intent.ACTION_VOICE_COMMAND) h.postDelayed({ startListening() }, 400)
    }

    override fun onResume() {
        super.onResume()
        if (Prefs.autoSetupDone(this)) ModelManager.ensureAuto(this)
        WakeWordService.uiActive = true
        WakeWordService.send(this, WakeWordService.ACTION_PAUSE)
        refreshStatus()
        preloadModel()
    }

    override fun onPause() {
        super.onPause()
        speech.cancel(); setListeningUi(false)
        WakeWordService.uiActive = false
        WakeWordService.send(this, WakeWordService.ACTION_RESUME)
    }

    override fun onDestroy() { speech.destroy(); super.onDestroy() }

    private fun preloadModel() {
        if (Prefs.modelPath(this) == null || app.llm.isLoaded || app.llm.loading) return
        lifecycleScope.launch {
            tvStatus.text = "AI दिमाग लोड हो रहा है… (10-30 सेकंड)"
            val ok = app.llm.ensureLoaded()
            tvStatus.text = if (ok) "ऑफ़लाइन तैयार ✓" else "मॉडल लोड नहीं हुआ: ${app.llm.lastError ?: ""}"
        }
    }

    private fun refreshStatus() {
        tvStatus.text = when {
            Prefs.downloadId(this) >= 0 -> "मॉडल डाउनलोड हो रहा है… (सेटअप देखें)"
            Prefs.modelPath(this) == null -> "AI मॉडल नहीं है — ⚙ सेटअप में डाउनलोड करें (फ़ोन कमांड चलेंगी)"
            app.llm.isLoaded -> "ऑफ़लाइन तैयार ✓"
            app.llm.loading -> "AI दिमाग लोड हो रहा है…"
            else -> "तैयार"
        }
    }

    private fun startListening() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micPerm.launch(Manifest.permission.RECORD_AUDIO); return
        }
        if (!speech.available()) {
            addBot("इस फ़ोन में आवाज़ पहचानने की सेवा नहीं मिली। Google ऐप इंस्टॉल / अपडेट कीजिए, या नीचे लिखकर बात कीजिए।")
            return
        }
        if (busy) return
        setListeningUi(true)
        speech.start()
    }

    private fun setListeningUi(on: Boolean) {
        fab.backgroundTintList = ContextCompat.getColorStateList(this, if (on) R.color.maira_primary else R.color.maira_accent)
        fab.animate().scaleX(if (on) 1.15f else 1f).scaleY(if (on) 1.15f else 1f).setDuration(180).start()
        if (!on && tvPartial.text == "सुन रही हूँ… बोलिए") tvPartial.visibility = View.GONE
    }

    private fun sendTyped() {
        val t = et.text.toString().trim()
        if (t.isEmpty()) return
        et.setText("")
        handleInput(t)
    }

    private fun addBot(text: String): Int {
        msgs.add(ChatMsg(text, false)); adapter.notifyItemInserted(msgs.size - 1); rv.scrollToPosition(msgs.size - 1)
        return msgs.size - 1
    }

    private fun addUser(text: String) {
        msgs.add(ChatMsg(text, true)); adapter.notifyItemInserted(msgs.size - 1); rv.scrollToPosition(msgs.size - 1)
    }

    private fun handleInput(text: String) {
        tvPartial.visibility = View.GONE
        addUser(text)
        val pos = addBot("…")
        busy = true
        lifecycleScope.launch {
            val r = app.brain.handle(text) { partial ->
                runOnUiThread {
                    if (pos < msgs.size && partial.isNotBlank()) { msgs[pos].text = partial; adapter.notifyItemChanged(pos); rv.scrollToPosition(msgs.size - 1) }
                }
            }
            busy = false
            if (r.say.isBlank()) {
                msgs.removeAt(pos); adapter.notifyItemRemoved(pos)
            } else {
                msgs[pos].text = r.say; adapter.notifyItemChanged(pos); rv.scrollToPosition(msgs.size - 1)
            }
            refreshStatus()
            val d = r.deferred
            val ds = r.deferredSay
            if (ds != null) {
                // Hide Maira so the app underneath is on screen, then read it and speak the result.
                moveTaskToBack(true)
                delay(900)
                val out = try { ds() } catch (e: Exception) { "माफ़ कीजिए, स्क्रीन नहीं पढ़ पाई।" }
                if (pos < msgs.size) { msgs[pos].text = out; adapter.notifyItemChanged(pos); rv.scrollToPosition(msgs.size - 1) }
                app.tts.speak(out)
            } else if (d != null) {
                app.tts.speak(r.say)
                moveTaskToBack(true)
                delay(800)
                try { d() } catch (_: Exception) {}
            } else if (r.say.isNotBlank()) {
                app.tts.speak(r.say) { if (r.expectReply && !isFinishing) startListening() }
            }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
