package com.maira.assistant

import android.app.ActivityManager
import android.app.DownloadManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

object ModelManager {
    data class ModelInfo(val name: String, val url: String, val file: String, val sizeBytes: Long)

    val BIG = ModelInfo(
        "Qwen2.5-1.5B-Instruct (q8, ekv1280)",
        "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
        "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
        1_597_913_616L
    )
    val SMALL = ModelInfo(
        "Qwen2.5-0.5B-Instruct (q8, ekv1280)",
        "https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct/resolve/main/Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
        "Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
        546_660_344L
    )

    fun modelsDir(ctx: Context): File =
        File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "models").apply { mkdirs() }

    /** If no model is selected but a finished .task file exists, select it. */
    fun autoDetect(ctx: Context) {
        val cur = Prefs.modelPath(ctx)
        if (cur != null && File(cur).exists()) return
        val dirs = listOf(modelsDir(ctx), File(ctx.filesDir, "models"))
        val f = dirs.flatMap { it.listFiles()?.toList() ?: emptyList() }
            .filter { it.isFile && it.name.endsWith(".task") && it.length() > 50_000_000 }
            .maxByOrNull { it.length() }
        Prefs.setModelPath(ctx, f?.absolutePath)
    }

    /** 0.5B if the phone has less than 5 GB RAM, else 1.5B. */
    fun pickDefault(totalRamBytes: Long): ModelInfo = if (totalRamBytes in 1 until 5_000_000_000L) SMALL else BIG

    fun totalRam(ctx: Context): Long = try {
        val mi = ActivityManager.MemoryInfo(); ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(mi); mi.totalMem
    } catch (_: Exception) { 0L }

    fun defaultFor(ctx: Context) = pickDefault(totalRam(ctx))

    fun hasModel(ctx: Context): Boolean { val p = Prefs.modelPath(ctx); return p != null && File(p).exists() }

    /** true = Wi-Fi/unmetered, false = mobile data, null = offline */
    fun unmetered(ctx: Context): Boolean? = try {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) null
        else caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    } catch (_: Exception) { null }

    /** Retry backoff after failures: 1, 2, 4 … 60 minutes. */
    fun retryDelayMs(failures: Int): Long = (60_000L shl failures.coerceIn(0, 6)).coerceAtMost(60 * 60_000L)

    /**
     * Zero-touch model download: starts the default model if there is none and nothing is downloading.
     * Uses mobile data only if the user said yes once (Prefs.meteredChoice == 1); otherwise DownloadManager waits for Wi-Fi.
     * Returns true if a download was (re)started.
     */
    fun ensureAuto(ctx: Context): Boolean {
        if (!Prefs.flag(ctx, "auto_model")) return false
        poll(ctx)
        if (hasModel(ctx) || Prefs.downloadId(ctx) >= 0) return false
        val fails = Prefs.int(ctx, "dl_fails")
        if (fails > 0 && System.currentTimeMillis() - Prefs.long(ctx, "dl_fail_at") < retryDelayMs(fails - 1)) return false
        return try {
            startDownload(ctx, defaultFor(ctx), Prefs.meteredChoice(ctx) == 1); true
        } catch (_: Exception) { false }
    }

    fun startDownload(ctx: Context, info: ModelInfo, allowMetered: Boolean = true): Long {
        val dm = ctx.getSystemService(DownloadManager::class.java)
        val old = Prefs.downloadId(ctx)
        if (old >= 0) try { dm.remove(old) } catch (_: Exception) {}
        File(modelsDir(ctx), info.file + ".part").delete()
        val req = DownloadManager.Request(Uri.parse(info.url))
            .setTitle("मायरा का AI मॉडल")
            .setDescription(info.name)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(ctx, "models", info.file + ".part")
            .setAllowedOverMetered(allowMetered)
            .setAllowedOverRoaming(true)
        val id = dm.enqueue(req)
        Prefs.setDownload(ctx, id, info.file)
        return id
    }

    fun cancelDownload(ctx: Context) {
        val id = Prefs.downloadId(ctx)
        if (id >= 0) try { ctx.getSystemService(DownloadManager::class.java).remove(id) } catch (_: Exception) {}
        Prefs.setDownload(ctx, -1, null)
    }

    data class DlState(val status: Int, val done: Long, val total: Long, val reason: Int)

    /** Polls the system DownloadManager. Finalizes the file when complete. Returns null if no download. */
    fun poll(ctx: Context): DlState? {
        val id = Prefs.downloadId(ctx)
        if (id < 0) return null
        val dm = ctx.getSystemService(DownloadManager::class.java)
        dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
            if (!c.moveToFirst()) { Prefs.setDownload(ctx, -1, null); return null }
            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                val name = Prefs.downloadFile(ctx) ?: return DlState(status, done, total, reason)
                val part = File(modelsDir(ctx), "$name.part")
                val fin = File(modelsDir(ctx), name)
                if (part.exists()) { fin.delete(); part.renameTo(fin) }
                if (fin.exists()) Prefs.setModelPath(ctx, fin.absolutePath)
                Prefs.setDownload(ctx, -1, null)
                Prefs.setInt(ctx, "dl_fails", 0)
            } else if (status == DownloadManager.STATUS_FAILED) {
                Prefs.setDownload(ctx, -1, null)
                try { dm.remove(id) } catch (_: Exception) {}
                Prefs.setInt(ctx, "dl_fails", Prefs.int(ctx, "dl_fails") + 1)
                Prefs.setLong(ctx, "dl_fail_at", System.currentTimeMillis())
            }
            return DlState(status, done, total, reason)
        }
        return null
    }

    fun displayName(ctx: Context, uri: Uri): String {
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) return it.getString(0) ?: "model.task"
            }
        } catch (_: Exception) {}
        return uri.lastPathSegment?.substringAfterLast('/') ?: "model.task"
    }

    fun fileSize(ctx: Context, uri: Uri): Long {
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
                if (it.moveToFirst() && !it.isNull(0)) return it.getLong(0)
            }
        } catch (_: Exception) {}
        return -1
    }

    /** Copies a picked .task file into app storage. Blocking; call from IO. */
    fun importModel(ctx: Context, uri: Uri, onProgress: (Long, Long) -> Unit): File {
        var name = displayName(ctx, uri).replace(Regex("[^A-Za-z0-9._-]"), "_")
        if (!name.endsWith(".task")) name += ".task"
        val total = fileSize(ctx, uri)
        val dest = File(modelsDir(ctx), name)
        val tmp = File(modelsDir(ctx), "$name.copy")
        ctx.contentResolver.openInputStream(uri)!!.use { input ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(1 shl 20)
                var copied = 0L
                var lastReport = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    copied += n
                    if (copied - lastReport > 8_000_000) { lastReport = copied; onProgress(copied, total) }
                }
                onProgress(copied, total)
            }
        }
        dest.delete()
        tmp.renameTo(dest)
        Prefs.setModelPath(ctx, dest.absolutePath)
        return dest
    }
}
