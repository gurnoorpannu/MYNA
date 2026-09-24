package com.example.myna_mimicyourinteractionsautomate.replay

/** Privacy mask before any cloud call (design §4.6): phone numbers, pincodes, e-mails, card-like digits. */
object Privacy {
    private val EMAIL = Regex("[\\w.+-]+@[\\w-]+\\.[\\w.]+")
    private val LONG_DIGITS = Regex("(?<!\\w)(\\+?\\d[\\d -]{4,}\\d)(?!\\w)")

    fun mask(s: String): String = s.replace(EMAIL, "<email>").replace(LONG_DIGITS, "<number>")
}
