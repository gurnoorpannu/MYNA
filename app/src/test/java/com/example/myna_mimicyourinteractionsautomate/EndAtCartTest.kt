package com.example.myna_mimicyourinteractionsautomate

import com.example.myna_mimicyourinteractionsautomate.compile.Compiler
import com.example.myna_mimicyourinteractionsautomate.llm.Llm
import com.example.myna_mimicyourinteractionsautomate.recipe.End
import com.example.myna_mimicyourinteractionsautomate.recipe.KeyKind
import com.example.myna_mimicyourinteractionsautomate.recipe.Recording
import com.example.myna_mimicyourinteractionsautomate.recipe.Step
import com.example.myna_mimicyourinteractionsautomate.recipe.StepType
import com.example.myna_mimicyourinteractionsautomate.recipe.SystemKey
import com.example.myna_mimicyourinteractionsautomate.recipe.Target
import com.example.myna_mimicyourinteractionsautomate.recipe.UniqueKey
import com.example.myna_mimicyourinteractionsautomate.replay.Device
import com.example.myna_mimicyourinteractionsautomate.replay.Executor
import com.example.myna_mimicyourinteractionsautomate.replay.OcrLine
import com.example.myna_mimicyourinteractionsautomate.replay.Outcome
import com.example.myna_mimicyourinteractionsautomate.safety.GatedActor
import com.example.myna_mimicyourinteractionsautomate.screen.UiNode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 30 Sep device run (Amazon): the demo ended by opening the cart, where the gate saw a "Pay ₹" button and stopped
 * recording. On replay the same cart sometimes shows "Proceed to checkout" instead, so MYNA tapped Proceed 3 times
 * and reported FAILED. A demo that ends by opening the cart now ends at the cart: hand off if it shows a payment
 * button, else stop there — never tap towards checkout.
 */
class EndAtCartTest {
    private fun rec(last: Target) = Recording("add a phone case to my Amazon cart", "in.amazon.mShop.android.shopping", 1, listOf(
        Step(StepType.LAUNCH, pkg = "in.amazon.mShop.android.shopping"),
        Step(StepType.TAP, target = Target(label = "Add to cart", key = UniqueKey(KeyKind.LABEL, "Add to cart"))),
        Step(StepType.TAP, target = last)), stoppedBy = "safety_gate", stopReason = "\"Pay ₹\" button on screen")

    @Test fun demoEndingOnTheCartEndsAtTheCart() = runBlocking {
        Llm.mock = true; Llm.canned.clear()
        assertEquals(End.LAST_SCREEN, Compiler.compile(rec(Target(key = UniqueKey(KeyKind.CHILD_TEXT, "Cart")))).recipe.end)   // Amazon's Cart tab
        assertEquals(End.LAST_SCREEN, Compiler.compile(rec(Target(label = "Go to Cart", key = UniqueKey(KeyKind.LABEL, "Go to Cart")))).recipe.end)
        // Any other last step that the gate stopped after still ends at the payment screen (Zomato: Continue → Place Order).
        assertEquals(End.PAYMENT_SCREEN, Compiler.compile(rec(Target(key = UniqueKey(KeyKind.CHILD_TEXT, "Continue")))).recipe.end)
    }

    /** Home with a Cart tab; the cart shows either a "Pay ₹" window or a "Proceed to checkout" button. */
    private class Amazon(val payWindow: Boolean) : Device {
        var state = "home"
        val taps = mutableListOf<String>()
        private var clock = 0L
        private fun t(s: String, top: Int, clickable: Boolean = false) = UiNode(text = s, cls = "Button", clickable = clickable, t = top, b = top + 80, r = 1000)
        override suspend fun screen() = (if (state == "home") UiNode(cls = "FrameLayout", b = 2392, r = 1080, children = listOf(
            t("Add to cart", 900, clickable = true), UiNode(desc = "Cart", clickable = true, t = 2300, b = 2392, r = 300, children = listOf(t("Cart", 2310)))))
        else UiNode(cls = "FrameLayout", b = 2392, r = 1080, children = listOf(t("Shopping Cart", 200), t("Phone case for Nothing Phone (3a)", 400),
            if (payWindow) t("Pay ₹499", 2200, clickable = true) else t("Proceed to checkout (1 item)", 2200, clickable = true)))) to "in.amazon.mShop.android.shopping"
        override val actor = GatedActor(click = { n -> taps += (n.label ?: n.desc ?: "?"); if (n.desc == "Cart") state = "cart"; true }, setText = { _, _, _ -> true }, onBlocked = {})
        override suspend fun launchClean(pkg: String) = true
        override fun key(key: SystemKey) {}
        override fun scroll(list: UiNode, forward: Boolean) = false
        override suspend fun ocr() = emptyList<OcrLine>()
        override suspend fun ask(question: String, options: List<String>): String? = null
        override fun say(text: String) {}
        override fun now() = clock++
        override suspend fun pause(ms: Long) { clock += ms }
        override fun prompt(text: String?) {}
        override val stopRequested = false
    }

    @Test fun replayStopsAtTheCartEitherWay() = runBlocking {
        Llm.mock = true; Llm.canned.clear()
        val recipe = Compiler.compile(rec(Target(key = UniqueKey(KeyKind.CHILD_TEXT, "Cart")))).recipe

        val proceed = Amazon(payWindow = false)
        val a = Executor(proceed).run(recipe)
        assertEquals(a.reason, Outcome.DONE, a.outcome)
        assertTrue(proceed.taps.toString(), proceed.taps.none { it.startsWith("Proceed") })   // no taps towards checkout

        val pay = Amazon(payWindow = true)
        val b = Executor(pay).run(recipe)
        assertEquals(b.reason, Outcome.HANDED_OFF, b.outcome)
        assertTrue(pay.taps.toString(), pay.taps.none { it.startsWith("Pay") })
    }
}
