package com.example.myna_mimicyourinteractionsautomate.intent

import com.example.myna_mimicyourinteractionsautomate.llm.Llm
import com.example.myna_mimicyourinteractionsautomate.recipe.Recipe
import com.example.myna_mimicyourinteractionsautomate.replay.Slots
import org.json.JSONObject

/**
 * Voice command → recipe + blank values (design §4.4).
 * Thresholds from VASTA, re-measured on gemini-embedding-001 (23 Sep): paraphrases 0.75–0.88,
 * "Order pizza" 0.68, unrelated apps ≤ 0.53.
 */
object IntentMatcher {

    const val RUN = 0.70
    const val CONFIRM = 0.60

    data class Match(val recipe: Recipe, val score: Float)

    /** What the AI read from the command: a value per blank, and whether the user actually said it. */
    data class Fill(val values: Map<String, String>, val said: Set<String>)

    /** The assistant's next move. */
    sealed interface Decision {
        data class Run(val recipe: Recipe, val values: Map<String, String>, val confirm: String? = null) : Decision
        /** Ask for [slot]; [rest] are the other unsaid blanks, asked next. */
        data class AskSlot(val recipe: Recipe, val values: Map<String, String>, val slot: String, val question: String, val rest: List<String> = emptyList()) : Decision
        data class DidYouMean(val recipe: Recipe, val values: Map<String, String>, val question: String) : Decision
        data class Unknown(val utterance: String) : Decision
        data object Report : Decision
    }

    private val cache = mutableMapOf<String, FloatArray>()
    private suspend fun embed(text: String) = cache.getOrPut(text) { Llm.embed(text) }

    /** Texts that stand for a recipe: its command, summary with the demo's values, paraphrases, and plain templates. */
    fun texts(r: Recipe): List<String> {
        val demo = r.slots.mapValues { it.value.value ?: it.value.default ?: it.key }
        val plain = r.slots.keys.associateWith { it.replace('_', ' ') }   // "order item from restaurant on Zomato"
        val templates = listOfNotNull(r.summary) + r.paraphrases
        return (listOf(r.utterance) + templates.map { Slots.fill(it, demo)!! } + templates.map { Slots.fill(it, plain)!! }).distinct()
    }

    suspend fun rank(utterance: String, recipes: List<Recipe>): List<Match> {
        val u = embed(utterance)
        return recipes.map { r -> Match(r, texts(r).maxOf { Llm.cosine(u, embed(it)) }) }.sortedByDescending { it.score }
    }

    private val META = Regex("\\b(did (it|that|the (last )?(run|order|task))|last run|what happened|did you (finish|manage|do it)|status)\\b", RegexOption.IGNORE_CASE)

    private val FILL_SCHEMA = JSONObject("""
    {"title": "fill", "type": "object", "required": ["slots"],
     "properties": {"slots": {"type": "array", "items": {"type": "object", "required": ["name", "value", "said"],
        "properties": {"name": {"type": "string"}, "value": {"type": ["string", "null"]}, "said": {"type": "boolean"}}}}}}""")

    /** One AI call: which blank values does the command give? Mock/no network: nothing said, demo values. */
    suspend fun fill(utterance: String, r: Recipe): Fill {
        val prompt = buildString {
            appendLine("A user taught a phone task: \"${r.utterance}\" (template: \"${r.summary ?: r.utterance}\").")
            appendLine("Blanks and the values used last time:")
            r.slots.forEach { (k, v) -> appendLine("- $k: \"${v.value ?: v.default}\"" + (v.neighbours.takeIf { it.isNotEmpty() }?.let { " (other options seen: ${it.take(5)})" } ?: "")) }
            appendLine("New command: \"$utterance\"")
            appendLine("For every blank: value = what the new command asks for (copy the user's words, fix obvious spelling, e.g. dominos → Domino's),")
            appendLine("said = true only if the new command mentions it. If not mentioned: value = null, said = false.")
        }
        val out = runCatching { Llm.llm(prompt, FILL_SCHEMA) }.getOrNull()
        val values = mutableMapOf<String, String>(); val said = mutableSetOf<String>()
        out?.optJSONArray("slots")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("name"); if (name !in r.slots) continue
                val v = o.optString("value").takeIf { !o.isNull("value") && it.isNotBlank() }
                if (o.optBoolean("said") && v != null) { values[name] = v; said += name }
            }
        }
        return Fill(values, said)
    }

    fun ask(r: Recipe, values: Map<String, String>, missing: List<String>): Decision.AskSlot {
        val s = missing.first()
        val last = r.slots[s]?.let { it.value ?: it.default }
        return Decision.AskSlot(r, values, s, "Which ${s.replace('_', ' ')}?" + (last?.let { " Last time it was $it." } ?: ""), missing.drop(1))
    }

    /** The whole decision for one spoken command. */
    suspend fun decide(
        utterance: String,
        recipes: List<Recipe>,
        ranker: suspend (String, List<Recipe>) -> List<Match> = ::rank,   // tests pin scores measured on Gemini
    ): Decision {
        if (META.containsMatchIn(utterance)) return Decision.Report
        val top = ranker(utterance, recipes).firstOrNull()
        if (top == null || top.score < CONFIRM) return Decision.Unknown(utterance)
        val r = top.recipe
        val fill = fill(utterance, r)
        val last = r.slots.mapValues { it.value.value ?: it.value.default.orEmpty() }
        val values = last + fill.values
        val summary = Slots.fill(r.summary ?: r.utterance, values)!!
        // T13: vague ("Order pizza") or only a middling match → offer the closest with last time's values.
        if (top.score < RUN || (fill.said.isEmpty() && r.slots.isNotEmpty() && top.score < 0.8f))
            return Decision.DidYouMean(r, values, "Did you mean: $summary — like last time?")
        // Mid-flow bonus: some blanks said, some not → ask for the first missing one.
        val missing = r.slots.keys.filter { it !in fill.said }
        if (fill.said.isNotEmpty() && missing.isNotEmpty()) return ask(r, values, missing)
        // VASTA saw swapped blanks: 2+ changes → say it back first.
        val changed = fill.values.filter { (k, v) -> !v.equals(last[k], ignoreCase = true) }
        return Decision.Run(r, values, confirm = if (changed.size >= 2) "Just to check: $summary?" else null)
    }
}
