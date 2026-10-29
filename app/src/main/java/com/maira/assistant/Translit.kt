package com.maira.assistant

/** Helpers to match Hindi (Devanagari) speech against Latin app / contact names. */
object Translit {
    private val cons = mapOf(
        'क' to "k", 'ख' to "kh", 'ग' to "g", 'घ' to "gh", 'ङ' to "n",
        'च' to "ch", 'छ' to "chh", 'ज' to "j", 'झ' to "jh", 'ञ' to "n",
        'ट' to "t", 'ठ' to "th", 'ड' to "d", 'ढ' to "dh", 'ण' to "n",
        'त' to "t", 'थ' to "th", 'द' to "d", 'ध' to "dh", 'न' to "n",
        'प' to "p", 'फ' to "f", 'ब' to "b", 'भ' to "bh", 'म' to "m",
        'य' to "y", 'र' to "r", 'ल' to "l", 'व' to "v", 'श' to "sh",
        'ष' to "sh", 'स' to "s", 'ह' to "h", 'ळ' to "l",
        '\u0958' to "q", '\u0959' to "kh", '\u095A' to "g", '\u095B' to "z", '\u095C' to "d", '\u095D' to "dh", '\u095E' to "f"
    )
    private val vowels = mapOf(
        'अ' to "a", 'आ' to "aa", 'इ' to "i", 'ई' to "ee", 'उ' to "u", 'ऊ' to "oo",
        'ए' to "e", 'ऐ' to "ai", 'ओ' to "o", 'औ' to "au", 'ऋ' to "ri", 'ऑ' to "o", 'ऍ' to "e"
    )
    private val matras = mapOf(
        'ा' to "aa", 'ि' to "i", 'ी' to "ee", 'ु' to "u", 'ू' to "oo", 'े' to "e",
        'ै' to "ai", 'ो' to "o", 'ौ' to "au", 'ृ' to "ri", 'ॉ' to "o", 'ॅ' to "e"
    )
    private val digits = "०१२३४५६७८९"

    fun hasDevanagari(s: String) = s.any { it in '\u0900'..'\u097F' }

    fun toLatin(input: String): String {
        val s = input
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val c2 = if (i + 1 < s.length && s[i + 1] == '\u093C') {
                when (c) { 'क' -> '\u0958'; 'ख' -> '\u0959'; 'ग' -> '\u095A'; 'ज' -> '\u095B'; 'ड' -> '\u095C'; 'ढ' -> '\u095D'; 'फ' -> '\u095E'; else -> c }
            } else c
            val skip = if (c2 != c) 2 else 1
            val cn = cons[c2]
            if (cn != null) {
                out.append(cn)
                val j = i + skip
                val next = if (j < s.length) s[j] else ' '
                when {
                    matras.containsKey(next) -> { out.append(matras[next]); i = j + 1; continue }
                    next == '\u094D' -> { i = j + 1; continue }
                    next.isLetter() && next in '\u0900'..'\u097F' && next != 'ं' && next != 'ँ' && next != 'ः' -> out.append("a")
                    next == 'ं' || next == 'ँ' -> out.append("a")
                    else -> { /* word end: schwa deletion */ }
                }
                i = j; continue
            }
            when {
                vowels.containsKey(c) -> out.append(vowels[c])
                matras.containsKey(c) -> out.append(matras[c])
                c == 'ं' || c == 'ँ' -> out.append("n")
                c == 'ः' -> out.append("h")
                c == '\u094D' || c == '\u093C' -> {}
                c in digits -> out.append('0' + digits.indexOf(c))
                c == '।' -> out.append('.')
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }

    /** Normalized phonetic key, vowels kept but simplified. */
    fun key(s: String): String {
        var t = (if (hasDevanagari(s)) toLatin(s) else s).lowercase()
        t = t.replace(Regex("[^a-z0-9]"), "")
        val reps = listOf(
            "chh" to "C", "ch" to "C", "sh" to "s", "ph" to "f", "kh" to "k", "gh" to "g",
            "th" to "t", "dh" to "d", "bh" to "b", "jh" to "j", "ck" to "k", "q" to "k",
            "c" to "k", "w" to "v", "z" to "j", "x" to "ks",
            "ee" to "i", "ii" to "i", "oo" to "u", "ou" to "u", "aa" to "a", "y" to "i", "ai" to "e", "ae" to "e",
            "h" to ""
        )
        for ((a, b) in reps) t = t.replace(a, b)
        t = t.replace("C", "ch")
        // collapse doubles
        val sb = StringBuilder()
        for (ch in t) if (sb.isEmpty() || sb.last() != ch) sb.append(ch)
        return sb.toString()
    }

    /** Consonant skeleton (first letter kept). */
    fun skeleton(s: String): String {
        val k = key(s)
        if (k.isEmpty()) return k
        val sb = StringBuilder().append(k[0])
        for (ch in k.substring(1)) if (ch !in "aeiou") { if (sb.last() != ch) sb.append(ch) }
        return sb.toString()
    }

    fun lev(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    private fun sim(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        return 1.0 - lev(a, b).toDouble() / maxOf(a.length, b.length)
    }

    /** Similarity 0..1 between a spoken query and a target name. */
    fun similarity(query: String, target: String): Double {
        val q = query.trim().lowercase()
        val t = target.trim().lowercase()
        if (q.isEmpty() || t.isEmpty()) return 0.0
        if (q == t) return 1.0
        val kq = key(q); val kt = key(t)
        if (kq == kt) return 0.98
        var best = sim(kq, kt)
        // match against individual words of the target ("Mummy Jio" -> "mummy")
        for (w in t.split(Regex("\\s+"))) if (w.length >= 2) best = maxOf(best, sim(kq, key(w)) * 0.95)
        if (kt.length >= 3 && kq.length >= 3 && (kt.startsWith(kq) || kq.startsWith(kt))) best = maxOf(best, 0.85)
        val sq = skeleton(q); val st = skeleton(t)
        if (sq.length >= 3 && st.length >= 3) best = maxOf(best, sim(sq, st) * 0.92)
        return best
    }
}
