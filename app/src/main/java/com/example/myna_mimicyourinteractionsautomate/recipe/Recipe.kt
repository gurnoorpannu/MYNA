package com.example.myna_mimicyourinteractionsautomate.recipe

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * The recipe schema (design §4.3). Source of truth for every module:
 * recorder writes a [Recording], compiler turns it into a [Recipe], executor replays a [Recipe].
 * Slot references inside step text/labels use braces: "{item}", "{restaurant}".
 */

val RecipeJson = Json {
    ignoreUnknownKeys = true   // LLM output and older recipes may carry extra fields
    isLenient = true           // "qty": {"default": 1} → "1"
    encodeDefaults = false
    explicitNulls = false
    prettyPrint = true
}

@Serializable
data class Recipe(
    val id: String,
    val app: String,                                  // package name
    val utterance: String,                            // the command said at teach time
    val paraphrases: List<String> = emptyList(),
    val slots: Map<String, Slot> = emptyMap(),
    val defaults: Map<String, String> = emptyMap(),   // habit defaults: tapped but not said ("size" → "Regular")
    val subtasks: List<Subtask>,
    val end: End = End.PAYMENT_SCREEN,
    val forks: List<Fork> = emptyList(),
    val golden: Boolean = false,                      // replayed by the golden-run button
    val version: Int = 1,
)

@Serializable
data class Slot(
    val value: String? = null,                        // value from the demo
    val default: String? = null,                      // used when the command doesn't mention it
    val neighbours: List<String> = emptyList(),       // other values seen next to it on screen
)

@Serializable
data class Subtask(
    val name: String,                                 // "search_restaurant", "add_item", …
    val why: String = "",
    val steps: List<Step>,
)

@Serializable
enum class StepType {
    @SerialName("launch") LAUNCH,   // open [Step.pkg] from the launcher
    @SerialName("key") KEY,         // system button: [Step.key]
    @SerialName("type") TYPE,       // set text [Step.text] into [Step.target]
    @SerialName("tap") TAP,         // tap [Step.target]
    @SerialName("goal") GOAL,       // built-in goal never taught step by step: [Step.goal] + [Step.args]
}

@Serializable
enum class SystemKey { @SerialName("back") BACK, @SerialName("home") HOME }

@Serializable
data class Step(
    val type: StepType,
    val pkg: String? = null,
    val key: SystemKey? = null,
    val text: String? = null,                         // may contain "{slot}"
    val target: Target? = null,
    val goal: String? = null,                         // "set_qty", "pick_address", "search"
    val args: Map<String, String> = emptyMap(),       // {"n": "{qty}"}
    val screen: Screen? = null,                       // screen the step happens on
    val next: Screen? = null,                         // screen expected after it (verify)
    val why: String = "",
    val noise: Boolean = false,                       // marked by recorder/compiler; skipped on replay
)

/** Everything we know about a tapped/typed element, SUGILITE-style. */
@Serializable
data class Target(
    val label: String? = null,                        // own text or content description; may be "{item}"
    val subTexts: List<String> = emptyList(),         // merged child texts ("Margherita", "₹299", "Veg")
    val id: String? = null,                           // resource-id without package
    val cls: String? = null,
    val index: Int? = null,                           // index among siblings
    val nearby: List<String> = emptyList(),           // texts of nearby nodes
    val neighbours: List<String> = emptyList(),       // sibling list items (possible slot values)
    val key: UniqueKey? = null,
    val bounds: List<Int>? = null,                    // [l, t, r, b] — last resort only
    val ocr: String? = null,                          // OCR text when the node had none
    val scrollDir: String? = null,                    // "down"/"up" if the demo scrolled to reach it
)

@Serializable
data class UniqueKey(val by: KeyKind, val value: String)

@Serializable
enum class KeyKind {
    @SerialName("label") LABEL, @SerialName("id") ID, @SerialName("child_text") CHILD_TEXT,
    @SerialName("ocr") OCR, @SerialName("position") POSITION,
}

@Serializable
data class Screen(
    val signature: String,                            // package + title + a few stable texts, hashed/joined
    val pkg: String? = null,
    val title: String? = null,
    val note: String? = null,                         // "Domino's menu page"
    val lang: String? = null,                         // "en" / "hi" — Devanagari check
)

@Serializable
enum class End { @SerialName("payment_screen") PAYMENT_SCREEN, @SerialName("last_screen") LAST_SCREEN }

/** A fix taught when replay got stuck ("teach me the fix"). */
@Serializable
data class Fork(
    val subtask: Int,
    val step: Int,
    val whenScreen: String,                           // screen signature that triggers the fork
    val steps: List<Step>,
)

/** Raw output of the recorder, before the compiler runs. */
@Serializable
data class Recording(
    val utterance: String,
    val app: String,
    val startedAt: Long,
    val steps: List<Step>,
    val stoppedBy: String = "user",                   // "user" | "safety_gate"
)
