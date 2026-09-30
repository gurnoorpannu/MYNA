package com.example.myna_mimicyourinteractionsautomate

import com.example.myna_mimicyourinteractionsautomate.recipe.End
import com.example.myna_mimicyourinteractionsautomate.recipe.KeyKind
import com.example.myna_mimicyourinteractionsautomate.recipe.Recipe
import com.example.myna_mimicyourinteractionsautomate.recipe.Screen
import com.example.myna_mimicyourinteractionsautomate.recipe.Step
import com.example.myna_mimicyourinteractionsautomate.recipe.StepType
import com.example.myna_mimicyourinteractionsautomate.recipe.Subtask
import com.example.myna_mimicyourinteractionsautomate.recipe.Target
import com.example.myna_mimicyourinteractionsautomate.recipe.UniqueKey
import com.example.myna_mimicyourinteractionsautomate.replay.Executor
import com.example.myna_mimicyourinteractionsautomate.replay.Outcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 30 Sep 11:26 teach (Zomato): the ADD tap after typing "ma" in Domino's menu search sent no event, so the recipe goes
 * straight from typing the dish to "confirm the options sheet". Replay must make that ADD tap on the typed dish.
 */
class ShortMenuAddTest {
    private val app = "com.application.zomato"

    @Test fun missingAddTapBeforeTheSheetIsMadeOnTheTypedDish() = runBlocking {
        // The replayed recipe as compiled from that teach: type the dish, then confirm the sheet — no ADD step.
        val en = Screen("s", lang = "en")
        val recipe = Recipe("z", app, "Order a Margherita pizza from Domino's on Zomato", end = End.PAYMENT_SCREEN, subtasks = listOf(Subtask("demo", steps = listOf(
            Step(StepType.LAUNCH, pkg = app, screen = en),
            Step(StepType.GOAL, goal = "search", text = "Domino's", args = mapOf("pick" to "Domino's Pizza"), screen = en,
                target = Target(label = "Restaurant name or a dish...", id = "edittext", key = UniqueKey(KeyKind.ID, "edittext"))),
            Step(StepType.TAP, target = Target(id = "button1", key = UniqueKey(KeyKind.ID, "button1")), screen = en),
            Step(StepType.TYPE, target = Target(label = "Search in Domino's Pizza", id = "edittext", key = UniqueKey(KeyKind.ID, "edittext")), text = "{item}", screen = en),
            Step(StepType.GOAL, goal = "confirm_sheet", args = mapOf("choices" to "New Hand Tossed")),
            Step(StepType.TAP, target = Target(id = "container", key = UniqueKey(KeyKind.ID, "container")), screen = en)))))
        val z = FakeZomato()
        val log = Executor(z).run(recipe, mapOf("item" to "Farmhouse"))
        assertEquals(log.reason, Outcome.HANDED_OFF, log.outcome)
        assertEquals(listOf("Farmhouse Pizza"), z.cart)   // the typed dish, not the first card
    }
}
