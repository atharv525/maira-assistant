package com.maira.assistant

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class MairaApp : Application() {
    lateinit var tts: TtsManager
    lateinit var brain: AssistantBrain
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        instance = this
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_WAKE, "Hey Maira (सुन रही है)", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_UPDATE, "मायरा अपडेट", NotificationManager.IMPORTANCE_HIGH)
        )
        cancelOldModelDownload()
        tts = TtsManager(this)
        brain = AssistantBrain(this)
        try { Watchdog.schedule(this) } catch (_: Exception) {}
        // Maira Brain (own model, ~4 MB in the APK): load in the background so the first command is fast.
        if (Prefs.brainEnabled(this)) Thread { com.maira.assistant.brain.MairaBrainLoader.get(this) }.start()
    }

    /** v1.4: no third-party AI model any more. Stop an unfinished Qwen download left by older versions (finished files are left alone). */
    private fun cancelOldModelDownload() {
        val id = Prefs.downloadId(this)
        if (id < 0) return
        try {
            val dm = getSystemService(android.app.DownloadManager::class.java)
            var done = false
            dm.query(android.app.DownloadManager.Query().setFilterById(id))?.use { c ->
                if (c.moveToFirst()) done = c.getInt(c.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_STATUS)) == android.app.DownloadManager.STATUS_SUCCESSFUL
            }
            if (!done) dm.remove(id)
        } catch (_: Exception) {}
        Prefs.setDownload(this, -1, null)
    }

    companion object {
        lateinit var instance: MairaApp
            private set
        const val CH_WAKE = "maira_wake"
        const val CH_UPDATE = "maira_update"
    }
}

object Prefs {
    private fun p(ctx: Context) = ctx.getSharedPreferences("maira", Context.MODE_PRIVATE)
    /** "Apna Brain (Maira Brain)" switch in Setup; default ON. */
    fun brainEnabled(ctx: Context) = p(ctx).getBoolean("brain", true)
    fun setBrainEnabled(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("brain", v).apply()
    fun wakeEnabled(ctx: Context) = p(ctx).getBoolean("wake", false)
    fun setWakeEnabled(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("wake", v).apply()
    fun downloadId(ctx: Context) = p(ctx).getLong("dl_id", -1L)
    fun downloadFile(ctx: Context) = p(ctx).getString("dl_file", null)
    fun setDownload(ctx: Context, id: Long, file: String?) =
        p(ctx).edit().putLong("dl_id", id).putString("dl_file", file).apply()
    fun setupShown(ctx: Context) = p(ctx).getBoolean("setup_shown", false)
    fun setSetupShown(ctx: Context) = p(ctx).edit().putBoolean("setup_shown", true).apply()

    // ---- v1.2 automation
    fun autoSetupDone(ctx: Context) = p(ctx).getBoolean("auto_setup_done", false)
    fun setAutoSetupDone(ctx: Context) = p(ctx).edit().putBoolean("auto_setup_done", true).apply()
    fun flag(ctx: Context, k: String) = p(ctx).getBoolean("f_$k", false)
    fun setFlag(ctx: Context, k: String, v: Boolean = true) = p(ctx).edit().putBoolean("f_$k", v).apply()
    fun long(ctx: Context, k: String, def: Long = 0L) = p(ctx).getLong("l_$k", def)
    fun setLong(ctx: Context, k: String, v: Long) = p(ctx).edit().putLong("l_$k", v).apply()
    fun int(ctx: Context, k: String, def: Int = 0) = p(ctx).getInt("i_$k", def)
    fun setInt(ctx: Context, k: String, v: Int) = p(ctx).edit().putInt("i_$k", v).apply()
    fun screenBolo(ctx: Context) = flag(ctx, "screen_bolo")
    fun setScreenBolo(ctx: Context, v: Boolean) = setFlag(ctx, "screen_bolo", v)
    fun updateArmed(ctx: Context) = long(ctx, "update_armed") > System.currentTimeMillis()
    fun armUpdate(ctx: Context, ms: Long) = setLong(ctx, "update_armed", System.currentTimeMillis() + ms)
    fun permArmed(ctx: Context) = long(ctx, "perm_armed") > System.currentTimeMillis()
    fun armPerms(ctx: Context, ms: Long) = setLong(ctx, "perm_armed", System.currentTimeMillis() + ms)
    /** Setup step the accessibility guide is talking about, while Auto Setup has sent the user to a settings screen. */
    fun guideStep(ctx: Context): String? = if (long(ctx, "guide_until") > System.currentTimeMillis()) p(ctx).getString("guide_step", null) else null
    fun setGuideStep(ctx: Context, step: String?, ms: Long = 4 * 60_000L) =
        p(ctx).edit().putString("guide_step", step).putLong("l_guide_until", if (step == null) 0L else System.currentTimeMillis() + ms).apply()
}
