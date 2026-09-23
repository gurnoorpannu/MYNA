package com.example.myna_mimicyourinteractionsautomate

import com.example.myna_mimicyourinteractionsautomate.compile.Compiler
import com.example.myna_mimicyourinteractionsautomate.llm.Llm
import com.example.myna_mimicyourinteractionsautomate.recipe.KeyKind
import com.example.myna_mimicyourinteractionsautomate.recipe.Recording
import com.example.myna_mimicyourinteractionsautomate.recipe.Screen
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

/** Amazon-shaped fake: search bar button → field + Go → web results → product → Add to cart → cart → payment. */
class FakeAmazon : Device {
    var state = "home"; var query = ""; var product = ""; val cart = mutableListOf<String>()
    private var clock = 0L
    private val catalog = listOf("Spigen Liquid Air Case for Galaxy S25 Ultra", "Ringke Fusion Clear Case for Galaxy S25 Ultra",
        "AmazonBasics Adjustable Laptop Stand for Desk", "Portronics My Buddy Laptop Stand, Aluminium")
    private fun link(t: String, top: Int, id: String? = null) = UiNode(text = t, id = id, clickable = true, t = top, b = top + 80, r = 1000)
    private fun tabs() = UiNode(id = "bottom_tabs", t = 2200, b = 2300, children = listOf(
        UiNode(desc = "Cart Tab 4 of 6", id = "cart_tab", clickable = true, children = listOf(UiNode(text = "Cart", id = "bottom_tab_label"))),
        UiNode(desc = "Wallet Tab 3 of 6", clickable = true, children = listOf(UiNode(text = "Wallet", id = "bottom_tab_label")))))
    private fun build(): UiNode = when (state) {
        "home" -> UiNode(b = 2340, children = listOf(link("Search or ask a question", 100, "chrome_search"), tabs()))
        "typing" -> UiNode(b = 2340, children = listOf(UiNode(hint = "Search or ask a question", id = "rs_search_src_text", editable = true, clickable = true, t = 100, b = 180), tabs()))
        "results" -> UiNode(b = 2340, children = listOf(link(query, 100, "chrome_search"),
            UiNode(cls = "WebView", scrollable = true, t = 200, b = 2200, children = catalog.mapIndexed { i, p -> link("Go to detail page for \"$p\"", 300 + i * 300) }), tabs()))
        "product" -> UiNode(b = 2340, children = listOf(UiNode(text = product, t = 200, b = 300), link("Add to cart", 1500), link("Buy Now", 1600), tabs()))
        "cart" -> UiNode(b = 2340, children = listOf(UiNode(text = cart.joinToString(), t = 300, b = 400), UiNode(text = "10% off with HDFC credit card", t = 500, b = 550),
            link("Proceed to checkout", 700), tabs()))
        "payment" -> UiNode(b = 2340, children = listOf(UiNode(text = "Pay by any UPI App", t = 300, b = 360), UiNode(text = "Add a new credit or debit card", t = 400, b = 460),
            UiNode(text = "Net Banking", t = 500, b = 560), UiNode(cls = "RadioButton", clickable = true, t = 300, b = 360)))
        else -> error(state)
    }
    override suspend fun screen() = build() to "in.amazon.mShop.android.shopping"
    override val actor = GatedActor(click = ::click, setText = { n, s, go -> if (n.editable) { query = s; if (go) state = "results" }; n.editable }, onBlocked = {})
    private fun click(n: UiNode): Boolean {
        when {
            n.id == "chrome_search" -> state = "typing"
            state == "results" && n.text!!.startsWith("Go to detail page") -> { product = n.text.substringAfter("\"").substringBefore("\""); state = "product" }
            n.text == "Add to cart" -> cart += product
            n.id == "cart_tab" -> state = "cart"
            n.text == "Proceed to checkout" -> state = "payment"
        }
        return true
    }
    override suspend fun launchClean(pkg: String) = true.also { state = "home" }
    override fun key(key: SystemKey) {}
    override fun scroll(list: UiNode, forward: Boolean) = false
    override suspend fun ocr() = emptyList<OcrLine>()
    override suspend fun ask(question: String, options: List<String>): String? = null
    override fun say(text: String) {}
    override fun now() = clock.also { clock += 100 }
    override suspend fun pause(ms: Long) { clock += ms }
    override fun prompt(text: String?) {}
    override val stopRequested = false
}

class AmazonTest {
    private val en = Screen("s", lang = "en")
    // The 23 Sep Amazon teach, as the recorder now records it (search + Go, inferred web taps).
    private val rec = Recording("add a phone case for my s25-ultra to my amazon shopping cart", "in.amazon.mShop.android.shopping", 3L,
        stoppedBy = "safety_gate", steps = listOf(
            Step(StepType.LAUNCH, pkg = "in.amazon.mShop.android.shopping"),
            Step(StepType.TYPE, text = "s25 ultra phone case", submit = true, screen = en,
                target = Target(label = "Search or ask a question", id = "rs_search_src_text", key = UniqueKey(KeyKind.ID, "rs_search_src_text"))),
            Step(StepType.TAP, screen = en, why = "inferred", target = Target(label = "Go to detail page for \"Ringke Fusion Clear Case for Galaxy S25 Ultra\"")),
            Step(StepType.TAP, screen = en, target = Target(label = "Add to cart", key = UniqueKey(KeyKind.LABEL, "Add to cart"))),
            Step(StepType.TAP, screen = en, target = Target(key = UniqueKey(KeyKind.CHILD_TEXT, "Cart"))),
            Step(StepType.TAP, screen = en, target = Target(label = "Proceed to checkout", key = UniqueKey(KeyKind.LABEL, "Proceed to checkout"))),
        ))

    @Test fun t8ExactAndT9NewProduct() = runBlocking {
        Llm.mock = true
        Llm.canned["compile"] = """{"summary":"add a {product} for my {device} to my amazon shopping cart","names":["product","device"],
            "paraphrases":[],"steps":[],"subtasks":[],"noise":[],"questions":[]}"""
        val r = Compiler.compile(rec).recipe
        val steps = r.subtasks.flatMap { it.steps }
        assertEquals("{device} {product}", steps[1].text)
        assertEquals("pick_result", steps[2].goal)

        val a = FakeAmazon()
        val t8 = Executor(a).run(r)
        assertEquals(t8.reason, Outcome.HANDED_OFF, t8.outcome)
        assertTrue(a.cart.single().contains("S25 Ultra"))

        val b = FakeAmazon()
        val t9 = Executor(b).run(r, mapOf("product" to "laptop stand", "device" to ""))
        assertEquals(t9.reason, Outcome.HANDED_OFF, t9.outcome)
        assertTrue(b.cart.single(), b.cart.single().contains("Laptop Stand"))
        Llm.canned.clear()
    }
}
