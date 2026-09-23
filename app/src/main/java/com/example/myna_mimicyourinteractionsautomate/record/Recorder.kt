package com.example.myna_mimicyourinteractionsautomate.record

import com.example.myna_mimicyourinteractionsautomate.recipe.Recording
import com.example.myna_mimicyourinteractionsautomate.recipe.Screen
import com.example.myna_mimicyourinteractionsautomate.recipe.Step
import com.example.myna_mimicyourinteractionsautomate.recipe.StepType
import com.example.myna_mimicyourinteractionsautomate.recipe.SystemKey
import com.example.myna_mimicyourinteractionsautomate.recipe.Target

/**
 * Turns a stream of already-filtered UI events into recorded steps (design §4.1).
 * No Android here: MynaService feeds it, tests feed it directly.
 */
class Recorder(
    val utterance: String,
    val app: String,
    private val now: () -> Long = System::currentTimeMillis,
) {
    val startedAt = now()
    /** Words of the command, used to pick which card text anchors a tap. */
    val spoken: Set<String> = utterance.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 }.toSet()
    val steps = mutableListOf<Step>()
    private val snapshots = mutableMapOf<String, String>()
    private var lastTapAt = 0L
    private var awaitingNext = false

    fun onLaunch(pkg: String) = add(Step(StepType.LAUNCH, pkg = pkg))

    fun onKey(key: SystemKey) {
        // Tap-then-Back = likely a mis-tap. Mark, don't delete: the compiler shows the user what was dropped.
        val lastReal = steps.indexOfLast { !it.noise }
        val mistake = key == SystemKey.BACK && lastReal >= 0 && steps[lastReal].type == StepType.TAP
        if (mistake) steps[lastReal] = steps[lastReal].copy(noise = true, why = "tapped then went back")
        add(Step(StepType.KEY, key = key, noise = mistake, why = if (mistake) "undo of previous tap" else ""))
    }

    fun onTap(target: Target, screen: Screen) {
        val last = steps.lastOrNull()
        val t = now()
        // Double tap / bounce on the same element: keep the first.
        if (last?.type == StepType.TAP && last.target?.key == target.key && t - lastTapAt < DOUBLE_TAP_MS) return
        lastTapAt = t
        add(Step(StepType.TAP, target = target, screen = screen))
    }

    fun onText(target: Target, text: String, screen: Screen) {
        val i = steps.lastIndex
        // Consecutive edits in the same field collapse into one step with the final text.
        if (i >= 0 && steps[i].type == StepType.TYPE && sameField(steps[i].target, target)) {
            steps[i] = steps[i].copy(text = text)
        } else {
            add(Step(StepType.TYPE, target = target, text = text, screen = screen))
        }
    }

    /** Called when the screen settles. The first settled screen after a step is that step's "next". */
    fun onScreen(screen: Screen, compact: String) {
        snapshots.putIfAbsent(screen.signature, compact)
        if (awaitingNext && steps.isNotEmpty()) {
            steps[steps.lastIndex] = steps.last().copy(next = screen)
            awaitingNext = false
        }
    }

    fun finish(stoppedBy: String = "user") =
        Recording(utterance, app, startedAt, steps.toList(), stoppedBy, snapshots.toMap())

    private fun add(step: Step) {
        steps += step
        awaitingNext = true
    }

    private fun sameField(a: Target?, b: Target) =
        a != null && a.id == b.id && a.cls == b.cls && a.label == b.label

    companion object {
        const val DOUBLE_TAP_MS = 500L
    }
}

/** One human-readable line per step, for the step list and logs. */
fun Step.describe(): String {
    val what = when (type) {
        StepType.LAUNCH -> "open $pkg"
        StepType.KEY -> "press ${key?.name?.lowercase()}"
        StepType.TYPE -> "type \"$text\" into ${target?.label ?: target?.id ?: "field"}"
        StepType.TAP -> "tap " + (target?.label ?: target?.key?.value ?: "?") +
            (target?.key?.takeIf { it.value != target.label }?.let { " (${it.by.name.lowercase()}: ${it.value})" } ?: "")
        StepType.GOAL -> "$goal $args"
    }
    return if (noise) "(mistake?) $what" else what
}
