package com.example.myna_mimicyourinteractionsautomate

import com.example.myna_mimicyourinteractionsautomate.recipe.End
import com.example.myna_mimicyourinteractionsautomate.recipe.KeyKind
import com.example.myna_mimicyourinteractionsautomate.recipe.Recipe
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
 * 29 Sep device run (Nothing A059, Zomato): the Domino's page puts its name inside the header's scrollable
 * GridView, so it looked like a result row, not the page heading. Opening Domino's took 14–17 s and once gave up
 * with "couldn't open Domino's" after landing on the page. Screens below follow that page's real tree dump; here
 * the unfixed code taps the title as if it were a result.
 */
class SearchArrivalTest {
    private class RealZomato : Device {
        var state = "search"
        val taps = mutableListOf<String>()
        private var clock = 0L

        private fun t(s: String, top: Int, id: String? = null, clickable: Boolean = false) =
            UiNode(text = s, id = id, cls = "View", clickable = clickable, t = top, b = top + 60, r = 900)
        private val field = UiNode(id = "edittext", hint = "Restaurant name or a dish...", editable = true, clickable = true, t = 150, b = 250, r = 1080)

        private fun build(): UiNode = when (state) {
            "search", "results" -> UiNode(cls = "FrameLayout", b = 2392, r = 1080, children = listOf(field,
                UiNode(cls = "RecyclerView", scrollable = true, t = 300, b = 2392, r = 1080, children = listOf(
                    UiNode(id = "res_row", clickable = true, t = 400, b = 600, r = 1080, children = listOf(t("Domino's Pizza", 420), t("15-20 mins", 500))),
                    UiNode(id = "res_row", clickable = true, t = 650, b = 850, r = 1080, children = listOf(t("Pizza Hut", 670)))))))
            // The restaurant page: header inside ScrollView > GridView (both scrollable), menu search behind button1, no text field.
            "restaurant" -> UiNode(cls = "FrameLayout", b = 2392, r = 1080, children = listOf(
                UiNode(id = "root", cls = "ScrollView", scrollable = true, b = 2392, r = 1080, children = listOf(
                    UiNode(id = "recyclerView", cls = "GridView", scrollable = true, t = 468, b = 1105, r = 1080, children = listOf(
                        UiNode(cls = "ViewGroup", t = 531, b = 736, r = 1080, children = listOf(
                            t("Domino's Pizza", 531, id = "title", clickable = true), t("1 km · Mall Road", 632, id = "subtitle"))))))),
                UiNode(id = "toolbar_restaurant", t = 0, b = 273, r = 1080, children = listOf(
                    UiNode(id = "button1", clickable = true, l = 548, t = 152, r = 786, b = 252, children = listOf(t("Search", 152, id = "subtitle1")))))))
            // Tapping the restaurant's title opens its info page: no menu search there.
            "info" -> UiNode(cls = "FrameLayout", b = 2392, r = 1080, children = listOf(t("Domino's Pizza", 200), t("Mall Road, Amritsar", 300)))
            "menu_search" -> UiNode(cls = "FrameLayout", b = 2392, r = 1080, children = listOf(field))
            else -> error(state)
        }

        override suspend fun screen() = build() to "com.application.zomato"
        override val actor = GatedActor(click = { n ->
            taps += "$state:${n.id ?: n.label ?: "?"}"
            when {
                state == "results" && n.id == "res_row" -> state = "restaurant"
                state == "restaurant" && n.id == "title" -> state = "info"
                state == "restaurant" && n.id == "button1" -> state = "menu_search"
            }
            true
        }, setText = { _, _, _ -> if (state == "search") state = "results"; true }, onBlocked = {})
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

    private val recipe = Recipe("z", "com.application.zomato", "order from dominos", end = End.LAST_SCREEN, subtasks = listOf(Subtask("s", steps = listOf(
        Step(StepType.GOAL, goal = "search", text = "Domino's", args = mapOf("pick" to "Domino's Pizza")),
        Step(StepType.TAP, target = Target(id = "button1", key = UniqueKey(KeyKind.ID, "button1")))))))

    @Test fun restaurantPageWhoseNameSitsInAHeaderListCountsAsArrived() = runBlocking {
        val z = RealZomato()
        val log = Executor(z).run(recipe)
        assertEquals(log.reason, Outcome.DONE, log.outcome)
        assertTrue(z.taps.toString(), z.taps.none { it.endsWith(":title") })   // never taps the restaurant's own name
        assertEquals("menu_search", z.state)
        assertEquals("opened \"Domino's Pizza\" after 1 tap(s)", log.steps[0].note)
    }
}
