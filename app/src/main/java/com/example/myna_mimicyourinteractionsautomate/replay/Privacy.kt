package com.example.myna_mimicyourinteractionsautomate.replay

import com.example.myna_mimicyourinteractionsautomate.recipe.Target

/** Privacy mask before any cloud call (design §4.6): phone numbers, pincodes, e-mails, card-like digits. */
object Privacy {
    /**
     * Messaging apps show personal chats: while teaching them, no screen text is stored and no AI call is made
     * (the offline compiler names the blanks instead).
     */
    val PRIVATE_APPS = setOf("com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger",
        "com.google.android.apps.messaging", "com.samsung.android.messaging")

    fun isPrivate(pkg: String?) = pkg in PRIVATE_APPS

    /** Messaging apps: keep only the text that finds the tapped element (label / unique key), never the messages around it. */
    fun scrub(t: Target): Target {
        val keep = setOfNotNull(t.label, t.key?.value)
        return t.copy(subTexts = t.subTexts.filter { it in keep }, nearby = t.nearby.filter { it in keep }, neighbours = emptyList())
    }

    private val EMAIL = Regex("[\\w.+-]+@[\\w-]+\\.[\\w.]+")
    private val LONG_DIGITS = Regex("(?<!\\w)(\\+?\\d[\\d -]{4,}\\d)(?!\\w)")

    fun mask(s: String): String = s.replace(EMAIL, "<email>").replace(LONG_DIGITS, "<number>")
}
