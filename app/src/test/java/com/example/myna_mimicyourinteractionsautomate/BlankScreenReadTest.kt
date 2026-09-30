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
import org.junit.Test

/**
 * 30 Sep 10:24 (Amazon): mid-run, Android briefly reported no active window, and one empty read ended the run as
 * FAILED "screen unreadable" — the app was readable 2 s before and 4 s after. A short blank read is retried.
 */
class BlankScreenReadTest {
    private class Flaky(private val blanks: Int) : Device {
        var reads = 0
        var tapped = false
        private var clock = 0L
        override suspend fun screen(): Pair<UiNode, String>? {
            reads++
            if (reads in 2..(1 + blanks)) return null   // the 2nd read (and a few after it) come back empty
            return UiNode(cls = "FrameLayout", b = 2392, r = 1080, children = listOf(
                UiNode(text = "Add to cart", cls = "Button", clickable = true, t = 900, b = 980, r = 1000))) to "in.amazon.mShop.android.shopping"
        }
        override val actor = GatedActor(click = { tapped = true; true }, setText = { _, _, _ -> true }, onBlocked = {})
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

    private val recipe = Recipe("a", "in.amazon.mShop.android.shopping", "add it", end = End.LAST_SCREEN, subtasks = listOf(Subtask("s", steps = listOf(
        Step(StepType.TAP, target = Target(label = "Add to cart", key = UniqueKey(KeyKind.LABEL, "Add to cart")))))))

    @Test fun oneBlankReadIsRetriedNotFatal() = runBlocking {
        val d = Flaky(blanks = 1)
        val log = Executor(d).run(recipe)
        assertEquals(log.reason, Outcome.DONE, log.outcome)
        assertEquals(true, d.tapped)
    }

    @Test fun aScreenThatStaysUnreadableStillFails() = runBlocking {
        val log = Executor(Flaky(blanks = 1000)).run(recipe)
        assertEquals(Outcome.FAILED, log.outcome)
    }
}
