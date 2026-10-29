package com.maira.assistant

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

/** "Hey Maira": keeps looping the speech recognizer and reacts when it hears "maira / मायरा". */
class WakeWordService : Service() {
    private enum class State { WAKE, COMMAND, BUSY, PAUSED }
    private var state = State.WAKE
    private val h = Handler(Looper.getMainLooper())
    private lateinit var speech: SpeechHelper
    private val app get() = application as MairaApp
    private var errorStreak = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        speech = SpeechHelper(this, onPartial = {}, onFinal = ::onFinal, onError = ::onErr)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_PAUSE -> { state = State.PAUSED; speech.cancel(); h.removeCallbacksAndMessages(null); updateNotif("रुकी हुई (ऐप खुला है)"); return START_STICKY }
            ACTION_RESUME -> { if (state != State.BUSY) { state = State.WAKE; updateNotif(null); listenSoon(600) }; return START_STICKY }
        }
        try {
            val n = buildNotif(null)
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(NOTIF_ID, n)
        } catch (e: Exception) {
            stopSelf(); return START_NOT_STICKY
        }
        if (uiActive) { state = State.PAUSED; updateNotif("रुकी हुई (ऐप खुला है)") }
        else if (state != State.PAUSED) { state = State.WAKE; listenSoon(300) }
        return START_STICKY
    }

    private fun buildNotif(extra: String?): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, WakeWordService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, MairaApp.CH_WAKE)
            .setSmallIcon(R.drawable.ic_stat_maira)
            .setContentTitle("मायरा सुन रही है")
            .setContentText(extra ?: "\"मायरा\" बोलिए, फिर अपना काम बताइए")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "बंद करें", stop)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotif(extra: String?) {
        try { getSystemService(android.app.NotificationManager::class.java).notify(NOTIF_ID, buildNotif(extra)) } catch (_: Exception) {}
    }

    private fun listenSoon(delay: Long) {
        h.removeCallbacksAndMessages(null)
        h.postDelayed({
            if (uiActive || state == State.PAUSED || state == State.BUSY) return@postDelayed
            if (app.tts.isSpeaking()) { listenSoon(400); return@postDelayed }
            speech.start(longListen = state == State.COMMAND)
        }, delay)
    }

    private fun onErr(code: Int) {
        if (state == State.PAUSED || state == State.BUSY) return
        when (code) {
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> { stopSelf(); return }
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT -> { speech.destroy(); errorStreak++ }
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> errorStreak = 0
            else -> errorStreak++
        }
        if (state == State.COMMAND) state = State.WAKE
        val backoff = if (errorStreak > 5) 5000L else if (errorStreak > 0) 1000L else 250L
        listenSoon(backoff)
    }

    private fun onFinal(results: List<String>) {
        errorStreak = 0
        if (state == State.PAUSED || state == State.BUSY) return
        if (state == State.COMMAND) {
            val t = results.firstOrNull()?.trim().orEmpty()
            if (t.isEmpty()) { state = State.WAKE; listenSoon(250); return }
            process(t); return
        }
        // WAKE state: look for the wake word in any alternative
        for (r in results) {
            val idx = wakeIndex(r)
            if (idx != null) {
                val rest = r.substring(idx).trim().trimStart(',', '.', '!', ' ')
                if (rest.length >= 2) process(rest)
                else {
                    state = State.BUSY
                    app.tts.speak("जी, बोलिए?") { state = State.COMMAND; listenSoon(100) }
                }
                return
            }
        }
        listenSoon(150)
    }

    private fun process(text: String) {
        state = State.BUSY
        updateNotif("सुना: $text")
        app.appScope.launch {
            val r = try { app.brain.handle(text) } catch (e: Exception) { ActionResult("माफ़ कीजिए, कुछ गड़बड़ हो गई।") }
            r.deferred?.let { d -> h.postDelayed({ try { d() } catch (_: Exception) {} }, 200) }
            app.tts.speak(r.say) {
                updateNotif(null)
                if (state == State.PAUSED) return@speak
                state = if (r.expectReply) State.COMMAND else State.WAKE
                listenSoon(200)
            }
        }
    }

    override fun onDestroy() {
        running = false
        h.removeCallbacksAndMessages(null)
        speech.destroy()
        super.onDestroy()
    }

    companion object {
        const val NOTIF_ID = 42
        const val ACTION_STOP = "maira.STOP"
        const val ACTION_PAUSE = "maira.PAUSE"
        const val ACTION_RESUME = "maira.RESUME"
        @Volatile var running = false
        @Volatile var uiActive = false

        private val WAKE = listOf("मायरा", "माइरा", "मैरा", "मयरा", "माईरा", "मायरा", "maira", "myra", "mayra", "maaira", "mayura", "maera")

        /** Returns index just after the wake word, or null. */
        fun wakeIndex(text: String): Int? {
            val t = text.lowercase()
            for (w in WAKE) {
                val i = t.indexOf(w)
                if (i >= 0) return i + w.length
            }
            return null
        }

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, WakeWordService::class.java))
        }
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, WakeWordService::class.java)) }
        fun send(ctx: Context, action: String) {
            if (!running) return
            try { ctx.startService(Intent(ctx, WakeWordService::class.java).setAction(action)) } catch (_: Exception) {}
        }
    }
}
