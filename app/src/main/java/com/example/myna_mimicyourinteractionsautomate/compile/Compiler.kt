package com.example.myna_mimicyourinteractionsautomate.compile

import com.example.myna_mimicyourinteractionsautomate.llm.Llm
import com.example.myna_mimicyourinteractionsautomate.recipe.End
import com.example.myna_mimicyourinteractionsautomate.recipe.KeyKind
import com.example.myna_mimicyourinteractionsautomate.recipe.Recipe
import com.example.myna_mimicyourinteractionsautomate.recipe.Recording
import com.example.myna_mimicyourinteractionsautomate.recipe.Slot
import com.example.myna_mimicyourinteractionsautomate.recipe.Step
import com.example.myna_mimicyourinteractionsautomate.recipe.StepType
import com.example.myna_mimicyourinteractionsautomate.recipe.Subtask
import com.example.myna_mimicyourinteractionsautomate.recipe.UniqueKey
import com.example.myna_mimicyourinteractionsautomate.record.Recorder
import com.example.myna_mimicyourinteractionsautomate.record.describe
import com.example.myna_mimicyourinteractionsautomate.replay.Privacy
import com.example.myna_mimicyourinteractionsautomate.screen.Identity.loose
import org.json.JSONArray
import org.json.JSONObject

/**
 * Recording → Recipe (design §4.2). Blanks are found WITHOUT AI (spoken words that match what was
 * typed/searched/tapped); one AI call then names them and adds paraphrases, "why" lines, screen notes,
 * sub-tasks, noise and mismatch flags. Mock mode / no network → deterministic names, one sub-task.
 */
object Compiler {

    enum class Where { TYPED, QUERY, PICK, ANCHOR, LABEL }

    /** A step value that matches words [from, to] of the command. */
    data class Hit(val step: Int, val where: Where, val value: String, val from: Int, val to: Int)

    /** A blank: command words [from, to], and every place in the steps that uses it. */
    data class Blank(val from: Int, val to: Int, val value: String, val hits: List<Hit>, var name: String = "")

    data class Result(val recipe: Recipe, val removed: List<String>, val questions: List<String>)

    private val STOP = setOf("a", "an", "the", "on", "from", "in", "at", "to", "for", "of", "me", "my", "and", "please",
        "order", "get", "buy", "search", "find", "add", "open", "show", "book", "some", "one", "with", "using", "via",
        // app furniture, not things the user would change
        "cart", "basket", "bag", "checkout", "app", "page", "screen", "button", "list", "wishlist", "account")

    fun words(utterance: String): List<String> = utterance.split(Regex("\\s+")).map { it.trim(',', '.', '!', '?') }.filter { it.isNotEmpty() }

    /**
     * The span (≤ 4 words, no stop/app words) whose words all appear in [value], covering the most
     * letters: for "Domino's Pizza" in "…Margherita pizza from Domino's…", "Domino's" (7) beats "pizza" (5).
     */
    fun match(value: String, words: List<String>, appWords: Set<String>): Pair<Int, Int>? {
        val v = loose(value).takeIf { it.length >= 2 } ?: return null
        // Two letters typed ("ma" in Domino's menu search, 30 Sep) are that word only if exactly one spoken word starts so.
        if (v.length == 2) return words.indices.filter { i ->
            words[i].lowercase() !in STOP && loose(words[i]) !in appWords && loose(words[i]).startsWith(v)
        }.singleOrNull()?.let { it to it }
        var best: Pair<Int, Int>? = null
        var bestLen = 0
        for (i in words.indices) for (j in i until minOf(words.size, i + 4)) {
            val span = words.subList(i, j + 1)
            // A blank never spans "from"/"on"/the app name, and every word of it must come from the value
            // ("Margherita" for typed "margherita", not "Margherita pizza"). A lone word may also be typed as a prefix.
            if (span.any { it.lowercase() in STOP || loose(it) in appWords }) continue
            val s = loose(span.joinToString(" ")).takeIf { it.length >= 3 } ?: continue
            // …or typed half-way with a typo and picked from the list ("margerit" for Margherita, 29 Sep).
            val fits = span.all { v.contains(loose(it)) } || (span.size == 1 && s.startsWith(v)) ||
                (span.size == 1 && v.length >= 5 && s.length >= v.length && com.example.myna_mimicyourinteractionsautomate.intent.SpeechFix.jaroWinkler(v, s.take(v.length)) >= 0.9)
            if (fits && s.length > bestLen) { best = i to j; bestLen = s.length }
        }
        return best
    }

    fun findBlanks(rec: Recording): List<Blank> {
        val w = words(rec.utterance)
        // "Amazon" in in.amazon.mShop.android.shopping is the app, not a blank.
        val appWords = rec.app.split('.').map(::loose).filter { it.length >= 3 && it != "android" && it != "com" }.toSet()
        val hits = mutableListOf<Hit>()
        fun hit(step: Int, where: Where, value: String?) {
            value ?: return
            match(value, w, appWords)?.let { (a, b) -> hits += Hit(step, where, value, a, b) }
        }
        /** Typed text can hold several spoken parts ("s25ultra phone cases" = "s25-ultra" + "phone case"): take each. */
        fun hitAll(step: Int, where: Where, value: String?) {
            value ?: return
            var rest: String = value
            while (true) {
                val (a, b) = match(rest, w, appWords) ?: break
                if (hits.any { it.step == step && it.where == where && it.from <= b && a <= it.to }) break
                hits += Hit(step, where, value, a, b)
                rest = removeLoose(rest, w.subList(a, b + 1).joinToString(" ")) ?: break
            }
        }
        rec.steps.forEachIndexed { i, s ->
            if (s.noise) return@forEachIndexed
            when (s.type) {
                StepType.TYPE -> hitAll(i, Where.TYPED, s.text)
                StepType.GOAL -> if (s.goal == "search") { hitAll(i, Where.QUERY, s.text); hit(i, Where.PICK, s.args["pick"]) }
                StepType.TAP -> {
                    val t = s.target ?: return@forEachIndexed
                    val label = t.label ?: t.key?.takeIf { it.by in setOf(KeyKind.LABEL, KeyKind.CHILD_TEXT, KeyKind.OCR) }?.value
                    if (label != null && match(label, w, appWords) != null) hit(i, Where.LABEL, label)
                    else t.nearby.firstOrNull { match(it, w, appWords) != null }?.let { hit(i, Where.ANCHOR, it) }
                }
                else -> {}
            }
        }
        // Overlapping spans are one blank ("margherita" typed + "Margherita Pizza" card = {item});
        // its value is the words every use shares.
        val groups = mutableListOf<MutableList<Hit>>()
        hits.sortedBy { it.from }.forEach { h ->
            groups.firstOrNull { g -> g.any { it.from <= h.to && h.from <= it.to } }?.add(h) ?: groups.add(mutableListOf(h))
        }
        return groups.map { g ->
            val from = g.maxOf { it.from }; val to = g.minOf { it.to }
            val (a, b) = if (from <= to) from to to else g.first().from to g.first().to
            Blank(a, b, w.subList(a, b + 1).joinToString(" "), g)
        }
    }

    /** Fallback names when the AI isn't available: searched → restaurant/query, picked on a page → item. */
    private fun defaultName(b: Blank, taken: Set<String>): String {
        val base = when {
            b.hits.any { it.where == Where.QUERY || it.where == Where.PICK } -> "query"
            else -> "item"
        }
        return generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base$it" }.first { it !in taken }
    }

    /** Regex matching [spoken] inside typed text, ignoring case, spaces and punctuation ("s25-ultra" ~ "S25 Ultra" ~ "s25ultra"). */
    private fun looseRegex(spoken: String): Regex? = loose(spoken).takeIf { it.isNotEmpty() }
        ?.map { Regex.escape(it.toString()) }?.joinToString("[^\\p{L}\\p{N}]*")?.let { Regex(it, RegexOption.IGNORE_CASE) }

    /** [text] without the part where [spoken] was typed (also when typed shorter: "nothing 3" for "nothing phone 3"). */
    private fun removeLoose(text: String, spoken: String): String? =
        typedPart(text, spoken)?.let { text.removeRange(it).trim() }?.takeIf { it.length >= 3 }

    /**
     * "s25ultra phone cases" with product = "phone case" → "s25ultra {product}s". When the typed text is shorter than
     * what was said ("nothing3a" for "nothing phone 3a"), only the typed words made of the spoken words are replaced:
     * "phone case nothing3a" → "phone case {item}". Whole text only if no typed word comes from the blank at all.
     */
    fun template(text: String, spoken: String, ref: String): String =
        typedPart(text, spoken)?.let { text.replaceRange(it, ref) } ?: ref

    /** Where [spoken] was typed in [text]: in one piece, else the run of typed words spelled by the spoken words in order. */
    fun typedPart(text: String, spoken: String): IntRange? {
        looseRegex(spoken)?.find(text)?.let { return it.range }
        val said = words(spoken).map(::loose).filter { it.isNotEmpty() }
        val tokens = Regex("\\S+").findAll(text).toList()
        var best: IntRange? = null
        var bestUsed = 0
        for (i in tokens.indices) for (j in i until tokens.size) {
            val used = usedWords(loose(tokens.subList(i, j + 1).joinToString("") { it.value }), said) ?: continue
            if (used > bestUsed) { best = tokens[i].range.first..tokens[j].range.last; bestUsed = used }
        }
        // Typed longer than said ("nothing phone 3a" for "nothing 3a"): a run that starts and ends with spoken words,
        // with at most 2 extra typed words inside, is still that blank — else the extras stay behind in every search.
        if (bestUsed < said.size) for (i in tokens.indices) for (j in i + 1 until tokens.size) {
            var from = 0; var used = 0; var extra = 0
            for (x in i..j) {
                val hit = (from until said.size).firstOrNull { loose(tokens[x].value) == said[it] }
                if (hit != null) { used++; from = hit + 1 } else if (x == i || x == j) { used = -1; break } else extra++
            }
            if (used > bestUsed && extra <= 2) { best = tokens[i].range.first..tokens[j].range.last; bestUsed = used }
        }
        return best
    }

    /** How many of [said] (in order, some may be skipped) spell exactly [s]; null if they can't. */
    private fun usedWords(s: String, said: List<String>, from: Int = 0): Int? {
        if (s.isEmpty()) return 0
        return (from until said.size).mapNotNull { k ->
            if (s.startsWith(said[k])) usedWords(s.removePrefix(said[k]), said, k + 1)?.plus(1) else null
        }.maxOrNull()
    }

    private val OPEN_CART = Regex("^(go to |view |my |your |shopping )?(cart|basket|bag)\\b", RegexOption.IGNORE_CASE)

    /**
     * The demo's last step opened the cart: the task is "put it in the cart", so replay ends there. Its cart may show a
     * payment window (the gate hands off) or a "Proceed to checkout" button (30 Sep, Amazon) — never tap past it.
     */
    fun endsByOpeningCart(steps: List<Step>): Boolean = steps.lastOrNull { !it.noise }?.let { s ->
        s.type == StepType.TAP && listOfNotNull(s.target?.label, s.target?.key?.value).any { OPEN_CART.containsMatchIn(it) }
    } == true

    /** Rewrite the steps to use "{name}" wherever the blank's value was used. */
    fun applyBlanks(steps: List<Step>, blanks: List<Blank>): List<Step> {
        val out = steps.toMutableList()
        for (b in blanks) for (h in b.hits) {
            val s = out[h.step]; val ref = "{${b.name}}"
            out[h.step] = when (h.where) {
                Where.TYPED, Where.QUERY -> s.copy(text = template(s.text ?: "", b.value, ref))
                Where.PICK -> s.copy(args = s.args + ("pick" to ref))
                Where.ANCHOR -> s.copy(target = s.target!!.copy(key = UniqueKey(KeyKind.NEAR_TEXT, ref, loose = true)))
                Where.LABEL -> s.copy(target = s.target!!.copy(label = ref, key = UniqueKey(KeyKind.LABEL, ref, loose = true)))
            }
        }
        return out
    }

    /**
     * A tap right after a submitted search is "open the result that matches the search", not "tap this exact
     * product": with {product} = laptop stand the demo's phone case won't be in the results (T9).
     */
    fun resultPicks(steps: List<Step>, original: List<Step> = steps): List<Step> = steps.mapIndexed { i, s ->
        val before = steps.subList(0, i).lastOrNull { !it.noise }
        val afterSearch = before != null && ((before.type == StepType.TYPE && before.submit) ||
            (before.goal == "search" && before.args["pick"] == null))
        val t0 = original[i].target   // before blanks rewrote the label
        val looksLikeResult = (t0?.label ?: t0?.key?.value).orEmpty().length >= 12   // a title, not the "Cart" tab
        if (s.type == StepType.TAP && !s.noise && afterSearch && looksLikeResult && before!!.text != null)
            Step(StepType.GOAL, goal = "pick_result", args = mapOf("query" to before.text!!), target = s.target, screen = s.screen,
                why = "open the result that best matches the search")
        else s
    }

    private val SCHEMA = JSONObject("""
    {"title": "compile", "type": "object",
     "required": ["summary", "names", "paraphrases", "steps", "subtasks", "noise", "questions"],
     "properties": {
       "summary": {"type": "string"},
       "names": {"type": "array", "items": {"type": "string"}},
       "paraphrases": {"type": "array", "items": {"type": "string"}},
       "steps": {"type": "array", "items": {"type": "object", "required": ["i", "why", "screen"],
                 "properties": {"i": {"type": "integer"}, "why": {"type": "string"}, "screen": {"type": "string"}}}},
       "subtasks": {"type": "array", "items": {"type": "object", "required": ["name", "why", "from", "to"],
                 "properties": {"name": {"type": "string"}, "why": {"type": "string"}, "from": {"type": "integer"}, "to": {"type": "integer"}}}},
       "noise": {"type": "array", "items": {"type": "object", "required": ["i", "reason"],
                 "properties": {"i": {"type": "integer"}, "reason": {"type": "string"}}}},
       "questions": {"type": "array", "items": {"type": "string"}}
     }}""")

    /** Masked (phone numbers, pincodes, e-mails) before it leaves the phone, like every cloud call (design §4.6). */
    internal fun prompt(rec: Recording, steps: List<Step>, blanks: List<Blank>) = Privacy.mask(buildString {
        appendLine("You compile a phone automation that a user taught by voice + taps.")
        appendLine("App: ${rec.app}")
        appendLine("Spoken command: \"${rec.utterance}\"")
        appendLine("Recorded steps (index: action [screen]):")
        steps.forEachIndexed { i, s ->
            val t = s.target
            appendLine("$i: ${s.describe()}" + (t?.nearby?.takeIf { it.isNotEmpty() }?.let { " | nearby: ${it.take(3)}" } ?: "") +
                (s.screen?.title?.let { " [$it]" } ?: ""))
        }
        appendLine("Blanks found (command words the user can change next time):")
        blanks.forEachIndexed { i, b -> appendLine("$i: \"${b.value}\" used in steps ${b.hits.map { it.step }.distinct()}") }
        appendLine()
        appendLine("Return JSON:")
        appendLine("- names: one short snake_case name per blank, same order. Prefer: item, restaurant, query, product, qty, address, size.")
        appendLine("- summary: the command with blanks as {name}, e.g. \"order {item} from {restaurant} on Zomato\".")
        appendLine("- paraphrases: 6-8 other ways a person in India might say the SAME command with the SAME values.")
        appendLine("- steps: for every step index, why (one short sentence) and screen (2-4 word note of the screen, e.g. \"Domino's menu\").")
        appendLine("- subtasks: contiguous groups covering all steps in order, e.g. open_app, search_restaurant, add_item, checkout.")
        appendLine("- noise: steps that are clearly not part of the task (dismissing leftovers, mis-taps), with a reason. Be conservative.")
        appendLine("- questions: only if the user SAID one thing but TAPPED a different one (e.g. said Margherita, added Farmhouse). Else empty.")
    })

    suspend fun compile(rec: Recording, golden: Boolean = false): Result {
        val steps = rec.steps
        val blanks = findBlanks(rec)
        // Messaging apps: chat names and previews stay on the phone (offline naming, no AI call).
        val ai = if (com.example.myna_mimicyourinteractionsautomate.replay.Privacy.isPrivate(rec.app)) null
            else runCatching { Llm.llm(prompt(rec, steps, blanks), SCHEMA) }.getOrNull()

        val names = ai?.optJSONArray("names")
        val taken = mutableSetOf<String>()
        blanks.forEachIndexed { i, b ->
            val n = names?.optString(i)?.lowercase()?.replace(Regex("[^a-z0-9_]"), "")?.takeIf { it.isNotEmpty() && it !in taken }
            b.name = n ?: defaultName(b, taken)
            taken += b.name
        }

        val out = resultPicks(applyBlanks(steps, blanks), steps).toMutableList()
        ai?.optJSONArray("steps")?.forEachObj { o ->
            val i = o.optInt("i", -1)
            if (i in out.indices) out[i] = out[i].copy(why = o.optString("why"),
                screen = out[i].screen?.copy(note = o.optString("screen").takeIf { it.isNotBlank() }))
        }
        val removed = mutableListOf<String>()
        out.forEachIndexed { i, s -> if (s.noise) removed += "${s.describe()} (${s.why.ifBlank { "mistake" }})" }
        ai?.optJSONArray("noise")?.forEachObj { o ->
            val i = o.optInt("i", -1)
            // Never drop a step that uses a blank: that's the heart of the task.
            if (i in out.indices && !out[i].noise && blanks.none { b -> b.hits.any { it.step == i } }) {
                out[i] = out[i].copy(noise = true, why = o.optString("reason"))
                removed += "${out[i].describe()} (${o.optString("reason")})"
            }
        }

        val subtasks = ai?.optJSONArray("subtasks")?.let { groups(it, out) } ?: listOf(Subtask("demo", steps = out))
        val slots = blanks.associate { b ->
            b.name to Slot(value = b.value, neighbours = b.hits.flatMap { h -> steps[h.step].target?.neighbours.orEmpty() }.distinct())
        }
        val defaults = out.filter { it.goal == "confirm_sheet" }.mapNotNull { it.args["choices"] }
            .flatMap { it.split(Recorder.CHOICE_SEP) }.associateBy { "option_${loose(it)}" }
        val recipe = Recipe(
            id = "${rec.app.substringAfterLast('.')}_${rec.startedAt}",
            app = rec.app,
            utterance = rec.utterance,
            summary = ai?.optString("summary")?.takeIf { it.isNotBlank() } ?: fallbackSummary(rec.utterance, blanks),
            paraphrases = ai?.optJSONArray("paraphrases")?.strings().orEmpty(),
            slots = slots,
            defaults = defaults,
            subtasks = subtasks,
            end = if (rec.stoppedBy == "safety_gate" && !endsByOpeningCart(out)) End.PAYMENT_SCREEN else End.LAST_SCREEN,
            golden = golden,
        )
        return Result(recipe, removed, ai?.optJSONArray("questions")?.strings().orEmpty())
    }

    private fun fallbackSummary(utterance: String, blanks: List<Blank>): String {
        val w = words(utterance).toMutableList()
        blanks.sortedByDescending { it.from }.forEach { b -> repeat(b.to - b.from + 1) { w.removeAt(b.from) }; w.add(b.from, "{${b.name}}") }
        return w.joinToString(" ")
    }

    /** AI groups → sub-tasks, only if they cover every step once, in order; else one "demo" sub-task. */
    private fun groups(arr: JSONArray, steps: List<Step>): List<Subtask>? {
        val out = mutableListOf<Subtask>()
        var next = 0
        arr.forEachObj { o ->
            val from = o.optInt("from", -1); val to = o.optInt("to", -1)
            if (from != next || to < from || to >= steps.size) return null
            out += Subtask(o.optString("name").ifBlank { "part${out.size + 1}" }, o.optString("why"), steps.subList(from, to + 1))
            next = to + 1
        }
        return out.takeIf { next == steps.size }
    }

    private inline fun JSONArray.forEachObj(f: (JSONObject) -> Unit) { for (i in 0 until length()) optJSONObject(i)?.let(f) }
    private fun JSONArray.strings() = (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotBlank() } }
}
