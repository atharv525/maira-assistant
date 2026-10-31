package com.maira.assistant

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Keeps Maira alive and up to date with no user action: wake service, model download retry, daily update check. */
object Watchdog {
    private const val PERIODIC = "maira_watchdog"
    private const val KICK = "maira_kick"

    fun schedule(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<WatchdogWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    /** One-off run in [minutes] (e.g. retry a failed download). */
    fun kickSoon(ctx: Context, minutes: Long) {
        val req = OneTimeWorkRequestBuilder<WatchdogWorker>().setInitialDelay(minutes, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(KICK, ExistingWorkPolicy.REPLACE, req)
    }

    /** Wake service should run: user enabled it (default on after Auto Setup) and did not stop it from the notification. */
    fun wakeWanted(ctx: Context) = Prefs.wakeEnabled(ctx) && !Prefs.flag(ctx, "wake_user_stopped") &&
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Best effort: Android 14+ may refuse to start a microphone service from the background; then the next app open starts it. */
    fun ensureWake(ctx: Context): Boolean {
        if (!wakeWanted(ctx) || WakeWordService.running) return WakeWordService.running
        return try { WakeWordService.start(ctx); true } catch (_: Exception) { false }
    }
}

class WatchdogWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val c = applicationContext
        try { Watchdog.ensureWake(c) } catch (_: Exception) {}
        try { Updater.checkBlocking(c) } catch (_: Exception) {}
        return Result.success()
    }
}

/** Phone restarted or Maira was updated: start "Hey Maira" again and re-arm the watchdog. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, "android.intent.action.QUICKBOOT_POWERON", "com.htc.intent.action.QUICKBOOT_POWERON" -> Prefs.setFlag(ctx, "wake_user_stopped", false)
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Updater.clearNotif(ctx)
                if (Prefs.autoSetupDone(ctx)) try { MairaApp.instance.tts.speak("मायरा अपडेट हो गई।") } catch (_: Exception) {}
            }
            else -> return
        }
        try { Watchdog.schedule(ctx) } catch (_: Exception) {}
        Watchdog.ensureWake(ctx)
    }
}
