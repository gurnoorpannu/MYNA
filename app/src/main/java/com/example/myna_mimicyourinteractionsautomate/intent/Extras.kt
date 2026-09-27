package com.example.myna_mimicyourinteractionsautomate.intent

/**
 * Things a command can ask for that were never taught step by step (design §4.5, T5/T6):
 * a quantity ("2 margheritas", "two of them") and a delivery address ("deliver to Work").
 * Parsed without AI; [rest] is the command with them removed, for blank filling.
 */
object Extras {

    data class Parsed(val qty: Int?, val address: String?, val rest: String)

    private val NUMBER_WORDS = mapOf("one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7,
        "eight" to 8, "nine" to 9, "ten" to 10, "a couple of" to 2, "couple of" to 2)   // not "double": "Double Cheese Burst"

    private val ADDRESS = listOf(
        Regex("\\b(?:deliver(?:ed)?|send|ship|bring)(?:\\s+(?:it|them|this))?\\s+to\\s+(?:my\\s+|the\\s+)?(home|work|office|[a-z]+(?:'s)? (?:place|house))\\b", RegexOption.IGNORE_CASE),
        Regex("\\bto\\s+(?:my\\s+)?(home|work|office)(?:\\s+address)?\\b", RegexOption.IGNORE_CASE),
        Regex("\\bat\\s+(?:my\\s+)?(home|work|office)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(home|work|office)\\s+address\\b", RegexOption.IGNORE_CASE),
    )

    fun parse(utterance: String): Parsed {
        var rest = utterance
        var address: String? = null
        for (re in ADDRESS) {
            val m = re.find(rest) ?: continue
            address = m.groupValues[1].replaceFirstChar { it.uppercase() }
            rest = rest.removeRange(m.range)
            break
        }
        var qty: Int? = null
        // A standalone count before a word ("2 margheritas", "two farmhouse"), or "x2" / "2x". Never inside "s25".
        val num = Regex("(?<![\\p{L}\\p{N}])(\\d{1,2})\\s*x?(?=\\s+\\p{L})|\\bx\\s*(\\d{1,2})\\b|\\b(\\d{1,2})\\s*x\\b", RegexOption.IGNORE_CASE)
        num.find(rest)?.let { m ->
            val n = (m.groupValues[1].ifEmpty { m.groupValues[2] }.ifEmpty { m.groupValues[3] }).toIntOrNull()
            if (n != null && n in 1..20) { qty = n; rest = singularFrom(rest.removeRange(m.range), m.range.first) }
        }
        if (qty == null) NUMBER_WORDS.entries.sortedByDescending { it.key.length }.firstOrNull { (w, _) ->
            Regex("\\b$w\\b", RegexOption.IGNORE_CASE).containsMatchIn(rest)
        }?.let { (w, n) ->
            val at = Regex("\\b$w\\b", RegexOption.IGNORE_CASE).find(rest)!!.range.first
            qty = n; rest = singularFrom(rest.replace(Regex("\\b$w\\b", RegexOption.IGNORE_CASE), " "), at)
        }
        return Parsed(qty, address, rest.replace(Regex("\\s{2,}"), " ").trim())
    }

    /**
     * The counted words go back to one: "2 margheritas from dominos" → "margherita from dominos".
     * Menus list "Margherita"; searching "margheritas" found other dishes (27 Sep). Stops at from/on/to… so "Dominos" stays.
     */
    private fun singularFrom(s: String, at: Int): String {
        val head = s.substring(0, at.coerceAtMost(s.length))
        val tail = s.substring(head.length)
        val stop = Regex("\\b(from|on|at|to|for|in|and|with|via)\\b", RegexOption.IGNORE_CASE).find(tail)?.range?.first ?: tail.length
        return head + tail.substring(0, stop).replace(Regex("\\p{L}+")) { singular(it.value) } + tail.substring(stop)
    }

    /** "margheritas" → "margherita" for matching what's on screen. */
    fun singular(s: String): String = when {
        s.length > 4 && s.endsWith("ies") -> s.dropLast(3) + "y"
        s.length > 4 && s.endsWith("es") && s.dropLast(2).last() in "sxz" -> s.dropLast(2)
        s.length > 3 && s.endsWith("s") && !s.endsWith("ss") -> s.dropLast(1)
        else -> s
    }
}
