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
import org.junit.Test

/** 30 Sep 11:38 Zomato teach: the search step saved no result to open, and the fallback couldn't open it. */
class SearchWithoutPickTest {
    private val app = "com.application.zomato"

    @Test fun searchWithNoRecordedPickOpensTheResultByOcr() = runBlocking {
        // The demo's search step saved no "pick" (the landed page wasn't named), so replay reached "tap button1" still on
        // the text-less suggestion list and "open the best result" found nothing: STUCK no result matches "Domino's pizza".
        val en = Screen("s", lang = "en")
        val recipe = Recipe("z", app, "Order a Margherita pizza from Domino's pizza on Zomato", end = End.LAST_SCREEN, subtasks = listOf(Subtask("demo", steps = listOf(
            Step(StepType.LAUNCH, pkg = app, screen = en),
            Step(StepType.GOAL, goal = "search", text = "Domino's pizza", screen = en,
                target = Target(label = "Restaurant name or a dish...", id = "edittext", key = UniqueKey(KeyKind.ID, "edittext"))),
            Step(StepType.TAP, target = Target(id = "button1", key = UniqueKey(KeyKind.ID, "button1")), screen = en)))))
        val z = FakeZomato().apply { state = "search" }
        val log = Executor(z).run(recipe)
        assertEquals(log.reason, Outcome.DONE, log.outcome)
        assertEquals("menu", z.state)
    }
}
