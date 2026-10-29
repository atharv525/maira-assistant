package com.maira.assistant

import android.app.DownloadManager
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

    fun startDownload(ctx: Context, info: ModelInfo): Long {
        val dm = ctx.getSystemService(DownloadManager::class.java)
        val old = Prefs.downloadId(ctx)
        if (old >= 0) try { dm.remove(old) } catch (_: Exception) {}
        File(modelsDir(ctx), info.file + ".part").delete()
        val req = DownloadManager.Request(Uri.parse(info.url))
            .setTitle("मायरा का AI मॉडल")
            .setDescription(info.name)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(ctx, "models", info.file + ".part")
            .setAllowedOverMetered(true)
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
            } else if (status == DownloadManager.STATUS_FAILED) {
                Prefs.setDownload(ctx, -1, null)
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
