package com.maira.assistant

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class TtsManager(ctx: Context) : TextToSpeech.OnInitListener {
    private val main = Handler(Looper.getMainLooper())
    private val tts = TextToSpeech(ctx.applicationContext, this)
    @Volatile var ready = false; private set
    @Volatile var hindiAvailable = false; private set
    private val pending = mutableListOf<Pair<String, (() -> Unit)?>>()
    private val callbacks = ConcurrentHashMap<String, () -> Unit>()

    @Volatile var initFailed = false; private set
    /** A Hindi voice that works without internet is installed. */
    @Volatile var hindiOffline = false; private set

    /** Re-checks the Hindi voice (e.g. after the user installed voice data). */
    fun recheck() { if (ready) try { applyHindi() } catch (_: Exception) {} }

    private fun applyHindi() {
        val hi = Locale("hi", "IN")
        val r = tts.setLanguage(hi)
        hindiAvailable = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
        hindiOffline = try { tts.voices?.any { it.locale.language == "hi" && !it.isNetworkConnectionRequired && !it.features.contains("notInstalled") } == true } catch (_: Exception) { hindiAvailable }
        try {
            // Prefer an offline (local) Hindi voice
            val voices = tts.voices?.filter { it.locale.language == "hi" && !it.isNetworkConnectionRequired }
            val cur = tts.voice
            if (voices != null && voices.isNotEmpty() && (cur == null || cur.isNetworkConnectionRequired || cur.locale.language != "hi")) {
                tts.voice = voices.firstOrNull { it.name.contains("female", true) } ?: voices.first()
            }
        } catch (_: Exception) { }
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) { initFailed = true; return }
        applyHindi()
        tts.setSpeechRate(1.0f)
        tts.setPitch(1.08f)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { fire(utteranceId) }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { fire(utteranceId) }
            override fun onError(utteranceId: String?, errorCode: Int) { fire(utteranceId) }
        })
        ready = true
        val copy = synchronized(pending) { pending.toList().also { pending.clear() } }
        copy.forEach { speak(it.first, it.second) }
    }

    private fun fire(id: String?) {
        if (id == null) return
        callbacks.remove(id)?.let { main.post(it) }
    }

    fun speak(text: String, onDone: (() -> Unit)? = null) {
        val clean = text.replace(Regex("[*#_`>\\[\\]{}]"), " ")
            .replace(Regex("[\\x{1F300}-\\x{1FAFF}\\x{2600}-\\x{27BF}]"), "")
            .trim()
        if (!ready) { synchronized(pending) { pending.add(clean to onDone) }; return }
        if (clean.isEmpty()) { onDone?.let { main.post(it) }; return }
        val id = UUID.randomUUID().toString()
        if (onDone != null) callbacks[id] = onDone
        val res = tts.speak(clean, TextToSpeech.QUEUE_FLUSH, Bundle(), id)
        if (res != TextToSpeech.SUCCESS) fire(id)
    }

    fun stop() {
        tts.stop()
        val ids = callbacks.keys.toList()
        ids.forEach { callbacks.remove(it) }
    }

    fun isSpeaking() = ready && tts.isSpeaking
}
