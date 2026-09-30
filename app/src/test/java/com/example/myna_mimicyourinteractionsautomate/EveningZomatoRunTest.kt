package com.example.myna_mimicyourinteractionsautomate

import com.example.myna_mimicyourinteractionsautomate.llm.Llm
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

/** 30 Sep 18:22–18:26 Zomato runs on the phone. */
class EveningZomatoRunTest {
    private val app = "com.application.zomato"
    private val en = Screen("s", lang = "en")

    @Test fun searchWithoutPickOpensTheSearchedRestaurantBeforeTheAiHelperGuesses() = runBlocking {
        // The search step had no saved result; "tap button1" wasn't on the suggestion list and the AI helper picked
        // "Type to search restaurants or dishes" instead (counted as ok). Recovery must open Domino's first.
        Llm.mock = true
        Llm.canned["helper"] = """{"action":"tap","id":0}"""   // the helper's wrong pick: the first tappable thing
        val recipe = Recipe("z", app, "order from dominos", end = End.LAST_SCREEN, subtasks = listOf(Subtask("demo", steps = listOf(
            Step(StepType.LAUNCH, pkg = app, screen = en),
            Step(StepType.GOAL, goal = "search", text = "Domino's pizza", screen = en,
                target = Target(label = "Restaurant name or a dish...", id = "edittext", key = UniqueKey(KeyKind.ID, "edittext"))),
            Step(StepType.TAP, target = Target(id = "button1", key = UniqueKey(KeyKind.ID, "button1")), screen = en)))))
        val z = FakeZomato().apply { state = "search" }
        val log = Executor(z, aiHelper = true).run(recipe)
        Llm.canned.remove("helper")
        assertEquals(log.reason, Outcome.DONE, log.outcome)
        assertTrue(z.taps.toString(), "button1" in z.taps)
        assertTrue(log.steps.none { it.level == 5 })   // no AI guess needed
    }

    /** Domino's menu shaped like the phone: ADD is a clickable View whose "ADD" text is a child TextView. */
    private class ChildTextAdd : Device {
        var state = "menu"
        val cart = mutableListOf<String>()
        private var clock = 0L
        private fun t(s: String, top: Int) = UiNode(text = s, cls = "TextView", t = top, b = top + 50, r = 700)
        private fun card(dish: String, top: Int) = UiNode(cls = "FrameLayout", t = top, b = top + 300, r = 1080, children = listOf(t(dish, top + 10), t("₹299", top + 60),
            UiNode(id = "text_view_title", cls = "View", clickable = true, l = 800, t = top + 200, r = 1000, b = top + 250, children = listOf(t("ADD", top + 205)))))
        override suspend fun screen(): Pair<UiNode, String> = when (state) {
            "menu" -> UiNode(cls = "FrameLayout", b = 2392, r = 1080, children = listOfNotNull(
                UiNode(id = "edittext", text = "Margherita", editable = true, t = 200, b = 260, r = 1080),
                UiNode(cls = "RecyclerView", scrollable = true, t = 300, b = 2000, r = 1080, children = listOf(card("Farmhouse Pizza", 300), card("Margherita Pizza", 700))),
                if (cart.isNotEmpty()) UiNode(id = "container", clickable = true, t = 2140, b = 2297, r = 1048, children = listOf(t("1 item added", 2150), t("Continue", 2200))) else null))
            "sheet" -> UiNode(cls = "FrameLayout", b = 2392, r = 1080, children = listOf(UiNode(id = "touch_outside", clickable = true, b = 2392, r = 1080),
                t("Margherita Pizza", 900), UiNode(cls = "Button", clickable = true, t = 2100, b = 2200, r = 1000, children = listOf(t("Add item ₹299", 2120)))))
            else -> UiNode(cls = "FrameLayout", b = 2392, r = 1080, children = listOf(t("Margherita Pizza", 300),
                UiNode(id = "cv_checkout_container", clickable = true, t = 2200, b = 2392, r = 1080, children = listOf(t("Place Order", 2260)))))
        } to "com.application.zomato"
        override val actor = GatedActor(click = { n ->
            when {
                state == "menu" && n.id == "text_view_title" -> { state = "sheet"; cart += n.parent!!.children[0].text!! }
                state == "sheet" && n.cls == "Button" -> state = "menu"
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

    @Test fun lostAddTapIsMadeWhenTheAddTextIsAChild() = runBlocking {
        // 18:25 run: "type Margherita pi + Enter", then "confirm the options sheet" found no sheet and nothing was added.
        val recipe = Recipe("z", app, "order margherita", end = End.PAYMENT_SCREEN, subtasks = listOf(Subtask("demo", steps = listOf(
            Step(StepType.TYPE, target = Target(label = "Search in Domino's Pizza", id = "edittext", key = UniqueKey(KeyKind.ID, "edittext")), text = "{item}", submit = true, screen = en),
            Step(StepType.GOAL, goal = "confirm_sheet"),
            Step(StepType.TAP, target = Target(id = "container", key = UniqueKey(KeyKind.ID, "container")), screen = en)))))
        val d = ChildTextAdd()
        val log = Executor(d).run(recipe, mapOf("item" to "Margherita"))
        assertEquals(log.reason, Outcome.HANDED_OFF, log.outcome)
        assertEquals(listOf("Margherita Pizza"), d.cart)
    }
}
