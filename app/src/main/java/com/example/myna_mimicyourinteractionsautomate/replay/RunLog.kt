package com.example.myna_mimicyourinteractionsautomate.replay

import kotlinx.serialization.Serializable

/** Everything needed to answer "did the last run work, and if not, where and why?" (T14). */
@Serializable
data class RunLog(
    val recipeId: String,
    val utterance: String,
    val startedAt: Long,
    var endedAt: Long = 0,
    var outcome: Outcome = Outcome.RUNNING,
    var reason: String? = null,               // "stopped at Domino's menu: Farmhouse not found"
    val steps: MutableList<StepLog> = mutableListOf(),
    var aiCalls: Int = 0,
    val slots: Map<String, String> = emptyMap(),  // the blank values this run used
)

/** T14: "Did it work?" answered in one sentence from this run log. */
fun RunLog.report(): String {
    val what = utterance
    return when (outcome) {
        Outcome.HANDED_OFF -> "Yes. For \"$what\" I reached the payment step and handed it to you."
        Outcome.DONE -> "Yes, \"$what\" finished."
        Outcome.STOPPED -> "You stopped it at step ${steps.size}."
        else -> {
            val s = steps.lastOrNull { it.status != "ok" } ?: steps.lastOrNull()
            "No. It stopped " + (s?.let { "at step ${it.index}, ${it.what}" } ?: "before the first step") + ", because $reason."
        }
    }
}

@Serializable
enum class Outcome {
    RUNNING,
    HANDED_OFF,   // reached the payment/secure screen and gave control to the user — the success case
    DONE,         // all steps done, recipe ends on a normal screen
    STUCK,        // stopped on purpose with a specific reason (T10)
    FAILED,       // something broke (element not found after all fallbacks, app missing…)
    STOPPED,      // user pressed Stop
}

@Serializable
data class StepLog(
    val index: Int,
    val what: String,
    var status: String = "running",           // ok | failed | stuck | blocked | skipped
    var level: Int? = null,                   // finder level that found it (1–3, 4 = OCR)
    var note: String? = null,
    var screen: String? = null,
    var ms: Long = 0,
)
