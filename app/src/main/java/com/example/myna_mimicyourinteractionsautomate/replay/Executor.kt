package com.example.myna_mimicyourinteractionsautomate.replay

import com.example.myna_mimicyourinteractionsautomate.recipe.End
import com.example.myna_mimicyourinteractionsautomate.recipe.KeyKind
import com.example.myna_mimicyourinteractionsautomate.recipe.Recipe
import com.example.myna_mimicyourinteractionsautomate.recipe.Step
import com.example.myna_mimicyourinteractionsautomate.recipe.StepType
import com.example.myna_mimicyourinteractionsautomate.recipe.SystemKey
import com.example.myna_mimicyourinteractionsautomate.record.describe
import com.example.myna_mimicyourinteractionsautomate.safety.GatedActor
import com.example.myna_mimicyourinteractionsautomate.safety.SafetyGate
import com.example.myna_mimicyourinteractionsautomate.screen.Identity
import com.example.myna_mimicyourinteractionsautomate.screen.UiNode

/** What the executor needs from the phone. MynaService implements it; tests fake it. */
interface Device {
    /** Current screen, after waiting for it to settle. Null if nothing readable is in front. */
    suspend fun screen(): Pair<UiNode, String>?
    val actor: GatedActor
    suspend fun launchClean(pkg: String): Boolean
    fun key(key: SystemKey)
    fun scroll(list: UiNode, forward: Boolean): Boolean
    /** On-device OCR of the whole screen: text lines with screen bounds. */
    suspend fun ocr(): List<OcrLine>
    /** Ask the user; returns the chosen option or null (no answer / timeout). */
    suspend fun ask(question: String, options: List<String>): String?
    fun say(text: String)
    fun now(): Long
    suspend fun pause(ms: Long)
    /** Spoken request + top banner asking the user to do one thing by hand; null clears it. */
    fun prompt(text: String?)
    val stopRequested: Boolean
}

data class OcrLine(val text: String, val l: Int, val t: Int, val r: Int, val b: Int)

/**
 * Replays a recipe step by step with NO AI (design §4.5). Every action goes through [Device.actor],
 * i.e. the safety gate. Returns a [RunLog] with a status and reason for each step.
 */
class Executor(
    private val device: Device,
    private val stepTimeoutMs: Long = 30_000,
    private val onStep: (StepLog, Int) -> Unit = { _, _ -> },   // progress for the overlay: (step, total)
) {

    private class Stop(val outcome: Outcome, val reason: String) : Exception(reason)

    suspend fun run(recipe: Recipe, slots: Map<String, String> = emptyMap()): RunLog {
        val log = RunLog(recipe.id, recipe.utterance, device.now())
        val steps = recipe.subtasks.flatMap { it.steps }.filter { !it.noise }
        try {
            steps.forEachIndexed { i, step ->
                val sl = StepLog(i + 1, step.describe()).also { log.steps += it }
                onStep(sl, steps.size)
                val t0 = device.now()
                runStep(step, slots, sl, recipe)
                sl.ms = device.now() - t0
            }
            finish(recipe, log)
        } catch (s: Stop) {
            log.outcome = s.outcome
            log.reason = s.reason
            log.steps.lastOrNull()?.takeIf { it.status == "running" }?.status = when (s.outcome) {
                Outcome.HANDED_OFF -> "blocked"; Outcome.STUCK -> "stuck"; else -> "failed"
            }
        }
        log.endedAt = device.now()
        return log
    }

    /**
     * Done rule: only a hard rule (the gate) confirms the payment screen — never the AI's word.
     * Recipes that end at payment get up to 3 "towards checkout" taps (View Cart, Checkout…) first:
     * the last hop is often a tap the app never reported while teaching. Every one goes through the gate.
     */
    private suspend fun finish(recipe: Recipe, log: RunLog) {
        var (root, pkg) = device.screen() ?: throw Stop(Outcome.FAILED, "screen unreadable at the end")
        if (recipe.end == End.PAYMENT_SCREEN) repeat(3) {
            if (SafetyGate.check(root, pkg) != null) return@repeat
            val next = root.walk().firstOrNull { n -> n.visible && n.clickable && n.walk().any { c -> c.label?.let(CHECKOUT::containsMatchIn) == true } }
                ?: return@repeat
            log.steps += StepLog(log.steps.size + 1, "tap ${next.walk().mapNotNull { it.label }.firstOrNull { CHECKOUT.containsMatchIn(it) }}", "ok", note = "towards checkout")
            if (device.actor.tap(next, root, pkg) !is GatedActor.Result.Done) return@repeat
            device.screen()?.let { root = it.first; pkg = it.second }
        }
        val block = SafetyGate.check(root, pkg)
        when {
            block != null -> { log.outcome = Outcome.HANDED_OFF; log.reason = block.reason; device.actor.handOff(block) }
            recipe.end == End.PAYMENT_SCREEN -> { log.outcome = Outcome.FAILED; log.reason = "all steps done but the payment screen never appeared" }
            else -> log.outcome = Outcome.DONE
        }
    }

    private suspend fun runStep(step: Step, slots: Map<String, String>, sl: StepLog, recipe: Recipe) {
        if (device.stopRequested) throw Stop(Outcome.STOPPED, "stopped by user")
        when (step.type) {
            StepType.LAUNCH -> {
                if (!device.launchClean(step.pkg!!)) throw Stop(Outcome.FAILED, "${step.pkg} is not installed")
                sl.status = "ok"
            }
            StepType.KEY -> { device.key(step.key!!); device.screen(); sl.status = "ok" }
            StepType.TAP, StepType.TYPE -> act(step, slots, sl)
            StepType.GOAL -> when (step.goal) {
                "search" -> search(step, slots, sl)
                "confirm_sheet" -> confirmSheet(sl, step.args["choices"]?.split(com.example.myna_mimicyourinteractionsautomate.record.Recorder.CHOICE_SEP).orEmpty())
                else -> { sl.status = "skipped"; sl.note = "goal ${step.goal} not built yet" }   // Phase 6
            }
        }
    }

    private suspend fun act(step: Step, slots: Map<String, String>, sl: StepLog) {
        val target = Slots.bind(step.target!!, slots)
        val start = device.now()
        var scrolls = 0
        var closedPopup = false
        var openedSearch = false
        val seen = mutableMapOf<String, Int>()
        while (true) {
            if (device.stopRequested) throw Stop(Outcome.STOPPED, "stopped by user")
            val (root, pkg) = device.screen() ?: throw Stop(Outcome.FAILED, "screen unreadable")
            val screen = Identity.screen(root, pkg, null)
            sl.screen = screen.signature

            // Safety first: a payment/credential screen ends the run here, zero taps.
            SafetyGate.check(root, pkg)?.let { device.actor.handOff(it); throw Stop(Outcome.HANDED_OFF, it.reason) }
            checkStuck(root, step, screen.signature, seen, start)

            val found = Finder.find(root, target) ?: ocrFind(target)?.let { Finder.Found(it, 4, "ocr \"${it.label}\"") }
            if (found != null) {
                sl.level = found.level
                sl.note = found.how
                SafetyGate.needsConfirm(found.node)?.let { risky ->
                    val ok = device.ask("The next step taps \"$risky\". Go ahead?", listOf("Yes", "No"))
                    if (ok != "Yes") throw Stop(Outcome.STUCK, "you said no to \"$risky\"")
                }
                val text = Slots.fill(step.text, slots)
                val r = if (step.type == StepType.TYPE) device.actor.type(found.node, text.orEmpty(), root, pkg, step.submit)
                        else device.actor.tap(found.node, root, pkg)
                when (r) {
                    GatedActor.Result.Done -> { verify(step, sl); sl.status = "ok"; return }
                    is GatedActor.Result.Blocked -> throw Stop(Outcome.HANDED_OFF, r.block.reason)
                    GatedActor.Result.Failed -> throw Stop(Outcome.FAILED, "the app refused the ${step.type.name.lowercase()} on ${found.how}")
                }
            }

            // Not found. Fallbacks, cheapest first; each one can only happen a bounded number of times.
            val close = Finder.closeButton(root)
            if (!closedPopup && close != null) {
                closedPopup = true
                sl.note = "closed a pop-up (${close.label ?: close.id})"
                device.actor.tap(close, root, pkg)
                continue
            }
            if (step.type == StepType.TYPE && !openedSearch) {
                // Universal search: the field often lives behind a search bar that must be tapped first.
                openedSearch = true
                Identity.searchBar(root)?.let { device.actor.tap(it, root, pkg); continue }
            }
            val list = root.walk().filter { it.visible && it.scrollable }.maxByOrNull { (it.r - it.l) * (it.b - it.t) }
            if (scrolls < MAX_SCROLLS && list != null) {
                scrolls++
                device.scroll(list, forward = target.scrollDir != "up")
                continue
            }
            throw Stop(Outcome.STUCK, "couldn't find ${describe(target)} on ${screen.title ?: screen.pkg} after $scrolls scrolls")
        }
    }

    /**
     * Universal search (design §4.5): type the query + Enter, then tap through results until we land on
     * a page that shows [pick] as a heading rather than as a list row. Rows without accessible text → OCR.
     */
    private suspend fun search(step: Step, slots: Map<String, String>, sl: StepLog) {
        val query = Slots.fill(step.text, slots).orEmpty()
        val pick = Slots.fill(step.args["pick"], slots)
        val start = device.now()
        // 1. Get a search field on screen and type into it.
        var typed = false
        for (attempt in 0 until 3) {
            val (root, pkg) = device.screen() ?: throw Stop(Outcome.FAILED, "screen unreadable")
            SafetyGate.check(root, pkg)?.let { device.actor.handOff(it); throw Stop(Outcome.HANDED_OFF, it.reason) }
            checkLang(root, step)
            val field = step.target?.let { Finder.find(root, it)?.node?.takeIf { n -> n.editable } }
                ?: root.walk().firstOrNull { it.visible && it.editable }
            if (field != null) {
                if (device.actor.type(field, query, root, pkg, submit = true) !is GatedActor.Result.Done)
                    throw Stop(Outcome.FAILED, "couldn't type \"$query\" into the search box")
                typed = true; break
            }
            Identity.searchBar(root)?.let { device.actor.tap(it, root, pkg) } ?: break
        }
        if (!typed) throw Stop(Outcome.STUCK, "no search box found for \"$query\"")
        if (pick == null) { sl.status = "ok"; return }

        // 2. Hop through results (suggestions → results → page) until pick is a heading, not a row.
        val want = Identity.loose(pick)
        var closedPopup = false
        for (hop in 0 until 5) {
            if (device.now() - start > stepTimeoutMs) break
            val (root, pkg) = device.screen() ?: throw Stop(Outcome.FAILED, "screen unreadable")
            SafetyGate.check(root, pkg)?.let { device.actor.handOff(it); throw Stop(Outcome.HANDED_OFF, it.reason) }
            val matches = root.walk().filter { it.visible && !it.editable && it.label?.let { l -> Identity.loose(l).contains(want) } == true }.toList()
            val rows = matches.filter { Identity.listItem(it) != null }.sortedWith(
                compareBy({ if (Identity.loose(it.label!!) == want) 0 else 1 }, { it.t }))
            val heading = matches.any { Identity.listItem(it) == null }
            val tapTarget = rows.firstOrNull()?.let(Finder::tappable)
                ?: if (heading && hop > 0) null else ocrFind(com.example.myna_mimicyourinteractionsautomate.recipe.Target(label = pick))
            if (tapTarget == null) {
                if (heading) { sl.status = "ok"; sl.note = "opened \"$pick\" after $hop tap(s)"; return }
                // Pop-up over the results/page: close-type buttons only (T7).
                Finder.closeButton(root)?.takeIf { !closedPopup }?.let { closedPopup = true; device.actor.tap(it, root, pkg) }
                continue   // results still loading
            }
            sl.level = if (tapTarget.cls == "OcrText") 4 else 1
            if (device.actor.tap(tapTarget, root, pkg) is GatedActor.Result.Blocked) throw Stop(Outcome.HANDED_OFF, "blocked while opening $pick")
        }
        throw Stop(Outcome.STUCK, "searched \"$query\" but couldn't open \"$pick\"")
    }

    /** Press the open sheet's main button (OCR "Add item ₹109", else its blank button box). Done when the sheet is gone. */
    private suspend fun confirmSheet(sl: StepLog, choices: List<String>) {
        device.pause(SHEET_ANIMATION_MS)   // sheets slide in; positions read mid-animation miss the button
        selectChoices(choices, sl)
        for (attempt in 0 until 3) {
            val (root, pkg) = device.screen() ?: throw Stop(Outcome.FAILED, "screen unreadable")
            SafetyGate.check(root, pkg)?.let { device.actor.handOff(it); throw Stop(Outcome.HANDED_OFF, it.reason) }
            if (!Identity.isModal(root)) { sl.status = "ok"; if (attempt == 0) sl.note = "no sheet open"; return }
            // Tree position first (exact), OCR second (reads pixels, can lag an animation).
            val box = Identity.blankSheetButton(root)
            val line = if (box != null) null else device.ocr().filter { it.t >= root.t + (root.b - root.t) * 3 / 4 && CONFIRM.containsMatchIn(it.text) }.maxByOrNull { it.r - it.l }
            val target = box ?: line?.let { UiNode(text = it.text, cls = "OcrText", clickable = true, l = it.l, t = it.t, r = it.r, b = it.b) }
                ?: throw Stop(Outcome.STUCK, "couldn't find the button that confirms the options sheet")
            sl.level = if (box != null) 2 else 4
            sl.note = "pressed ${line?.text?.let { "\"$it\"" } ?: "the sheet's main button ${box!!.bounds}"} (try ${attempt + 1})"
            if (device.actor.tap(target, root, pkg) is GatedActor.Result.Blocked) throw Stop(Outcome.HANDED_OFF, "blocked on the options sheet")
            device.pause(SHEET_ANIMATION_MS)
            if (attempt >= 1) break   // two tries: some apps (Zomato's "Add item") ignore accessibility taps entirely
        }
        // Ask for one finger tap instead of guessing further, then carry on once the sheet closes.
        device.prompt("Please tap the add button on the sheet. This app only accepts your finger there.")
        try {
            val deadline = device.now() + USER_TAP_WAIT_MS
            while (device.now() < deadline) {
                if (device.stopRequested) throw Stop(Outcome.STOPPED, "stopped by user")
                val (root, _) = device.screen() ?: continue
                if (!Identity.isModal(root)) { sl.status = "ok"; sl.note = "you tapped the sheet's add button (the app ignores accessibility taps there)"; return }
                device.pause(500)
            }
        } finally { device.prompt(null) }
        throw Stop(Outcome.STUCK, "the options sheet needs your tap and nobody tapped it within ${USER_TAP_WAIT_MS / 1000} seconds")
    }

    /** Habit defaults: make the sheet show the same options as the demo (required groups block "Add item" otherwise). */
    private suspend fun selectChoices(choices: List<String>, sl: StepLog) {
        val picked = mutableListOf<String>()
        for (choice in choices) {
            var scrolls = 0
            while (true) {
                val (root, pkg) = device.screen() ?: return
                SafetyGate.check(root, pkg)?.let { device.actor.handOff(it); throw Stop(Outcome.HANDED_OFF, it.reason) }
                val toggle = Identity.optionToggle(root, choice)
                if (toggle != null) {
                    if (!toggle.checked) { device.actor.tap(Identity.optionRow(toggle)?.let(Finder::tappable) ?: toggle, root, pkg); picked += choice }
                    break
                }
                val list = root.walk().filter { it.visible && it.scrollable }.maxByOrNull { (it.r - it.l) * (it.b - it.t) }
                if (list == null || scrolls++ >= 3) break          // not on this sheet: leave the app's default
                device.scroll(list, forward = true)
            }
        }
        if (picked.isNotEmpty()) sl.note = "selected ${picked.joinToString()}"
    }

    /** Wait for the screen to settle and compare with the demo's next screen. A mismatch is noted, not fatal: the next step's finder decides. */
    private suspend fun verify(step: Step, sl: StepLog) {
        val expected = step.next ?: return
        val (root, pkg) = device.screen() ?: return
        val now = Identity.screen(root, pkg, null)
        if (now.pkg != expected.pkg || (expected.title != null && now.title != null && now.title != expected.title))
            sl.note = (sl.note?.let { "$it; " } ?: "") + "unexpected screen ${now.title ?: now.pkg} (demo went to ${expected.title ?: expected.pkg})"
    }

    /** T10: stop with a specific reason instead of tapping around. */
    private fun checkStuck(root: UiNode, step: Step, signature: String, seen: MutableMap<String, Int>, start: Long) {
        checkLang(root, step)
        val n = (seen[signature] ?: 0) + 1
        seen[signature] = n
        if (n > SAME_SCREEN_LIMIT) throw Stop(Outcome.STUCK, "the same screen came back $SAME_SCREEN_LIMIT times without progress")
        if (device.now() - start > stepTimeoutMs) throw Stop(Outcome.STUCK, "no progress for ${stepTimeoutMs / 1000} seconds")
    }

    private fun checkLang(root: UiNode, step: Step) {
        if (step.screen?.lang == "en" && Identity.lang(root) == "hi")
            throw Stop(Outcome.STUCK, "the app is showing Hindi but I learned this in English")
    }

    /** Last resort for elements with no accessible text (Zomato's Compose suggestions): read the pixels. */
    private suspend fun ocrFind(t: com.example.myna_mimicyourinteractionsautomate.recipe.Target): UiNode? {
        val want = (t.key?.takeIf { it.by in OCR_KEYS }?.value ?: t.label)?.let(Identity::loose)?.takeIf { it.length >= 2 } ?: return null
        val lines = device.ocr()
        val line = lines.firstOrNull { Identity.loose(it.text) == want }
            ?: lines.filter { Identity.loose(it.text).startsWith(want) }.minByOrNull { it.t }
            ?: lines.filter { Identity.loose(it.text).contains(want) }.minByOrNull { it.t }
            ?: return null
        return UiNode(text = line.text, cls = "OcrText", clickable = true, l = line.l, t = line.t, r = line.r, b = line.b)
    }

    private fun describe(t: com.example.myna_mimicyourinteractionsautomate.recipe.Target) =
        "\"" + (t.key?.takeIf { it.by != KeyKind.POSITION }?.value ?: t.label ?: t.id ?: "the element") + "\""

    companion object {
        const val MAX_SCROLLS = 5
        const val SHEET_ANIMATION_MS = 800L
        const val USER_TAP_WAIT_MS = 30_000L
        private val CONFIRM = Regex("^(add item|add to cart|add|done|confirm|continue|save|apply|update)\\b", RegexOption.IGNORE_CASE)
        private val CHECKOUT = Regex("^(view cart|go to cart|checkout|proceed to checkout|proceed to buy|continue to checkout|continue)\\b", RegexOption.IGNORE_CASE)
        const val SAME_SCREEN_LIMIT = 3 + MAX_SCROLLS + 2   // scrolls/pop-up retries legitimately revisit a screen
        private val OCR_KEYS = setOf(KeyKind.OCR, KeyKind.LABEL, KeyKind.CHILD_TEXT, KeyKind.NEAR_TEXT)
    }
}
