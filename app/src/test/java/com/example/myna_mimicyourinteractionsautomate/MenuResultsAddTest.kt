package com.example.myna_mimicyourinteractionsautomate

import com.example.myna_mimicyourinteractionsautomate.recipe.End
import com.example.myna_mimicyourinteractionsautomate.recipe.KeyKind
import com.example.myna_mimicyourinteractionsautomate.recipe.Recipe
import com.example.myna_mimicyourinteractionsautomate.recipe.Screen
import com.example.myna_mimicyourinteractionsautomate.recipe.Step
import com.example.myna_mimicyourinteractionsautomate.recipe.StepType
import com.example.myna_mimicyourinteractionsautomate.recipe.Subtask
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
 * 30 Sep 18:34/18:36 runs: "confirm the options sheet — no sheet open" and nothing added. The screen, from the dump of
 * Domino's menu search "Margherita": each card has a clickable LinearLayout #ll_root holding the real View "ADD"
 * #text_view_title, and the customisable dish's sheet slides up a moment after the tap.
 */
class MenuResultsAddTest {
    private class DominosMenuSearch : Device {
        var state = "results"
        var sheetIn = 0                    // screen reads until the sheet has slid in
        val cart = mutableListOf<String>()
        val taps = mutableListOf<String>()
        private var clock = 0L
        private fun v(s: String, top: Int, id: String? = null, clickable: Boolean = false, l: Int = 32, r: Int = 618) =
            UiNode(text = s, id = id, cls = "View", clickable = clickable, l = l, t = top, r = r, b = top + 60)
        private fun card(dish: String, top: Int) = UiNode(cls = "FrameLayout", clickable = true, t = top, b = top + 653, r = 1080, children = listOf(
            v("In Recommended for you", top + 66, id = "category_info"), v(dish, top + 124, id = "dish_name"), v("₹114", top + 257, id = "dish_final_price"),
            UiNode(desc = dish, id = "image_view", cls = "ImageView", clickable = true, l = 649, t = top + 10, r = 1048, b = top + 409),
            UiNode(id = "ll_root", cls = "LinearLayout", clickable = true, l = 691, t = top + 352, r = 1006, b = top + 457, children = listOf(
                v("ADD", top + 352, id = "text_view_title", clickable = true, l = 759, r = 931),
                UiNode(id = "button_add", cls = "View", clickable = true, l = 931, t = top + 362, r = 1001, b = top + 457))),
            v("customisable", top + 462, id = "dish_customisation", l = 691, r = 1006)))

        override suspend fun screen(): Pair<UiNode, String> {
            if (state == "opening" && --sheetIn <= 0) state = "sheet"
            return when (state) {
                "results", "opening" -> UiNode(cls = "FrameLayout", b = 2392, r = 1080, children = listOfNotNull(
                    UiNode(id = "edittext", text = "Margherita", cls = "EditText", editable = true, clickable = true, l = 159, t = 208, r = 932, b = 263),
                    v("Showing results for Margherita", 346, id = "title", clickable = true),
                    UiNode(cls = "RecyclerView", scrollable = true, t = 424, b = 2392, r = 1080, children = listOf(
                        card("Margherita Pizza", 424), card("Double Cheese Margherita Pizza", 1143))),
                    if (cart.isNotEmpty()) UiNode(id = "container", clickable = true, t = 2140, b = 2297, r = 1048, children = listOf(v("1 item added", 2150), v("Continue", 2200))) else null))
                "sheet" -> UiNode(cls = "FrameLayout", b = 2392, r = 1080, children = listOf(UiNode(id = "touch_outside", clickable = true, b = 2392, r = 1080),
                    v("Margherita Pizza", 900), UiNode(cls = "Button", clickable = true, t = 2100, b = 2200, r = 1000, children = listOf(v("Add item ₹114", 2120)))))
                else -> UiNode(cls = "FrameLayout", b = 2392, r = 1080, children = listOf(v("Margherita Pizza", 300),
                    UiNode(id = "cv_checkout_container", clickable = true, t = 2200, b = 2392, r = 1080, children = listOf(v("Place Order", 2260)))))
            } to "com.application.zomato"
        }

        override val actor = GatedActor(click = { n ->
            taps += n.id ?: n.label ?: "?"
            when {
                // Only the real button opens the sheet; the wrapper and the card do nothing.
                state == "results" && n.id == "text_view_title" -> { state = "opening"; sheetIn = 3 }
                state == "sheet" && n.cls == "Button" -> { cart += "Margherita Pizza"; state = "results" }
                n.id == "container" -> state = "cart"
                n.id == "cv_checkout_container" -> error("tapped Place Order!")
            }
            true
        }, setText = { _, _, _ -> true }, onBlocked = {})
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

    @Test fun addsTheTypedDishOnTheRealAddButtonAndWaitsForItsSheet() = runBlocking {
        val en = Screen("s", lang = "en")
        val recipe = Recipe("z", "com.application.zomato", "order margherita", end = End.PAYMENT_SCREEN, subtasks = listOf(Subtask("demo", steps = listOf(
            Step(StepType.TYPE, target = Target(label = "Search in Domino's Pizza", id = "edittext", key = UniqueKey(KeyKind.ID, "edittext")), text = "{item}", submit = true, screen = en),
            Step(StepType.GOAL, goal = "confirm_sheet"),
            Step(StepType.TAP, target = Target(id = "container", key = UniqueKey(KeyKind.ID, "container")), screen = en)))))
        val d = DominosMenuSearch()
        val log = Executor(d).run(recipe, mapOf("item" to "Margherita"))
        assertEquals(log.reason, Outcome.HANDED_OFF, log.outcome)
        assertEquals(listOf("Margherita Pizza"), d.cart)
        assertTrue(d.taps.toString(), "ll_root" !in d.taps)
        assertTrue(d.taps.toString(), "text_view_title" in d.taps)   // the real ADD button
    }
}
