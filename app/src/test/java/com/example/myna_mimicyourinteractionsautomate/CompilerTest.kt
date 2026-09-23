package com.example.myna_mimicyourinteractionsautomate

import com.example.myna_mimicyourinteractionsautomate.compile.Compiler
import com.example.myna_mimicyourinteractionsautomate.llm.Llm
import com.example.myna_mimicyourinteractionsautomate.recipe.KeyKind
import com.example.myna_mimicyourinteractionsautomate.recipe.Recording
import com.example.myna_mimicyourinteractionsautomate.recipe.Screen
import com.example.myna_mimicyourinteractionsautomate.recipe.Step
import com.example.myna_mimicyourinteractionsautomate.recipe.StepType
import com.example.myna_mimicyourinteractionsautomate.recipe.Target
import com.example.myna_mimicyourinteractionsautomate.recipe.UniqueKey
import com.example.myna_mimicyourinteractionsautomate.replay.Executor
import com.example.myna_mimicyourinteractionsautomate.replay.Outcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompilerTest {
    private val en = Screen("s", lang = "en")

    /** Same shape as the 23 Sep recording that passed T2. */
    private val rec = Recording(
        utterance = "Order a Margherita pizza from Domino's on Zomato", app = "com.application.zomato", startedAt = 1L,
        stoppedBy = "safety_gate",
        steps = listOf(
            Step(StepType.LAUNCH, pkg = "com.application.zomato"),
            Step(StepType.TAP, screen = en, target = Target(label = "Double tap to open search page", id = "search_edit_text", key = UniqueKey(KeyKind.ID, "search_edit_text"))),
            Step(StepType.GOAL, goal = "search", text = "dominos", args = mapOf("pick" to "Domino's Pizza"), screen = en,
                target = Target(label = "Restaurant name or a dish...", id = "edittext", key = UniqueKey(KeyKind.ID, "edittext"))),
            Step(StepType.TAP, screen = en, target = Target(id = "button1", subTexts = listOf("Search"), key = UniqueKey(KeyKind.ID, "button1"))),
            Step(StepType.TYPE, text = "margherita", screen = en, target = Target(label = "Search in Domino's Pizza", id = "edittext", key = UniqueKey(KeyKind.ID, "edittext"))),
            Step(StepType.TAP, screen = en, target = Target(label = "ADD", id = "text_view_title", key = UniqueKey(KeyKind.ID, "text_view_title"),
                nearby = listOf("In Recommended for you", "Margherita Pizza", "Highly reordered", "₹109"),
                neighbours = listOf("Double Cheeseburst - Margherita (Reg)", "Farmhouse Pizza"))),
            Step(StepType.GOAL, goal = "confirm_sheet", args = mapOf("choices" to "New Hand Tossed | Regular"), screen = en),
            Step(StepType.TAP, screen = en, target = Target(id = "container", key = UniqueKey(KeyKind.ID, "container"))),
        ))

    @Test fun findsRestaurantAndItemBlanksWithoutAi() {
        val blanks = Compiler.findBlanks(rec)
        assertEquals(listOf("Margherita", "Domino's"), blanks.map { it.value })
        val item = blanks[0]
        assertEquals(setOf(4, 5), item.hits.map { it.step }.toSet())          // typed + the card Add sits on
        assertEquals(setOf(2), blanks[1].hits.map { it.step }.toSet())         // search query + pick
    }

    @Test fun compiledRecipeReplaysWithFarmhouse() = runBlocking {
        Llm.mock = true
        Llm.canned["compile"] = """{"summary":"order {item} from {restaurant} on Zomato","names":["item","restaurant"],
            "paraphrases":["get me a margherita from dominos"],"steps":[],"subtasks":[],"noise":[],"questions":[]}"""
        val r = Compiler.compile(rec).recipe
        assertEquals("order {item} from {restaurant} on Zomato", r.summary)
        assertEquals("Margherita", r.slots["item"]!!.value)
        assertTrue("Farmhouse Pizza" in r.slots["item"]!!.neighbours)
        val steps = r.subtasks.flatMap { it.steps }
        assertEquals("{restaurant}", steps[2].text); assertEquals("{restaurant}", steps[2].args["pick"])
        assertEquals("{item}", steps[4].text)
        assertEquals(UniqueKey(KeyKind.NEAR_TEXT, "{item}", loose = true), steps[5].target!!.key)
        assertEquals("New Hand Tossed", r.defaults["option_newhandtossed"])

        // T4: the same recipe, a different item. The fake Zomato's card says "Farmhouse Pizza".
        val z = FakeZomato()
        val log = Executor(z).run(r, mapOf("item" to "Farmhouse", "restaurant" to "Domino's"))
        assertEquals(log.reason, Outcome.HANDED_OFF, log.outcome)
        assertEquals(listOf("Farmhouse Pizza"), z.cart)
        Llm.canned.clear()
    }
}
