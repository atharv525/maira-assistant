package com.maira.assistant

/** What Maira saw on screen (or in notifications) — kept as context for follow-up questions. */
data class ScreenCapture(val appName: String, val pkg: String, val text: String, val time: Long = System.currentTimeMillis())

/** One piece of visible text with its position. [group] orders windows (0 = top-most dialog/app window). */
data class ScreenItem(val text: String, val top: Int, val left: Int, val group: Int = 0)

data class NotifItem(val app: String, val title: String, val text: String, val time: Long = 0L)

/** Pure helpers (unit-tested) to turn raw accessibility text into something speakable. */
object ScreenText {
    const val MAX_CHARS = 1500

    fun clean(s: String): String = s
        .replace(Regex("[\\u200B-\\u200F\\u202A-\\u202E\\u2060\\uFEFF]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun key(s: String) = clean(s).lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")

    /** Sort top-to-bottom (window group first), dedupe, cap length. Items are joined with newlines. */
    fun compose(items: List<ScreenItem>, max: Int = MAX_CHARS): String {
        val sorted = items.withIndex()
            .sortedWith(compareBy({ it.value.group }, { it.value.top / 12 }, { it.value.left }, { it.index }))
            .map { it.value }
        val seen = HashSet<String>()
        val kept = ArrayList<String>()
        for (it in sorted) {
            val c = clean(it.text)
            val k = key(c)
            if (k.isEmpty()) continue
            if (!seen.add(k)) continue
            // skip a short piece that is fully contained in the previous kept line (label + same content description)
            val prev = kept.lastOrNull()
            if (prev != null && k.length < 40 && key(prev).contains(k)) continue
            kept.add(c)
        }
        return cap(kept.joinToString("\n"), max)
    }

    fun cap(s: String, max: Int): String {
        if (s.length <= max) return s
        val cut = s.substring(0, max)
        val b = maxOf(cut.lastIndexOf('\n'), cut.lastIndexOf('.'), cut.lastIndexOf('।'), cut.lastIndexOf(' '))
        return (if (b > max / 2) cut.substring(0, b) else cut).trimEnd() + " …"
    }

    /** Text for TTS: newlines become short pauses. */
    fun forSpeech(s: String): String = s.split('\n').map { it.trim().trimEnd('.', '।') }.filter { it.isNotEmpty() }
        .joinToString("। ") + "।"

    /** Rough token estimate for Qwen: Devanagari ~1 token/char, Latin ~0.3 token/char. */
    fun estimateTokens(s: String): Int {
        var t = 0.0
        for (c in s) t += when {
            c in '\u0900'..'\u097F' -> 1.0
            c.code < 128 -> 0.3
            else -> 1.0
        }
        return t.toInt() + 1
    }

    /** Truncate so that the estimated token count fits [budget]. */
    fun fitTokens(s: String, budget: Int): String {
        if (estimateTokens(s) <= budget) return s
        var lo = 0; var hi = s.length
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (estimateTokens(s.substring(0, mid)) <= budget) lo = mid else hi = mid - 1
        }
        return cap(s.substring(0, lo), lo)
    }

    /** Spoken Hindi summary of notifications, newest first. */
    fun notifications(list: List<NotifItem>, max: Int = 900): String {
        val items = list.sortedByDescending { it.time }
            .map { NotifItem(clean(it.app), clean(it.title), clean(it.text), it.time) }
            .filter { it.title.isNotEmpty() || it.text.isNotEmpty() }
            .distinctBy { key(it.app + it.title + it.text) }
        if (items.isEmpty()) return "अभी कोई नया नोटिफिकेशन नहीं है।"
        val sb = StringBuilder("आपके ${items.size} नोटिफिकेशन हैं। ")
        for ((i, n) in items.withIndex()) {
            val body = listOf(n.title, n.text).filter { it.isNotEmpty() }.distinct().joinToString(": ")
            val line = (if (n.app.isNotEmpty()) "${n.app} से — " else "") + body.trimEnd('.', '।') + "। "
            if (sb.length + line.length > max) {
                sb.append("और ${items.size - i} नोटिफिकेशन भी हैं।"); break
            }
            sb.append(line)
        }
        return sb.toString().trim()
    }
}
