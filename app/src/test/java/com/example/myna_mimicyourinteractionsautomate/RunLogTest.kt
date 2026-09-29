package com.example.myna_mimicyourinteractionsautomate

import com.example.myna_mimicyourinteractionsautomate.recipe.RecipeJson
import com.example.myna_mimicyourinteractionsautomate.replay.Outcome
import com.example.myna_mimicyourinteractionsautomate.replay.RunLog
import com.example.myna_mimicyourinteractionsautomate.replay.StepLog
import com.example.myna_mimicyourinteractionsautomate.replay.report
import org.junit.Assert.assertEquals
import org.junit.Test

/** Run logs are what History and T14 ("did the last run work?") read back from files/runs/. */
class RunLogTest {
    private fun log(outcome: Outcome, reason: String? = null, steps: List<StepLog> = emptyList()) = RunLog(
        recipeId = "zomato_1", utterance = "order Margherita from Domino's on Zomato", startedAt = 1_000, endedAt = 9_000,
        outcome = outcome, reason = reason, steps = steps.toMutableList(), aiCalls = 1, slots = mapOf("item" to "Margherita", "qty" to "2"))

    private val steps = listOf(
        StepLog(1, "open Zomato", "ok", ms = 2_000),
        StepLog(2, "search \"Domino's\", open Domino's Pizza", "ok", level = 4, note = "ocr \"Domino's Pizza\""),
        StepLog(3, "tap ADD on Margherita Pizza", "stuck", level = null, note = "scrolled 5 times", screen = "zomato|Domino's"))

    @Test fun roundTripsThroughTheOnDiskJson() {
        val l = log(Outcome.STUCK, "couldn't find \"Margherita\"", steps)
        assertEquals(l, RecipeJson.decodeFromString<RunLog>(RecipeJson.encodeToString(l)))
    }

    @Test fun everyOutcomeSurvivesSerialization() {
        for (o in Outcome.entries) assertEquals(o, RecipeJson.decodeFromString<RunLog>(RecipeJson.encodeToString(log(o))).outcome)
    }

    @Test fun olderLogsWithMissingFieldsStillLoad() {
        val l = RecipeJson.decodeFromString<RunLog>("""{"recipeId":"z","utterance":"order pizza","startedAt":5,"newField":1}""")
        assertEquals(Outcome.RUNNING, l.outcome)
        assertEquals(0, l.steps.size)
    }

    @Test fun t14HandedOffIsASuccess() {
        assertEquals("Yes. For \"order Margherita from Domino's on Zomato\" I reached the payment step and handed it to you.",
            log(Outcome.HANDED_OFF, "\"Place Order\" button on screen", steps.take(2)).report())
    }

    @Test fun t14StuckNamesTheStepAndTheReason() {
        assertEquals("No. It stopped at step 3, tap ADD on Margherita Pizza, because Domino's Pizza isn't taking orders right now.",
            log(Outcome.STUCK, "Domino's Pizza isn't taking orders right now", steps).report())
    }

    @Test fun t14FailedNamesTheStepAndTheReason() {
        val failed = steps.take(1) + StepLog(2, "search \"Domino's\"", "failed")
        assertEquals("No. It stopped at step 2, search \"Domino's\", because couldn't open com.application.zomato.",
            log(Outcome.FAILED, "couldn't open com.application.zomato", failed).report())
    }

    @Test fun t14DoneAndStopped() {
        assertEquals("Yes, \"order Margherita from Domino's on Zomato\" finished.", log(Outcome.DONE, steps = steps.take(2)).report())
        assertEquals("You stopped it at step 2.", log(Outcome.STOPPED, "stopped by user", steps.take(2)).report())
    }
}
