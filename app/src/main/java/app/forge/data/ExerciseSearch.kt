package app.forge.data

/**
 * Exercise search for the picker (v1.1). Matches names and aliases ("RDL", "OHP", "pull down"),
 * ignores case, spaces and hyphens ("pulldown" = "pull down" = "pull-down"), and forgives a typo or two
 * ("romainan", "tricep pushdwn"). Better matches rank first: exact, then prefix, then contained words,
 * then near misses.
 */
object ExerciseSearch {
    private fun norm(s: String) = s.lowercase().replace('’', '\'').replace(Regex("[^a-z0-9° ]"), " ").replace(Regex("\\s+"), " ").trim()
    private fun squash(s: String) = norm(s).replace(" ", "")

    /** How well [query] matches [ex]; 0 means no match. */
    fun score(ex: Exercise, query: String): Int {
        val q = norm(query)
        if (q.isEmpty()) return 1
        val qs = q.replace(" ", "")
        val qWords = q.split(' ')
        var best = 0
        for (cand in listOf(ex.name) + ex.aliases) {
            val c = norm(cand)
            val cs = c.replace(" ", "")
            val words = c.split(' ')
            val s = when {
                cs == qs -> 100
                cs.startsWith(qs) -> 85
                words.any { it.startsWith(qs) } -> 75
                cs.contains(qs) -> 65
                // Every word typed starts some word of the name: "inc db" → "incline dumbbell".
                qWords.all { qw -> words.any { it.startsWith(qw) } } -> 55
                // Typos: each typed word is close to a word of the name (or the whole thing is close).
                qWords.all { qw -> qw.length >= 3 && words.any { close(qw, it) } } -> 35
                qs.length >= 4 && close(qs, cs) -> 30
                else -> 0
            }
            if (s > best) best = s
        }
        return best
    }

    /** Matches for [query], best first; ties keep the library's order (main lifts first). */
    fun search(all: List<Exercise>, query: String): List<Exercise> =
        all.withIndex().map { (i, e) -> Triple(e, score(e, query), i) }.filter { it.second > 0 }
            .sortedWith(compareByDescending<Triple<Exercise, Int, Int>> { it.second }.thenBy { it.third }).map { it.first }

    /** Close enough to be a typo: one edit for short words, two for longer ones. Also matches a typed prefix. */
    private fun close(typed: String, word: String): Boolean {
        val allowed = if (typed.length <= 4) 1 else 2
        if (kotlin.math.abs(typed.length - word.length) <= allowed && distance(typed, word) <= allowed) return true
        // A partly typed word with a typo: compare against the start of the word.
        return word.length > typed.length && distance(typed, word.take(typed.length)) <= (if (typed.length <= 4) 0 else 1)
    }

    /** Optimal string alignment distance (Levenshtein plus swapped neighbours). */
    fun distance(a: String, b: String): Int {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
        }
        return d[a.length][b.length]
    }
}
