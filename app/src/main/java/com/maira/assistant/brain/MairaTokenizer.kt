package com.maira.assistant.brain

import org.json.JSONObject

/**
 * Pure-Kotlin port of maira-brain/tokenizer.py (BPETokenizer): normalize -> words -> rank-based BPE pieces
 * ("▁" marks a word start), and the detokeniser for the model's JSON output.
 * No Android imports, so the exact same code runs in JVM parity tests.
 */
class MairaTokenizer(tokenizerJson: String) {
    val vocab: List<String>
    private val tokenId: Map<String, Int>
    private val mergeRank: Map<Pair<String, String>, Int>
    private val merges: List<Pair<String, String>>
    val structIds: Set<Int>
    private val cache = HashMap<String, List<String>>()

    companion object {
        const val PAD = 0; const val BOS = 1; const val EOS = 2; const val UNK = 3
        const val WB = "\u2581"
        /** Training truncated sources to 62 tokens (train.py: s[:62]); positions go up to 64. */
        const val MAX_SRC = 62
        private val SPECIALS = setOf("<pad>", "<s>", "</s>", "<unk>")
        // Python re's \s (str mode) = unicode whitespace incl. \x1c-\x1f
        private val WS = Regex("(?U)[\\s\\u001c-\\u001f]+")
        private val TRAIL = Regex("(?U)[\\s\\u001c-\\u001f?!.\\u0964,]+$")
    }

    init {
        val tj = JSONObject(tokenizerJson)
        val va = tj.getJSONArray("vocab")
        vocab = List(va.length()) { va.getString(it) }
        tokenId = HashMap<String, Int>(vocab.size * 2).apply { vocab.forEachIndexed { i, t -> put(t, i) } }
        val ma = tj.getJSONArray("merges")
        val ml = ArrayList<Pair<String, String>>(ma.length())
        val mr = HashMap<Pair<String, String>, Int>(ma.length() * 2)
        for (i in 0 until ma.length()) {
            val p = ma.getJSONArray(i); val pair = p.getString(0) to p.getString(1)
            ml.add(pair)
            mr[pair] = i   // like Python's {m: i for i, m in enumerate(merges)}: the last index wins for duplicates
        }
        merges = ml; mergeRank = mr
        structIds = vocab.indices.filter { i ->
            val t = vocab[i]
            t !in SPECIALS && (t.startsWith("{") || t.startsWith(",\"") || t == "\"" || t == "}")
        }.toSet()
    }

    private fun pyStrip(s: String): String {
        // Python str.strip(): unicode whitespace (str.isspace)
        var a = 0; var b = s.length
        while (a < b && isPySpace(s[a])) a++
        while (b > a && isPySpace(s[b - 1])) b--
        return s.substring(a, b)
    }
    private fun isPySpace(c: Char) = Character.isWhitespace(c) || Character.isSpaceChar(c) || c == '\u0085'

    /** MUST match tokenizer.normalize(): lower, strip, collapse whitespace, drop trailing ?!.।, and spaces. */
    fun normalize(s: String): String {
        var t = pyStrip(s.lowercase())
        t = WS.replace(t, " ")
        t = TRAIL.replace(t, "")
        return pyStrip(t)
    }

    /** Same as BPETokenizer.bpe_word: repeatedly merge the lowest-rank adjacent pair (all its occurrences, left to right). */
    fun bpeWord(w: String): List<String> {
        cache[w]?.let { return it }
        var parts: MutableList<String> = ArrayList()
        var i0 = 0
        while (i0 < w.length) { val cp = w.codePointAt(i0); parts.add(String(Character.toChars(cp))); i0 += Character.charCount(cp) }
        while (parts.size > 1) {
            var best = -1; var bestRank = Int.MAX_VALUE
            for (i in 0 until parts.size - 1) {
                val r = mergeRank[parts[i] to parts[i + 1]] ?: continue
                if (r < bestRank) { bestRank = r; best = i }
            }
            if (best < 0) break
            val (a, b) = merges[bestRank]
            val merged = ArrayList<String>(parts.size)
            var i = 0
            while (i < parts.size) {
                if (i < parts.size - 1 && parts[i] == a && parts[i + 1] == b) { merged.add(a + b); i += 2 }
                else { merged.add(parts[i]); i += 1 }
            }
            parts = merged
        }
        if (cache.size < 5000) cache[w] = parts
        return parts
    }

    private fun encodeWords(text: String): List<Int> {
        val ids = ArrayList<Int>()
        for (w in text.split(" ")) {
            if (w.isEmpty()) continue
            for (p in bpeWord(WB + w)) ids.add(tokenId[p] ?: UNK)
        }
        return ids
    }

    /** Full (untruncated) source encoding = BPETokenizer.encode_src. */
    fun encodeSrcFull(text: String): List<Int> = encodeWords(normalize(text))

    /** What the model is fed: first [MAX_SRC] tokens. */
    fun encodeSrc(text: String): LongArray = encodeSrcFull(text).take(MAX_SRC).map { it.toLong() }.toLongArray()

    /** = BPETokenizer.decode_tgt: '▁' right after a structural token is dropped, elsewhere it becomes a space. */
    fun decodeTgt(ids: List<Int>): String {
        val sb = StringBuilder(); var prevStruct = true
        for (i in ids) {
            if (i == PAD || i == BOS) continue
            if (i == EOS) break
            var t = if (i in vocab.indices) vocab[i] else "<unk>"
            if (i in structIds) { sb.append(t); prevStruct = true }
            else {
                if (t.startsWith(WB)) t = if (prevStruct) t.substring(1) else " " + t.substring(1)
                sb.append(t); prevStruct = false
            }
        }
        return sb.toString()
    }
}
