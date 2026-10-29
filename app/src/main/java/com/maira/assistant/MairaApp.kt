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
    lateinit var llm: LlmEngine
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
        ModelManager.autoDetect(this)
        tts = TtsManager(this)
        llm = LlmEngine(this)
        brain = AssistantBrain(this)
        try { Watchdog.schedule(this) } catch (_: Exception) {}
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
    fun modelPath(ctx: Context): String? = p(ctx).getString("model_path", null)
    fun setModelPath(ctx: Context, path: String?) = p(ctx).edit().putString("model_path", path).apply()
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
    /** 0 = not asked, 1 = mobile data allowed for the model download, 2 = Wi-Fi only */
    fun meteredChoice(ctx: Context) = int(ctx, "metered")
    fun setMeteredChoice(ctx: Context, v: Int) = setInt(ctx, "metered", v)
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
