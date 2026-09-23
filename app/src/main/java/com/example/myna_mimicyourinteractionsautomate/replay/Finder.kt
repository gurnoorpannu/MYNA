package com.example.myna_mimicyourinteractionsautomate.replay

import com.example.myna_mimicyourinteractionsautomate.recipe.KeyKind
import com.example.myna_mimicyourinteractionsautomate.recipe.Target
import com.example.myna_mimicyourinteractionsautomate.screen.Identity
import com.example.myna_mimicyourinteractionsautomate.screen.Identity.norm
import com.example.myna_mimicyourinteractionsautomate.screen.UiNode
import kotlin.math.abs
import kotlin.math.max

/**
 * Three-level finder (design §4.5). Pure: works on a [UiNode] snapshot.
 *  1. the recorded unique key (label / id / child text / card anchor)
 *  2. the same element by any other attribute (label, id+text, sub-texts)
 *  3. fuzzy text match, similarity ≥ [FUZZY]
 * Scrolling, pop-ups and OCR are the executor's job (they need the device).
 */
object Finder {

    const val FUZZY = 0.8

    data class Found(val node: UiNode, val level: Int, val how: String, val candidates: Int = 1)

    /** [target] must already have slots filled in (see [Slots.bind]). */
    fun find(root: UiNode, target: Target): Found? =
        byKey(root, target) ?: sameElement(root, target) ?: fuzzy(root, target)

    private fun visible(root: UiNode) = root.walk().filter { it.visible }

    private fun byKey(root: UiNode, t: Target): Found? {
        val key = t.key ?: return null
        val hits: List<UiNode> = when (key.by) {
            KeyKind.LABEL -> visible(root).filter { it.label?.let(::norm) == key.value }.toList()
            KeyKind.ID -> visible(root).filter { it.id == key.value }.toList()
            KeyKind.CHILD_TEXT -> visible(root).filter { it.clickable && it.walk().any { c -> c.label?.let(::norm) == key.value } }.toList()
                .let { l -> l.filter { n -> l.none { o -> o !== n && o in n.walk().drop(1) } } }   // innermost only
            KeyKind.NEAR_TEXT -> nearText(root, t, key.value)
            KeyKind.OCR -> visible(root).filter { it.label?.let(::norm) == key.value }.toList()
            KeyKind.POSITION -> emptyList()
        }
        return best(hits, t)?.let { Found(tappable(it), 1, "${key.by.name.lowercase()}=${key.value}", hits.size) }
    }

    /** "Add" on the card whose text is [anchor]: find the anchor, then the element on the same card. */
    private fun nearText(root: UiNode, t: Target, anchor: String): List<UiNode> =
        visible(root).filter { it.label?.let(::norm) == anchor }.flatMap { a ->
            val card = Identity.listItem(a) ?: a.ancestors().take(3).lastOrNull() ?: return@flatMap emptyList()
            card.walk().filter { it !== a && it.visible && sameKind(it, t) }.toList()
        }.toList()

    private fun sameElement(root: UiNode, t: Target): Found? {
        // A card-anchored tap ("Add" next to Margherita) IS its card: any other "Add" is the wrong item.
        if (t.key?.by == KeyKind.NEAR_TEXT) return null
        val hits = visible(root).filter { n ->
            (t.label != null && !n.editable && n.label?.let(::norm) == t.label.let(::norm)) ||
                (t.id != null && Identity.isStableId(t.id) && n.id == t.id && (t.label == null || n.label == null || n.label == t.label)) ||
                (t.subTexts.isNotEmpty() && n.clickable && t.subTexts.filter(Identity::isStable).let { s -> s.isNotEmpty() && s.all { x -> n.walk().any { c -> c.label == x } } })
        }.toList()
        return best(hits, t)?.let { Found(tappable(it), 2, "same element", hits.size) }
    }

    private fun fuzzy(root: UiNode, t: Target): Found? {
        val want = (t.key?.takeIf { it.by != KeyKind.ID && it.by != KeyKind.POSITION }?.value ?: t.label)?.let(::norm) ?: return null
        val scored = visible(root).mapNotNull { n -> n.label?.let { n to similarity(norm(it), want) } }
            .filter { it.second >= FUZZY }.sortedByDescending { it.second }.toList()
        val top = scored.firstOrNull() ?: return null
        val node = if (t.key?.by == KeyKind.NEAR_TEXT) nearText(root, t, norm(top.first.label!!)).firstOrNull() ?: return null else top.first
        return Found(tappable(node), 3, "fuzzy ${"%.2f".format(top.second)} \"${top.first.label}\"", scored.size)
    }

    /** Several matches: prefer same id/class, then nearest to where it was in the demo. */
    private fun best(hits: List<UiNode>, t: Target): UiNode? {
        if (hits.size <= 1) return hits.firstOrNull()
        val b = t.bounds
        return hits.sortedWith(compareBy<UiNode>(
            { if (it.id == t.id) 0 else 1 },
            { if (it.cls == t.cls) 0 else 1 },
            { if (b == null) 0 else abs(it.t - b[1]) + abs(it.l - b[0]) },
        )).first()
    }

    private fun sameKind(n: UiNode, t: Target) =
        (t.id != null && n.id == t.id) || (t.id == null && t.label != null && n.label == t.label) ||
            (t.id == null && t.label == null && n.clickable && n.cls == t.cls)

    /** Tap the node itself if clickable, else its nearest clickable ancestor (Zomato labels sit inside clickable rows). */
    fun tappable(n: UiNode): UiNode = (sequenceOf(n) + n.ancestors()).firstOrNull { it.clickable } ?: n

    /** Pop-up rule (T7): only close-type buttons. Never the pop-up's main action. */
    private val CLOSE = Regex("^(x|×|✕|✖|close|dismiss|not now|skip|no thanks|no, thanks|maybe later|later|cancel)$", RegexOption.IGNORE_CASE)

    fun closeButton(root: UiNode): UiNode? = visible(root).firstOrNull { n ->
        n.clickable && (n.label?.let { CLOSE.matches(it) } == true ||
            // Not "cross"/"cancel" ids: Zomato's iconCross clears the search text.
            n.id?.let { Regex("close|dismiss", RegexOption.IGNORE_CASE).containsMatchIn(it) } == true)
    }

    fun similarity(a: String, b: String): Double {
        val x = a.lowercase(); val y = b.lowercase()
        if (x == y) return 1.0
        val m = max(x.length, y.length)
        return if (m == 0) 1.0 else 1.0 - levenshtein(x, y).toDouble() / m
    }

    private fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1).also { it[0] = i }
            for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[b.length]
    }
}

/** Fill "{slot}" references in a target/text with run-time values ("{item}" → "Farmhouse"). */
object Slots {
    private val REF = Regex("\\{(\\w+)\\}")

    fun fill(s: String?, values: Map<String, String>): String? = s?.let { REF.replace(it) { m -> values[m.groupValues[1]] ?: m.value } }

    fun bind(t: Target, values: Map<String, String>): Target = if (values.isEmpty()) t else t.copy(
        label = fill(t.label, values),
        subTexts = t.subTexts.map { fill(it, values)!! },
        nearby = t.nearby.map { fill(it, values)!! },
        key = t.key?.let { it.copy(value = fill(it.value, values)!!) },
    )
}
