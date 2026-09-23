package com.example.myna_mimicyourinteractionsautomate.screen

import com.example.myna_mimicyourinteractionsautomate.recipe.KeyKind
import com.example.myna_mimicyourinteractionsautomate.recipe.Screen
import com.example.myna_mimicyourinteractionsautomate.recipe.Target
import com.example.myna_mimicyourinteractionsautomate.recipe.UniqueKey

/** Element identity (design §4.1) and screen signatures. Pure functions over [UiNode]. */
object Identity {

    /**
     * [spoken] = words of the teach command; an anchor containing one ("Margherita Pizza" for "…margherita…")
     * wins over other unique card texts ("In Recommended for you").
     */
    fun target(n: UiNode, root: UiNode, spoken: Set<String> = emptySet()): Target {
        // An edit field's text is what the user typed, not its name.
        val label = if (n.editable) (n.hint ?: n.desc)?.takeIf { it.isNotBlank() } else n.label
        val subTexts = n.walk().drop(1).mapNotNull { it.label }.filter { it != label }.distinct().take(6).toList()
        val anchor = anchor(n)
        val nearby = anchor.walk().filter { it !in n.walk() }.mapNotNull { it.label }.distinct().take(6).toList()
        return Target(
            label = label,
            subTexts = subTexts,
            id = n.id,
            cls = n.cls,
            index = n.index,
            nearby = nearby,
            neighbours = neighbours(n),
            key = uniqueKey(n, label, subTexts, anchor, root, spoken),
            bounds = n.bounds,
        )
    }

    /** SUGILITE-style: the cheapest thing that is unique on this screen. */
    private fun uniqueKey(n: UiNode, label: String?, subTexts: List<String>, anchor: UiNode, root: UiNode, spoken: Set<String>): UniqueKey {
        val visible = root.walk().filter { it.visible }.toList()
        val labelCount = visible.mapNotNull { it.label?.let(::norm) }.groupingBy { it }.eachCount()
        val unique = { s: String -> labelCount[norm(s)] == 1 }
        // A card may repeat its own title (image desc + text): unique = appears nowhere outside the card.
        val inAnchor = anchor.walk().toSet()
        val onlyInAnchor = { s: String -> visible.none { it !in inAnchor && it.label?.let(::norm) == norm(s) } }
        val nearby = anchor.walk().filter { it !in n.walk() }.mapNotNull { it.label }.distinct()
            .filter { isStable(it) && onlyInAnchor(it) }
            .sortedByDescending { t -> spoken.any { w -> t.contains(w, ignoreCase = true) } }
        return when {
            label != null && !n.editable && unique(label) -> UniqueKey(KeyKind.LABEL, norm(label))
            n.id != null && isStableId(n.id) && visible.count { it.id == n.id } == 1 -> UniqueKey(KeyKind.ID, n.id)
            else -> subTexts.firstOrNull { unique(it) && isStable(it) }?.let { UniqueKey(KeyKind.CHILD_TEXT, norm(it)) }
                // "Add" on the Margherita card: anchor on the card's unique text.
                ?: nearby.firstOrNull()?.let { UniqueKey(KeyKind.NEAR_TEXT, norm(it)) }
                ?: UniqueKey(KeyKind.POSITION, n.bounds.joinToString(","))
        }
    }

    /**
     * Label as used for matching: rotating quoted parts dropped
     * (Zomato's search bar: `Search "homestyle meals"` today, `Search "biryani"` tomorrow → `Search`).
     */
    fun norm(s: String) = s.replace(Regex("[\"“”][^\"“”]*[\"“”]"), "").replace(Regex("\\s+"), " ").trim()

    /** Webview ids like "pp-mDxkUF-246" are regenerated every load. */
    fun isStableId(id: String) = !Regex("\\d{3,}").containsMatchIn(id)

    /** The row/card the node lives in: the child of the nearest scrollable list, else a few parents up. */
    fun listItem(n: UiNode): UiNode? =
        (sequenceOf(n) + n.ancestors()).firstOrNull { it.parent?.scrollable == true }

    private fun anchor(n: UiNode): UiNode = listItem(n) ?: n.ancestors().take(2).lastOrNull() ?: n

    /** Primary texts of the other rows in the same list — candidate values for a blank. */
    fun neighbours(n: UiNode): List<String> {
        val item = listItem(n) ?: return emptyList()
        return item.parent!!.children.filter { it !== item }.mapNotNull(::primaryText).distinct().take(15)
    }

    /** A card's title: its most repeated stable text (Zomato repeats the dish name as image desc), else the first. */
    fun primaryText(n: UiNode): String? {
        val texts = n.walk().mapNotNull { it.label }.filter(::isStable).toList()
        val counts = texts.groupingBy { it }.eachCount()
        return texts.maxByOrNull { counts[it]!! }   // maxBy keeps the first on ties
    }

    /** Not a price, count or time: those change between runs. */
    fun isStable(s: String) = s.length in 2..60 && s.count(Char::isDigit) <= 1

    fun screen(root: UiNode, pkg: String, activity: String?): Screen {
        val topEdge = root.t + (root.b - root.t) / 4
        // Top-of-screen chrome only: list contents scroll and change between runs.
        val topTexts = root.walk().filter { it.visible && it.t < topEdge && !it.editable && it.ancestors().none { a -> a.scrollable } }
            .mapNotNull { it.label }.filter(::isStable).distinct().take(3).toList()
        val title = activity?.substringAfterLast('.')
        return Screen(
            signature = (listOf(pkg, title ?: "") + topTexts).joinToString("|"),
            pkg = pkg,
            title = title,
            lang = lang(root),
        )
    }

    /**
     * Some apps (Zomato's search suggestions) navigate on touch without sending a click event.
     * When the screen changes with no recorded step, guess the tap: the one clickable on [prev]
     * whose text shows up on [next]. Null unless exactly one candidate — a wrong guess is worse than none.
     */
    fun inferTap(prev: UiNode, next: UiNode): UiNode? {
        val nextTexts = next.walk().filter { it.visible }.mapNotNull { it.label?.let(::norm) }.toSet()
        val prevTexts = prev.walk().filter { it.visible }.mapNotNull { it.label?.let(::norm) }.toSet()
        // Only rows of a scrollable list (suggestions, results): chrome like "Back" lives on both screens.
        val hits = prev.walk().filter { it.visible && it.clickable && !it.editable && listItem(it) != null }
            .mapNotNull { n -> primaryText(n)?.let(::norm)?.let { t -> n to t } }
            .filter { (_, t) -> t.length >= 3 && t in nextTexts }
            .toList()
        // A text repeated across many rows ("Restaurant") proves nothing; keep ones specific to one row.
        val specific = hits.filter { (_, t) -> prev.walk().count { it.visible && it.label?.let(::norm) == t } <= 2 }
        val best = specific.distinctBy { it.second }.maxByOrNull { it.second.length } ?: return null
        return best.first.takeIf { specific.count { it.second == best.second } == 1 && prevTexts.isNotEmpty() }
    }

    /** "hi" when ≥20% of letters on screen are Devanagari (dish/brand names often stay English), else "en". */
    fun lang(root: UiNode): String {
        var letters = 0; var deva = 0
        root.walk().mapNotNull { it.label }.forEach { s ->
            s.forEach { c -> if (c.isLetter()) { letters++; if (c in 'ऀ'..'ॿ') deva++ } }
        }
        return if (letters > 0 && deva * 5 >= letters) "hi" else "en"
    }

    /** One line per meaningful node — stored with the recording for the compiler/AI. */
    fun compact(root: UiNode, maxLines: Int = 200): String = buildString {
        var lines = 0
        fun go(n: UiNode, depth: Int) {
            if (lines >= maxLines || !n.visible) return
            val show = n.label != null || n.clickable || n.editable || n.scrollable
            if (show) {
                append("  ".repeat(depth)).append(n.cls ?: "?")
                n.label?.let { append(" \"").append(it.take(60)).append('"') }
                n.hint?.let { append(" hint=\"").append(it).append('"') }
                n.id?.let { append(" #").append(it) }
                if (n.clickable) append(" [tap]")
                if (n.editable) append(" [edit]")
                if (n.scrollable) append(" [scroll]")
                append('\n'); lines++
            }
            n.children.forEach { go(it, if (show) depth + 1 else depth) }
        }
        go(root, 0)
    }
}
