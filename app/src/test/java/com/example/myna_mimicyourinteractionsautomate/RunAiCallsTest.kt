package com.example.myna_mimicyourinteractionsautomate

import com.example.myna_mimicyourinteractionsautomate.llm.Llm
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
import org.junit.Test

/** Slide 13 needs "AI calls per run": the run log must count the AI helper calls replay made. */
class RunAiCallsTest {
    /** One screen with a "Continue" button; the recipe wants "Proceed", which never shows up. */
    private class OneScreen : Device {
        private var clock = 0L
        override suspend fun screen() = UiNode(cls = "FrameLayout", b = 2400, r = 1080, children = listOf(
            UiNode(text = "Continue", cls = "Button", clickable = true, t = 2200, b = 2300, r = 1080))) to "com.example.shop"
        override val actor = GatedActor({ true }, { _, _, _ -> true }, {})
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

    private val recipe = Recipe("r", "com.example.shop", "buy it", end = End.LAST_SCREEN, subtasks = listOf(Subtask("s", steps = listOf(
        Step(StepType.TAP, target = Target(label = "Proceed", key = UniqueKey(KeyKind.LABEL, "Proceed")))))))

    @Test fun aiHelperCallIsCountedInTheRunLog() = runBlocking {
        Llm.mock = true
        Llm.canned["helper"] = """{"action":"stuck","reason":"no Proceed button"}"""
        val log = Executor(OneScreen(), aiHelper = true).run(recipe)
        assertEquals(Outcome.STUCK, log.outcome)
        assertEquals(1, log.aiCalls)
        Llm.canned.remove("helper"); Unit
    }

    @Test fun noAiHelperNoCalls() = runBlocking {
        Llm.mock = true
        assertEquals(0, Executor(OneScreen(), aiHelper = false).run(recipe).aiCalls)
    }
}
