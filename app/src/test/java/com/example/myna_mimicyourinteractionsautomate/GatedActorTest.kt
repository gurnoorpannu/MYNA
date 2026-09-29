package com.example.myna_mimicyourinteractionsautomate

import com.example.myna_mimicyourinteractionsautomate.safety.GatedActor
import com.example.myna_mimicyourinteractionsautomate.safety.SafetyGate
import com.example.myna_mimicyourinteractionsautomate.screen.UiNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** GatedActor is the only way replay touches another app: every tap and every typed text asks SafetyGate first. */
class GatedActorTest {
    private fun screen(vararg kids: UiNode) = UiNode(cls = "FrameLayout", children = kids.toList())
    private fun text(s: String) = UiNode(text = s, cls = "TextView")
    private fun button(s: String) = UiNode(text = s, cls = "Button", clickable = true)
    private fun field(hint: String) = UiNode(hint = hint, cls = "EditText", editable = true, clickable = true)

    private val clicked = mutableListOf<UiNode>()
    private val typed = mutableListOf<String>()
    private val blocked = mutableListOf<SafetyGate.Block>()
    private fun actor(clickOk: Boolean = true) = GatedActor(
        click = { clicked += it; clickOk },
        setText = { _, s, _ -> typed += s; true },
        onBlocked = { blocked += it })

    private val menu = screen(text("Domino's Pizza"), button("ADD"), button("View Cart"), field("Search in menu"))
    private val cart = screen(text("Margherita Pizza"),
        UiNode(clickable = true, children = listOf(text("Delivering to Home"))),
        UiNode(id = "cv_checkout_container", clickable = true, children = listOf(text("₹299"), text("Place Order"))))
    private val payment = screen(text("UPI"), text("Credit / Debit card"), text("Net Banking"), text("Wallets"), button("Continue"))
    private val otp = screen(text("Enter the code sent to your phone"), field("Enter OTP"), button("Verify"))
    private val login = screen(field("Mobile number"), button("Send OTP"))

    /** Every tappable and typable node on a screen gets tried. */
    private fun everything(root: UiNode) = root.walk().filter { it.clickable || it.editable }.toList()

    @Test fun safeScreenTapsAndTypesGoThrough() {
        val a = actor()
        assertEquals(GatedActor.Result.Done, a.tap(menu.children[1], menu, "com.application.zomato"))
        assertEquals(GatedActor.Result.Done, a.type(menu.children[3], "margherita", menu, "com.application.zomato"))
        assertEquals(1, clicked.size); assertEquals(listOf("margherita"), typed); assertTrue(blocked.isEmpty())
    }

    @Test fun blockedScreensProduceZeroTapsAndZeroTyping() {
        val a = actor()
        val screens = mapOf(cart to "com.application.zomato", payment to "in.amazon.mShop.android.shopping",
            otp to "com.application.zomato", login to "com.application.zomato",
            screen(button("Continue")) to "com.phonepe.app")   // a payment app: nothing in it is safe
        var tries = 0
        for ((root, pkg) in screens) for (n in everything(root)) {
            tries++
            val r = if (n.editable) a.type(n, "123456", root, pkg) else a.tap(n, root, pkg)
            assertTrue("$n on $pkg", r is GatedActor.Result.Blocked)
        }
        assertEquals(0, clicked.size)
        assertEquals(0, typed.size)
        assertEquals(tries, blocked.size)   // the user is told every time
    }

    @Test fun commitButtonIsBlockedEvenOnAScreenThatLooksSafe() {
        val a = actor()
        val pay = button("Pay ₹299")
        assertTrue(a.tap(pay, screen(text("Summary")), "x") is GatedActor.Result.Blocked)
        assertTrue(a.tap(button("Log in"), screen(text("Welcome")), "x") is GatedActor.Result.Blocked)
        assertEquals(0, clicked.size)
    }

    @Test fun addressPickTapsTheAddressButNeverPlaceOrderAndDoesNotAnnounce() {
        val a = actor()
        val chip = cart.children[1]
        val placeOrder = cart.children[2]
        assertEquals(GatedActor.Result.Done, a.tap(chip, cart, "com.application.zomato", addressPick = true))
        assertTrue(a.tap(placeOrder, cart, "com.application.zomato", addressPick = true) is GatedActor.Result.Blocked)
        assertEquals(listOf(chip), clicked)
        // A refused address tap is not a hand-off: the executor decides what happens next.
        assertTrue(blocked.isEmpty())
    }

    @Test fun appRefusingTheClickIsFailedNotDone() {
        assertEquals(GatedActor.Result.Failed, actor(clickOk = false).tap(menu.children[1], menu, "com.application.zomato"))
    }

    @Test fun handOffOnlyAnnounces() {
        val a = actor()
        a.handOff(SafetyGate.Block(SafetyGate.Kind.PAYMENT, "payment options on screen"))
        assertEquals(1, blocked.size); assertEquals(0, clicked.size)
    }
}
