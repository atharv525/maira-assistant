package com.maira.assistant.brain

import android.content.Context
import android.util.Log

/** Loads Maira Brain once from assets (≈4 MB). Thread-safe; null if the model can't be loaded on this phone. */
object MairaBrainLoader {
    private const val TAG = "MairaBrain"
    @Volatile private var brain: MairaBrain? = null
    @Volatile private var failed = false

    fun get(ctx: Context): MairaBrain? {
        brain?.let { return it }
        if (failed) return null
        synchronized(this) {
            brain?.let { return it }
            if (failed) return null
            return try {
                val a = ctx.applicationContext.assets
                val b = MairaBrain(
                    a.open("maira_encoder_int8.onnx").use { it.readBytes() },
                    a.open("maira_decoder_int8.onnx").use { it.readBytes() },
                    a.open("maira_tokenizer.json").use { it.readBytes().toString(Charsets.UTF_8) },
                    threads = 2
                )
                brain = b; b
            } catch (t: Throwable) {
                Log.e(TAG, "Maira Brain load failed", t); failed = true; null
            }
        }
    }

    fun isLoaded() = brain != null
}
