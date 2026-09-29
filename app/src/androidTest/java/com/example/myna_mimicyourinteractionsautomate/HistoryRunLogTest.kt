package com.example.myna_mimicyourinteractionsautomate

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myna_mimicyourinteractionsautomate.recipe.RecipeJson
import com.example.myna_mimicyourinteractionsautomate.replay.Outcome
import com.example.myna_mimicyourinteractionsautomate.replay.RunLog
import com.example.myna_mimicyourinteractionsautomate.replay.StepLog
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** A run log saved where MynaService saves it shows up in History, with its outcome, steps and reason. */
@RunWith(AndroidJUnit4::class)
class HistoryRunLogTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val runs = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "runs").apply { mkdirs() }
    private val started = System.currentTimeMillis()
    private val file = File(runs, "run-$started.json")

    @After fun cleanUp() { file.delete() }

    @Test fun savedRunIsListedAndOpens() {
        val log = RunLog("androidtest", "order an androidtest pizza from nowhere", started, endedAt = started + 12_000,
            outcome = Outcome.STUCK, reason = "androidtest: Nowhere Pizza isn't taking orders right now",
            steps = mutableListOf(StepLog(1, "open Zomato", "ok"), StepLog(2, "search \"nowhere\"", "ok"),
                StepLog(3, "tap ADD on androidtest pizza", "stuck")))
        file.writeText(RecipeJson.encodeToString(log))

        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithText("History").performClick()
            compose.onNodeWithText("Order an androidtest pizza from nowhere").assertIsDisplayed()
            compose.onNodeWithText("⚠ Stuck").assertIsDisplayed()
            compose.onNodeWithText("12s · 3 steps", substring = true).assertIsDisplayed()

            compose.onNodeWithText("Order an androidtest pizza from nowhere").performClick()
            compose.onNodeWithText("androidtest: Nowhere Pizza isn't taking orders right now").assertIsDisplayed()
            compose.onNodeWithText("tap ADD on androidtest pizza", substring = true).assertExists()
        }
    }
}
