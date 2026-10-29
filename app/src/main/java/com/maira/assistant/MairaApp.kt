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
        ModelManager.autoDetect(this)
        tts = TtsManager(this)
        llm = LlmEngine(this)
        brain = AssistantBrain(this)
    }

    companion object {
        lateinit var instance: MairaApp
            private set
        const val CH_WAKE = "maira_wake"
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
}
