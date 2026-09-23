package com.example.myna_mimicyourinteractionsautomate.intent

import com.example.myna_mimicyourinteractionsautomate.recipe.Recipe
import kotlin.math.max
import kotlin.math.min

/**
 * Fixes speech-to-text mis-hearings against words MYNA already knows ("marherator" → "margherita"):
 * recipe commands, blank values and the options seen on screen while learning (menu items, products).
 */
object SpeechFix {

    data class Result(val text: String, val changes: List<Pair<String, String>>)

    /** Words worth correcting to, from every recipe. */
    fun vocabulary(recipes: List<Recipe>): Set<String> = recipes.flatMap { r ->
        listOf(r.utterance, r.summary.orEmpty()) + r.paraphrases + r.slots.values.flatMap { s -> listOfNotNull(s.value, s.default) + s.neighbours }
    }.flatMap { it.split(Regex("[^\\p{L}\\p{N}']+")) }
        .map { it.trim('\'') }.filter { it.length >= 4 && !it.startsWith("{") }
        .associateBy { it.lowercase() }.values.toSet()

    /** Replace unknown words (and split words: "farm house") with the closest known word, if close enough. */
    fun fix(text: String, vocab: Set<String>, threshold: Double = 0.86): Result {
        if (vocab.isEmpty()) return Result(text, emptyList())
        val known = vocab.associateBy { it.lowercase() }
        val words = text.split(" ").filter { it.isNotEmpty() }.toMutableList()
        val changes = mutableListOf<Pair<String, String>>()
        var i = 0
        while (i < words.size) {
            val w = words[i].trim(',', '.', '?', '!')
            val lw = w.lowercase()
            if (lw.length < 4 || lw in known || lw in COMMON) { i++; continue }
            // Two words heard for one ("farm house" → "Farmhouse")?
            val pair = words.getOrNull(i + 1)?.let { (lw + it.lowercase().trim(',', '.', '?', '!')) }
            val pairHit = pair?.let { p -> known[p] ?: best(p, known.keys, threshold)?.let { known[it] } }
            if (pairHit != null) {
                changes += "${words[i]} ${words[i + 1]}" to pairHit
                words[i] = pairHit; words.removeAt(i + 1); i++; continue
            }
            best(lw, known.keys, threshold)?.let { hit ->
                changes += w to known[hit]!!
                words[i] = words[i].replace(w, known[hit]!!)
            }
            i++
        }
        return Result(words.joinToString(" "), changes)
    }

    private fun best(w: String, candidates: Collection<String>, threshold: Double): String? =
        candidates.filter { kotlin.math.abs(it.length - w.length) <= 3 }
            .map { it to jaroWinkler(w, it) }.filter { it.second >= threshold }.maxByOrNull { it.second }?.first

    /** Jaro-Winkler: forgiving of the dropped/swapped letters speech recognisers make; rewards a shared start. */
    fun jaroWinkler(a: String, b: String): Double {
        if (a == b) return 1.0
        val range = max(0, max(a.length, b.length) / 2 - 1)
        val am = BooleanArray(a.length); val bm = BooleanArray(b.length)
        var matches = 0
        for (i in a.indices) for (j in max(0, i - range) until min(b.length, i + range + 1)) {
            if (!bm[j] && a[i] == b[j]) { am[i] = true; bm[j] = true; matches++; break }
        }
        if (matches == 0) return 0.0
        var t = 0; var k = 0
        for (i in a.indices) if (am[i]) { while (!bm[k]) k++; if (a[i] != b[k]) t++; k++ }
        val m = matches.toDouble()
        val jaro = (m / a.length + m / b.length + (m - t / 2.0) / m) / 3
        val prefix = a.zip(b).takeWhile { it.first == it.second }.size.coerceAtMost(4)
        return jaro + prefix * 0.1 * (1 - jaro)
    }

    /** Everyday words that must never be "corrected" into a dish name. */
    private val COMMON = setOf("order", "from", "with", "that", "this", "please", "want", "need", "some", "like", "last", "time",
        "same", "none", "yes", "okay", "cart", "add", "amazon", "zomato", "swiggy", "myntra", "get", "buy", "search", "find", "my",
        "the", "and", "for", "into", "phone", "pizza", "case", "cover", "what", "happened", "work", "worked", "did", "run")
}
