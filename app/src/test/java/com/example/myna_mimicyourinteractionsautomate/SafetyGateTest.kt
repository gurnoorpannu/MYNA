package com.example.myna_mimicyourinteractionsautomate

import com.example.myna_mimicyourinteractionsautomate.safety.GatedActor
import com.example.myna_mimicyourinteractionsautomate.safety.SafetyGate
import com.example.myna_mimicyourinteractionsautomate.safety.SafetyGate.Kind
import com.example.myna_mimicyourinteractionsautomate.screen.UiNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T11 (−10 if failed). Screens are shaped like the real 23 Sep Zomato/Amazon dumps. */
class SafetyGateTest {
    private fun screen(vararg kids: UiNode) = UiNode(cls = "FrameLayout", children = kids.toList())
    private fun text(s: String) = UiNode(text = s, cls = "TextView")
    private fun button(s: String, id: String? = null) = UiNode(text = s, id = id, cls = "Button", clickable = true)
    private fun field(hint: String, password: Boolean = false) = UiNode(hint = hint, cls = "EditText", editable = true, clickable = true, password = password)

    // Zomato cart: the whole "₹155.24 TOTAL Place Order" bar is one clickable container.
    private val zomatoCart = screen(text("Domino's Pizza"), text("Margherita Pizza"), text("Pay using"), text("Pay on Delivery"),
        UiNode(id = "cv_checkout_container", clickable = true, children = listOf(text("₹155.24"), text("TOTAL"), text("Place Order"))))
    private val zomatoMenu = screen(text("Domino's Pizza"), text("Margherita Pizza"), button("ADD"), button("View Cart"))
    private val amazonCart = screen(text("Shopping Cart"), text("Get 5% back with Amazon Pay UPI"), button("Proceed to checkout"))
    private val amazonPayment = screen(text("Payment"), text("Pay by any UPI App"), text("+ Add a new credit or debit card"),
        text("Amazon Pay Balance: ₹0.00"), UiNode(cls = "RadioButton", clickable = true), button("Use this payment method"))
    private val otpScreen = screen(text("Enter the 6-digit code sent to +91 98xxx"), field("Enter OTP"), button("Verify"))
    private val loginScreen = screen(field("Mobile number"), button("Continue with Phone Number"))

    @Test fun blocksEveryDangerousScreen() {
        assertEquals(Kind.FINAL_ORDER, SafetyGate.check(zomatoCart, "com.application.zomato")?.kind)
        assertEquals(Kind.PAYMENT, SafetyGate.check(amazonPayment, "in.amazon.mShop.android.shopping")?.kind)
        assertEquals(Kind.CREDENTIAL, SafetyGate.check(otpScreen, "com.application.zomato")?.kind)
        assertEquals(Kind.CREDENTIAL, SafetyGate.check(screen(field("Password", password = true)), "x")?.kind)
        assertEquals(Kind.LOGIN, SafetyGate.check(loginScreen, "com.application.zomato")?.kind)
        assertEquals(Kind.PAYMENT, SafetyGate.check(screen(text("Pay ₹155")), "com.phonepe.app")?.kind)
        assertEquals(Kind.FINAL_ORDER, SafetyGate.check(screen(button("Pay ₹155.24")), "x")?.kind)
        assertEquals(Kind.FINAL_ORDER, SafetyGate.check(screen(button("Place your order", id = "placeOrder")), "x")?.kind)
    }

    @Test fun letsNormalShoppingScreensThrough() {
        assertNull(SafetyGate.check(zomatoMenu, "com.application.zomato"))
        assertNull(SafetyGate.check(amazonCart, "in.amazon.mShop.android.shopping"))          // one UPI offer ≠ payment screen
        // 23 Sep false alarm: Amazon's cart shows card offers + Pay balance.
        assertNull(SafetyGate.check(screen(text("Shopping Cart"), text("10% off with HDFC credit card"), text("Amazon Pay Balance: ₹0"),
            button("Proceed to Buy (1 item)")), "in.amazon.mShop.android.shopping"))
        assertNull(SafetyGate.check(screen(field("Search for restaurant, item or more")), "com.application.zomato"))
        assertNull(SafetyGate.check(screen(text("Sign in for the best experience"), button("Sign in")), "x"))  // banner, no form
    }

    @Test fun tapLevelBlocksCommitTargetsEvenOnSafeScreens() {
        val pay = button("Pay now")
        assertEquals(Kind.FINAL_ORDER, SafetyGate.checkTap(pay, screen(text("Cart")), "x")?.kind)
        assertEquals("Remove", SafetyGate.needsConfirm(button("Remove")))
        assertNull(SafetyGate.needsConfirm(button("ADD")))
    }

    @Test fun gatedActorNeverClicksOrTypesWhenBlocked() {
        var clicks = 0; var typed = 0; val blocks = mutableListOf<SafetyGate.Block>()
        val actor = GatedActor({ clicks++; true }, { _, _, _ -> typed++; true }, { blocks += it })
        val placeOrder = zomatoCart.walk().first { it.id == "cv_checkout_container" }
        val otp = otpScreen.walk().first { it.editable }

        assertTrue(actor.tap(placeOrder, zomatoCart, "com.application.zomato") is GatedActor.Result.Blocked)
        assertTrue(actor.tap(zomatoCart.children[0], zomatoCart, "com.application.zomato") is GatedActor.Result.Blocked) // any tap on a blocked screen
        assertTrue(actor.type(otp, "123456", otpScreen, "com.application.zomato") is GatedActor.Result.Blocked)
        assertEquals(0, clicks); assertEquals(0, typed); assertEquals(3, blocks.size)

        assertEquals(GatedActor.Result.Done, actor.tap(zomatoMenu.children[2], zomatoMenu, "com.application.zomato"))
        assertEquals(1, clicks)
    }
}
