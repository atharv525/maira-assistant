package com.maira.assistant.brain

// ---------------------------------------------------------------------------------------------
// MairaBrain — the user's own from-scratch command model ("Maira Brain v2"): Hindi / Hinglish -> JSON action.
// Tiny encoder-decoder Transformer with a pointer/copy head, exported to ONNX (int8) and run with ONNX Runtime.
// The decoder ONNX graph already contains the copy mechanism and returns LOG-PROBABILITIES (B,V) for the last
// position, so greedy decoding = argmax per step (same loop as maira-brain/export.py ort_predict()).
//
// This file has no Android imports on purpose: the same code runs in the JVM parity test
// (onnxruntime JVM build). Asset loading lives in MairaBrainLoader.kt.
// ---------------------------------------------------------------------------------------------

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.json.JSONObject
import java.nio.LongBuffer
import kotlin.math.exp

data class MairaCommand(
    val action: String,              // e.g. "open_app", "set_alarm", "chat"; "" when the output is not valid JSON
    val args: Map<String, Any>,      // e.g. {"app":"whatsapp"} / {"time":"07:30"} / {"level":40}
    val json: String,                // raw decoded model output
    val confidence: Float,           // min softmax prob over the generated tokens (0..1)
    val millis: Long,
    val valid: Boolean               // output parsed as a JSON object with an "action"
) {
    fun str(k: String): String = (args[k] as? String)?.trim().orEmpty()
    fun int(k: String): Int? = when (val v = args[k]) { is Number -> v.toInt(); is String -> v.trim().toIntOrNull(); else -> null }
}

class MairaBrain(encoderBytes: ByteArray, decoderBytes: ByteArray, tokenizerJson: String, threads: Int = 2) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val encoder: OrtSession
    private val decoder: OrtSession
    val tokenizer = MairaTokenizer(tokenizerJson)

    companion object { const val MAX_OUT = 48 }

    init {
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threads)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        encoder = env.createSession(encoderBytes, opts)
        decoder = env.createSession(decoderBytes, opts)
    }

    private fun longTensor(a: LongArray): OnnxTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(a), longArrayOf(1, a.size.toLong()))

    /** Greedy decode. Returns generated ids (incl. EOS if produced) and min token probability. */
    @Synchronized
    fun generate(src: LongArray): Pair<List<Int>, Float> {
        val out = arrayListOf(MairaTokenizer.BOS.toLong())
        var minProb = 1f
        longTensor(src).use { srcT ->
            encoder.run(mapOf("src_ids" to srcT)).use { encRes ->
                val memory = encRes.get(0) as OnnxTensor
                for (step in 0 until MAX_OUT) {
                    val next = longTensor(out.toLongArray()).use { tgtT ->
                        decoder.run(mapOf("tgt_ids" to tgtT, "memory" to memory, "src_ids" to srcT)).use { r ->
                            @Suppress("UNCHECKED_CAST")
                            val lp = (r.get(0).value as Array<FloatArray>)[0]
                            var bi = 0
                            for (k in 1 until lp.size) if (lp[k] > lp[bi]) bi = k
                            var sum = 0.0; val mx = lp[bi]
                            for (v in lp) sum += exp((v - mx).toDouble())
                            minProb = minOf(minProb, (1.0 / sum).toFloat())
                            bi
                        }
                    }
                    out.add(next.toLong())
                    if (next == MairaTokenizer.EOS) break
                }
            }
        }
        return out.drop(1).map { it.toInt() } to minProb
    }

    fun parse(text: String): MairaCommand {
        val t0 = System.currentTimeMillis()
        val src = tokenizer.encodeSrc(text)
        if (src.isEmpty()) return MairaCommand("", emptyMap(), "", 0f, 0, false)
        val (ids, conf) = generate(src)
        val json = tokenizer.decodeTgt(ids)
        val ms = System.currentTimeMillis() - t0
        return try {
            val o = JSONObject(json)
            val args = LinkedHashMap<String, Any>()
            o.keys().forEach { k -> if (k != "action") args[k] = o.get(k) }
            MairaCommand(o.getString("action"), args, json, conf, ms, true)
        } catch (e: Exception) {
            MairaCommand("", emptyMap(), json, conf, ms, false)
        }
    }

    override fun close() { encoder.close(); decoder.close() }
}

/** Helpers for slot values. */
object MairaUtil {
    /** "calculate" -> {"expression":"25 * 4"}. Space separated numbers and + - * / ^ ; ^ first, then * /, then + -. */
    fun evalExpression(expr: String): Double? = try {
        val t = expr.trim().split(Regex("\\s+"))
        val nums = ArrayList<Double>(); val ops = ArrayList<String>()
        nums.add(t[0].toDouble())
        var i = 1
        while (i + 1 < t.size) {
            if (t[i] !in setOf("+", "-", "*", "/", "^")) throw IllegalArgumentException(t[i])
            ops.add(t[i]); nums.add(t[i + 1].toDouble()); i += 2
        }
        if (i != t.size) throw IllegalArgumentException("dangling")
        fun reduce(set: Set<String>) {
            var k = 0
            while (k < ops.size) {
                if (ops[k] in set) {
                    val a = nums[k]; val b = nums[k + 1]
                    nums[k] = when (ops[k]) { "^" -> Math.pow(a, b); "*" -> a * b; "/" -> a / b; "+" -> a + b; else -> a - b }
                    nums.removeAt(k + 1); ops.removeAt(k)
                } else k++
            }
        }
        reduce(setOf("^")); reduce(setOf("*", "/")); reduce(setOf("+", "-"))
        nums[0].takeIf { !it.isNaN() && !it.isInfinite() }
    } catch (e: Exception) { null }

    /** "1h30m" / "7m15s" / "90s" -> total seconds (0 when nothing parsed). */
    fun durationSeconds(d: String): Int =
        Regex("(\\d+)([hms])").findAll(d).sumOf { m ->
            val n = m.groupValues[1].toInt()
            when (m.groupValues[2]) { "h" -> n * 3600; "m" -> n * 60; else -> n }
        }

    /** "06:30" -> (6, 30) */
    fun clock(t: String): Pair<Int, Int>? {
        val m = Regex("^(\\d{1,2}):(\\d{2})$").find(t.trim()) ?: return null
        val h = m.groupValues[1].toInt(); val mi = m.groupValues[2].toInt()
        return if (h in 0..23 && mi in 0..59) h to mi else null
    }

    /** Formats a calculation result for speech: 100.0 -> "100", 3.333333 -> "3.33". */
    fun formatNumber(v: Double): String =
        if (v == Math.rint(v) && Math.abs(v) < 1e15) v.toLong().toString()
        else String.format(java.util.Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')

    /**
     * The model lower-cases its input, so copied slot text is lower-case. Recover the user's original casing when the
     * slot appears verbatim (case-insensitively) in what they said, e.g. type_text "hello rahul" -> "Hello Rahul".
     */
    fun recoverCase(raw: String, value: String): String {
        if (value.isBlank()) return value
        val lower = raw.lowercase()
        if (lower.length != raw.length) return value
        val i = lower.indexOf(value)
        return if (i >= 0) raw.substring(i, i + value.length) else value
    }
}
