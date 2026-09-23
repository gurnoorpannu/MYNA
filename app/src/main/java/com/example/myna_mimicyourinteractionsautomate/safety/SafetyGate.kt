package com.example.myna_mimicyourinteractionsautomate.safety

import com.example.myna_mimicyourinteractionsautomate.screen.Identity
import com.example.myna_mimicyourinteractionsautomate.screen.UiNode

/**
 * Layer 1 safety gate (design §4.7, test T11): hard rules, no AI. Checked on every settled screen
 * and before every tap/type. When it blocks, MYNA makes zero taps and hands control to the user.
 *
 * Tuned on the 23 Sep dumps: Zomato's cart places a cash-on-delivery order with one "Place Order" tap,
 * Amazon's checkout is a webview with a payment-method list. Offer banners ("5% back with Amazon Pay UPI")
 * must NOT block a cart page, so single payment words don't count — a *list* of payment methods does.
 */
object SafetyGate {

    enum class Kind { CREDENTIAL, PAYMENT, FINAL_ORDER, LOGIN }

    data class Block(val kind: Kind, val reason: String)

    val PAYMENT_PACKAGES = setOf(
        "com.phonepe.app", "net.one97.paytm", "com.google.android.apps.nbu.paisa.user", "in.org.npci.upiapp",
        "com.samsung.android.spay", "com.mobikwik_new", "com.freecharge.android", "com.dreamplug.androidapp",
        "in.amazon.mShop.android.shopping.pay",
    )

    /** Tapping these commits money or an order. */
    private val COMMIT = Regex(
        "\\b(place (your )?order|pay\\s*(now|₹|rs\\.?|inr|\\d)|proceed to pay|make payment|complete (the )?payment|" +
            "confirm (and|&) pay|(slide|swipe) to pay|pay securely|buy now and pay)\\b", RegexOption.IGNORE_CASE)

    /** A field asking for one of these is a credential field. */
    private val SECRET_FIELD = Regex(
        "\\b(otp|one[- ]?time (password|code)|verification code|cvv|cvc|card number|expiry|valid thru|upi pin|mpin|pin|password|passcode)\\b",
        RegexOption.IGNORE_CASE)

    private val LOGIN_BUTTON = Regex(
        "^(log ?in|sign ?in|login with otp|send otp|get otp|request otp|verify( otp)?|continue with (google|email|phone|mobile)( number)?)$",
        RegexOption.IGNORE_CASE)

    private val CART_NEXT = Regex("^(proceed to buy|proceed to checkout|checkout)\\b", RegexOption.IGNORE_CASE)
    private val TAB = Regex("\\btab \\d+ of \\d+\\b", RegexOption.IGNORE_CASE)

    private fun isTab(n: UiNode) = n.cls?.endsWith("Tab") == true || n.id?.contains("tab", ignoreCase = true) == true ||
        n.label?.let(TAB::containsMatchIn) == true

    /** Payment-method categories; ≥3 visible (or 2 + radio buttons) = a payment screen. */
    private val PAY_METHODS = listOf(
        "upi" to Regex("\\bupi\\b", RegexOption.IGNORE_CASE),
        "card" to Regex("\\b(credit|debit) card|\\bcards?\\b", RegexOption.IGNORE_CASE),
        "netbanking" to Regex("net ?banking", RegexOption.IGNORE_CASE),
        "wallet" to Regex("\\bwallets?\\b|pay balance", RegexOption.IGNORE_CASE),
        "cod" to Regex("(cash|pay) on delivery", RegexOption.IGNORE_CASE),
        "emi" to Regex("\\bemi\\b", RegexOption.IGNORE_CASE),
    )

    /** Screen-level check. Null = safe to act here. */
    fun check(root: UiNode, pkg: String?): Block? {
        if (pkg in PAYMENT_PACKAGES) return Block(Kind.PAYMENT, "payment app $pkg is open")
        val visible = root.walk().filter { it.visible }.toList()

        visible.firstOrNull { it.password }?.let { return Block(Kind.CREDENTIAL, "password field on screen") }
        visible.firstOrNull { it.editable && SECRET_FIELD.containsMatchIn(fieldName(it)) }
            ?.let { return Block(Kind.CREDENTIAL, "field asks for ${SECRET_FIELD.find(fieldName(it))!!.value}") }

        visible.firstOrNull { it.clickable && COMMIT.containsMatchIn(allText(it)) }
            ?.let { return Block(Kind.FINAL_ORDER, "\"${COMMIT.find(allText(it))!!.value}\" button on screen") }

        // Navigation tabs ("Wallet Tab 3 of 6" on every Amazon screen) are not payment options.
        val texts = visible.filter { n -> (sequenceOf(n) + n.ancestors().take(2)).none(::isTab) }.mapNotNull { it.label }
        val methods = PAY_METHODS.filter { (_, re) -> texts.any { re.containsMatchIn(it) } }.map { it.first }
        // A cart ("Proceed to Buy") advertises card/UPI offers but still has a step before payment.
        val isCart = visible.any { it.clickable && it.walk().any { c -> c.label?.let(CART_NEXT::containsMatchIn) == true } }
        // Carts advertise "credit card offers" + "Pay balance" (2 kinds): not a payment screen. Real ones list
        // ≥3 kinds (Zomato: UPI, card, wallet; Amazon: +netbanking, COD, EMI), or 2 with radio buttons to pick one.
        // Radios must be picking a PAYMENT method: product pages have colour/size radios next to EMI offers.
        val radios = visible.any { r -> r.cls?.endsWith("RadioButton") == true &&
            (sequenceOf(r) + r.ancestors().take(1)).any { a -> a.walk().any { c -> c.label?.let { l -> PAY_METHODS.any { (_, re) -> re.containsMatchIn(l) } } == true } } }
        if (!isCart && (methods.size >= 3 || (methods.size >= 2 && radios))) return Block(Kind.PAYMENT, "payment options on screen (${methods.joinToString()})")

        if (visible.any { it.editable } && visible.any { it.clickable && it.label?.let(LOGIN_BUTTON::matches) == true })
            return Block(Kind.LOGIN, "login screen")
        return null
    }

    /** Tap-level check: the screen must be safe AND the target itself must not commit/log in. */
    fun checkTap(target: UiNode, root: UiNode, pkg: String?): Block? {
        check(root, pkg)?.let { return it }
        val t = allText(target)
        if (COMMIT.containsMatchIn(t)) return Block(Kind.FINAL_ORDER, "target is \"${COMMIT.find(t)!!.value}\"")
        if (target.label?.let(LOGIN_BUTTON::matches) == true) return Block(Kind.LOGIN, "target is a login button")
        return null
    }

    /** Layer 2 (rules for now, AI flag later): allowed, but ask the user first. */
    private val RISKY = Regex("\\b(remove|delete|clear (cart|all)|empty cart|cancel (order|booking)|unsubscribe|logout|log out|sign out)\\b",
        RegexOption.IGNORE_CASE)

    fun needsConfirm(target: UiNode): String? = RISKY.find(allText(target))?.value

    private fun fieldName(n: UiNode) = listOfNotNull(n.hint, n.desc, n.id, n.text?.takeIf { n.password }).joinToString(" ") +
        " " + (n.parent?.walk()?.filter { it !== n }?.mapNotNull { it.label }?.take(3)?.joinToString(" ") ?: "")

    private fun allText(n: UiNode) = n.walk().mapNotNull { it.label?.let(Identity::norm) }.joinToString(" ")
}
