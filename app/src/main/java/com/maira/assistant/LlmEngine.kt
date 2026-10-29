package com.maira.assistant

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.google.mediapipe.tasks.genai.llminference.PromptTemplates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Fully offline LLM using MediaPipe LLM Inference (.task model on the phone). */
class LlmEngine(private val ctx: Context) {
    private val mutex = Mutex()
    @Volatile private var llm: LlmInference? = null
    @Volatile private var loadedPath: String? = null
    @Volatile var loading = false; private set
    @Volatile var lastError: String? = null; private set

    val isLoaded: Boolean get() = llm != null && loadedPath == Prefs.modelPath(ctx)

    suspend fun ensureLoaded(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock { loadLocked() }
    }

    private fun loadLocked(): Boolean {
        val path = Prefs.modelPath(ctx) ?: return false
        if (!File(path).exists()) { lastError = "मॉडल फ़ाइल नहीं मिली"; return false }
        if (llm != null && loadedPath == path) return true
        loading = true
        return try {
            try { llm?.close() } catch (_: Exception) {}
            llm = null
            val opts = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(path)
                .setMaxTokens(1024)
                .setMaxTopK(40)
                .build()
            llm = LlmInference.createFromOptions(ctx, opts)
            loadedPath = path
            lastError = null
            true
        } catch (t: Throwable) {
            lastError = t.message ?: t.toString()
            llm = null
            false
        } finally {
            loading = false
        }
    }

    /** Generates a reply. [onPartial] receives the accumulated text so far (on a background thread). */
    suspend fun generate(prompt: String, onPartial: ((String) -> Unit)? = null): String =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (!loadLocked()) throw IllegalStateException(lastError ?: "मॉडल लोड नहीं हुआ")
                val engine = llm!!
                val templates = PromptTemplates.builder()
                    .setUserPrefix("").setUserSuffix("")
                    .setModelPrefix("").setModelSuffix("")
                    .setSystemPrefix("").setSystemSuffix("")
                    .build()
                val sOpts = LlmInferenceSession.LlmInferenceSessionOptions.builder()
                    .setTopK(40)
                    .setTopP(0.9f)
                    .setTemperature(0.6f)
                    .setRandomSeed((System.currentTimeMillis() % 100000).toInt())
                    .setPromptTemplates(templates)
                    .build()
                val session = LlmInferenceSession.createFromOptions(engine, sOpts)
                try {
                    session.addQueryChunk(prompt)
                    if (onPartial == null) {
                        session.generateResponse()
                    } else {
                        val sb = StringBuilder()
                        val fut = session.generateResponseAsync { partial, _ ->
                            if (partial != null) { sb.append(partial); onPartial(sb.toString()) }
                        }
                        val full = fut.get()
                        if (full.isNullOrBlank()) sb.toString() else full
                    }
                } finally {
                    try { session.close() } catch (_: Exception) {}
                }
            }
        }

    companion object {
        const val SYSTEM_PROMPT = """You are Maira (मायरा), a helpful, warm female voice assistant on the user's Android phone. The user speaks Hindi or Hinglish. ALWAYS reply ONLY in simple Hindi written in Devanagari script, never in English or Roman letters. Keep answers short: 1 to 3 sentences, easy to speak aloud. Use feminine grammar for yourself (मैं कर सकती हूँ). No lists, no markdown, no emojis.
तुम मायरा हो, एक मददगार महिला सहायक। हमेशा सिर्फ़ आसान हिंदी (देवनागरी) में, छोटा जवाब दो।
If and only if the user asks you to do a task on the phone, reply with ONLY one JSON object and nothing else, for example {"action":"open_app","name":"YouTube"}. Allowed actions: open_app(name), call(name), sms(name,text), flashlight(state:"on"/"off"), volume(dir:"up"/"down"/"mute"), alarm(hour,minute), timer(seconds), settings(panel:"wifi"/"bluetooth"/"settings"), home, back, notifications, lock, screenshot. For normal questions and chat, reply in Hindi text, not JSON."""

        fun buildPrompt(history: List<Pair<String, String>>, user: String): String {
            val sb = StringBuilder()
            sb.append("<|im_start|>system\n").append(SYSTEM_PROMPT).append("<|im_end|>\n")
            for ((u, a) in history) {
                sb.append("<|im_start|>user\n").append(u).append("<|im_end|>\n")
                sb.append("<|im_start|>assistant\n").append(a).append("<|im_end|>\n")
            }
            sb.append("<|im_start|>user\n").append(user)
                .append("\n(जवाब सिर्फ़ आसान हिंदी में, छोटा।)<|im_end|>\n")
            sb.append("<|im_start|>assistant\n")
            return sb.toString()
        }

        fun cleanOutput(s: String): String {
            var t = s
            listOf("<|im_end|>", "<|im_start|>", "<|endoftext|>", "assistant\n").forEach { t = t.replace(it, " ") }
            t = t.substringBefore("<|").trim()
            t = t.removePrefix("मायरा:").removePrefix("Maira:").trim()
            return t
        }
    }
}
