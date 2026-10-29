package com.maira.assistant

import android.app.DownloadManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Auto-update from the GitHub release tagged "latest".
 * CI writes "versionCode=<n>" into the release body and a version.txt asset; each CI build has a higher versionCode.
 */
object Updater {
    const val API = "https://api.github.com/repos/atharv525/maira-assistant/releases/tags/latest"
    private const val NOTIF_ID = 77
    private const val DAY = 24 * 60 * 60_000L

    private fun httpGet(url: String): String? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 20000
        c.instanceFollowRedirects = true
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "Maira-Android")
        if (c.responseCode in 200..299) c.inputStream.bufferedReader().use { it.readText() } else null
    } catch (_: Exception) { null }

    fun localCode(ctx: Context): Long = try {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
    } catch (_: Exception) { BuildConfig.VERSION_CODE.toLong() }

    /** Blocking (call off the main thread). [force] ignores the once-a-day limit. Returns true if a newer build was found. */
    fun checkBlocking(ctx: Context, force: Boolean = false): Boolean {
        val now = System.currentTimeMillis()
        if (!force && now - Prefs.long(ctx, "upd_checked") < DAY - 60 * 60_000L) return false
        val json = httpGet(API) ?: return false
        Prefs.setLong(ctx, "upd_checked", now)
        val r = UpdateInfo.parseRelease(json) ?: return false
        var code = r.versionCode
        if (code < 0 && r.versionTxtUrl != null) code = httpGet(r.versionTxtUrl)?.let { UpdateInfo.parseVersionTxt(it).first } ?: -1
        val apk = r.apkUrl ?: return false
        if (!UpdateInfo.isNewer(code, localCode(ctx).toInt())) return false
        // already downloading / installing this one?
        if (Prefs.int(ctx, "upd_code") == code && Prefs.long(ctx, "upd_dl", -1) >= 0) return true
        speak("नया अपडेट आया है। मैं इसे डाउनलोड करके अपने आप इंस्टॉल कर दूँगी।")
        download(ctx, apk, code)
        return true
    }

    private fun speak(s: String) = try { android.os.Handler(android.os.Looper.getMainLooper()).post { MairaApp.instance.tts.speak(s) } } catch (_: Exception) {}

    private fun updatesDir(ctx: Context) = (ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: File(ctx.filesDir, "dl")).apply { mkdirs() }

    private fun download(ctx: Context, url: String, code: Int) {
        val dm = ctx.getSystemService(DownloadManager::class.java)
        val old = Prefs.long(ctx, "upd_dl", -1)
        if (old >= 0) try { dm.remove(old) } catch (_: Exception) {}
        updatesDir(ctx).listFiles()?.filter { it.name.startsWith("Maira-update") }?.forEach { it.delete() }
        val req = DownloadManager.Request(Uri.parse(url))
            .setTitle("मायरा अपडेट")
            .setDescription("नया वर्शन डाउनलोड हो रहा है")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(ctx, Environment.DIRECTORY_DOWNLOADS, "Maira-update-$code.apk")
            .setMimeType("application/vnd.android.package-archive")
        val id = dm.enqueue(req)
        Prefs.setLong(ctx, "upd_dl", id); Prefs.setInt(ctx, "upd_code", code)
    }

    /** Called from the DOWNLOAD_COMPLETE broadcast. */
    fun onDownloadComplete(ctx: Context, id: Long) {
        if (id < 0 || id != Prefs.long(ctx, "upd_dl", -1)) return
        Prefs.setLong(ctx, "upd_dl", -1)
        val dm = ctx.getSystemService(DownloadManager::class.java)
        var ok = false
        dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
            if (c.moveToFirst()) ok = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) == DownloadManager.STATUS_SUCCESSFUL
        }
        val f = File(updatesDir(ctx), "Maira-update-${Prefs.int(ctx, "upd_code")}.apk")
        if (!ok || !f.exists()) { Prefs.setLong(ctx, "upd_checked", 0); return }   // retry on next check
        install(ctx, f)
    }

    private fun certs(pi: PackageInfo?): Set<String>? {
        if (pi == null) return null
        val sigs = if (Build.VERSION.SDK_INT >= 28) pi.signingInfo?.apkContentsSigners else @Suppress("DEPRECATION") pi.signatures
        if (sigs.isNullOrEmpty()) return null
        return sigs.map { s -> MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
    }

    @Suppress("DEPRECATION")
    private fun archiveInfo(ctx: Context, f: File): PackageInfo? {
        val pm = ctx.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        var pi = pm.getPackageArchiveInfo(f.absolutePath, flags)
        if (pi != null && certs(pi) == null) pi = pm.getPackageArchiveInfo(f.absolutePath, PackageManager.GET_SIGNATURES) ?: pi
        return pi
    }

    @Suppress("DEPRECATION")
    fun install(ctx: Context, f: File) {
        val pm = ctx.packageManager
        val arch = archiveInfo(ctx, f)
        if (arch == null || arch.packageName != ctx.packageName) { f.delete(); return }
        val mine = certs(pm.getPackageInfo(ctx.packageName, if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES))
        val theirs = certs(arch)
        if (mine != null && theirs != null && mine.intersect(theirs).isEmpty()) {
            notify(ctx, "अपडेट दूसरी चाबी (signature) से बना है", "पुराना मायरा हटाकर नया Maira.apk एक बार हाथ से इंस्टॉल करना होगा।", null)
            return
        }
        if (Build.VERSION.SDK_INT >= 26 && !pm.canRequestPackageInstalls()) {
            val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            notify(ctx, "मायरा का नया अपडेट तैयार है", "अपडेट इंस्टॉल करने की अनुमति दीजिए: 'Allow from this source' चालू करें।", i)
            speak("अपडेट तैयार है। इसे इंस्टॉल करने के लिए एक बार 'Allow from this source' चालू कर दीजिए।")
            return
        }
        Prefs.armUpdate(ctx, 10 * 60_000L)
        try {
            val pi = pm.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(ctx.packageName)
            if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            val sid = pi.createSession(params)
            pi.openSession(sid).use { s ->
                s.openWrite("Maira.apk", 0, f.length()).use { out -> f.inputStream().use { it.copyTo(out) }; s.fsync(out) }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val pending = PendingIntent.getBroadcast(ctx, sid, Intent(ctx, InstallResultReceiver::class.java).setPackage(ctx.packageName), flags)
                s.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            // Fallback: classic installer screen
            try {
                val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
                val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                try { ctx.startActivity(i) } catch (_: Exception) {}
                notify(ctx, "मायरा का नया अपडेट तैयार है", "इंस्टॉल करने के लिए टैप करें", i)
            } catch (_: Exception) {}
        }
    }

    fun notify(ctx: Context, title: String, text: String, intent: Intent?) {
        try {
            val b = NotificationCompat.Builder(ctx, MairaApp.CH_UPDATE)
                .setSmallIcon(R.drawable.ic_stat_maira).setContentTitle(title).setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
            if (intent != null) b.setContentIntent(PendingIntent.getActivity(ctx, 5, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            ctx.getSystemService(NotificationManager::class.java).notify(NOTIF_ID, b.build())
        } catch (_: Exception) {}
    }

    fun clearNotif(ctx: Context) = try { ctx.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID) } catch (_: Exception) {}
}

/** Result of the PackageInstaller session. If Android wants a confirmation, show it (the accessibility service may tap "Update"). */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                Prefs.armUpdate(ctx, 10 * 60_000L)
                try { ctx.startActivity(confirm) } catch (_: Exception) {}
                Updater.notify(ctx, "मायरा का नया अपडेट तैयार है", "इंस्टॉल करने के लिए टैप करें", confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> { Prefs.armUpdate(ctx, 0); Updater.clearNotif(ctx) }
            else -> {
                Prefs.armUpdate(ctx, 0)
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
                Prefs.setLong(ctx, "upd_checked", 0)
                Updater.notify(ctx, "मायरा अपडेट नहीं हो पाया", "बाद में फिर कोशिश होगी। $msg", null)
            }
        }
    }
}

/** DownloadManager finished something: the AI model or an app update. */
class DownloadDoneReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
        if (id < 0) return
        if (id == Prefs.downloadId(ctx)) {
            val st = ModelManager.poll(ctx)
            if (st?.status == DownloadManager.STATUS_SUCCESSFUL) {
                try { MairaApp.instance.tts.speak("मायरा का AI दिमाग डाउनलोड हो गया। अब मैं बिना इंटरनेट के आपके सवालों के जवाब दे सकती हूँ।") } catch (_: Exception) {}
            } else if (st?.status == DownloadManager.STATUS_FAILED) Watchdog.kickSoon(ctx, 2)
            return
        }
        if (id == Prefs.long(ctx, "upd_dl", -1)) {
            val pr = goAsync()
            Thread { try { Updater.onDownloadComplete(ctx.applicationContext, id) } finally { pr.finish() } }.start()
        }
    }
}
