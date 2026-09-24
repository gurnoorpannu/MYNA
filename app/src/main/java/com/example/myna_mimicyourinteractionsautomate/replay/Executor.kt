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
    private val aiHelper: Boolean = false,                      // one AI pick when a step is lost (off in tests)
) {
    // Never-taught extras from the command (T5/T6): quantity and delivery address.
    private var wantQty = 1
    private var qtyDone = false
    private var wantAddress: String? = null
    private var addressDone = false
    private var lastTapped: UiNode? = null

    private class Stop(val outcome: Outcome, val reason: String) : Exception(reason)

    /** The last submitted search (steps after it act on its results). */
    private var lastQuery: String? = null

    private fun bestRowFor(root: UiNode, like: UiNode, query: String): UiNode? {
        val want = Identity.stems(query).ifEmpty { return null }
        val twins = root.walk().filter { it.visible && it.clickable && it.label == like.label && it.id == like.id && it.cls == like.cls }.toList()
        return twins.mapNotNull { n ->
            // The row: the list card if there is one, else go up (Zomato nests ADD 5 deep) until there's real text.
            val row = Identity.listItem(n)?.takeIf { c -> c.walk().any { it !== n && (it.label?.length ?: 0) >= 12 } }
                ?: n.ancestors().take(7).firstOrNull { a -> a.walk().any { it !== n && (it.label?.length ?: 0) >= 12 } } ?: return@mapNotNull null
            val text = row.walk().mapNotNull { it.label }.joinToString(" ")
            // A sponsored PRODUCT with its own Add to cart is still that product (23 Sep: the only visible stand
            // was "Sponsored Ad - Adjustable Laptop Stand…"). Banners never have these buttons, so they can't match.
            val flat = Identity.loose(text)
            n to want.count { w -> Identity.loose(w).let { it.isNotEmpty() && flat.contains(it) } }
        }.filter { it.second > 0 }.maxByOrNull { it.second }?.first
    }

    /** Text typed by the previous step and not submitted yet: if the next target doesn't show up, press Enter. */
    private var unsubmitted: String? = null

    suspend fun run(recipe: Recipe, given: Map<String, String> = emptyMap()): RunLog {
        // Blanks not given fall back to the demo's value / default ("Margherita", qty 1…); given "" = leave it out.
        val slots = recipe.slots.mapNotNull { (k, v) -> (v.value ?: v.default)?.let { k to it } }.toMap() + given
        val log = RunLog(recipe.id, Slots.fill(recipe.summary ?: recipe.utterance, slots)!!, device.now(), slots = slots)
        wantQty = slots["qty"]?.toIntOrNull()?.coerceIn(1, 20) ?: 1
        wantAddress = slots["address"]?.takeIf { it.isNotBlank() }
        val steps = recipe.subtasks.flatMap { it.steps }.filter { !it.noise }
        try {
            var retries = 0
            steps.forEachIndexed { i, step ->
                val sl = StepLog(i + 1, Slots.fill(step.describe(), slots)!!).also { log.steps += it }
                onStep(sl, steps.size)
                val t0 = device.now()
                val pending = unsubmitted
                val query = lastQuery
                while (true) {
                    try {
                        // T6: the cart page's "Deliver to …" comes before Proceed/Checkout (Amazon).
                        if (wantAddress != null && !addressDone && step.type == StepType.TAP &&
                            (step.target?.label ?: step.target?.key?.value)?.let(CHECKOUT::containsMatchIn) == true) pickAddress(log)
                        runStep(step, slots, sl, recipe)
                        // T5: "2 margheritas" → set the count right after the item was added (Zomato sets it in the sheet).
                        if (wantQty > 1 && !qtyDone && isAddStep(step) && steps.getOrNull(i + 1)?.goal != "confirm_sheet") {
                            device.pause(800); qtyDone = setQty(wantQty, sl)
                        }
                        break
                    }
                    catch (s: Stop) {
                        // Opened a wrong result (an ad, a store page) and the next step's button isn't there:
                        // go back and open the next-best result instead of giving up.
                        val prev = steps.getOrNull(i - 1)
                        val afterSearch = prev != null && ((prev.type == StepType.TYPE && prev.submit) || (prev.goal == "search" && prev.args["pick"] == null))
                        if (s.outcome != Outcome.STUCK || retries >= 2 || !(prev?.goal == "pick_result" || afterSearch)) throw s
                        retries++
                        if (prev!!.goal == "pick_result") {
                            sl.note = "wrong result (${s.reason}); trying the next one"
                            device.key(SystemKey.BACK)
                            pickResult(Slots.fill(prev.args["query"], slots).orEmpty(), prev, sl)
                        } else {
                            // Still on the results page (opening a result wasn't recorded): open the best match, then retry.
                            sl.note = "opening the best result for \"${Slots.fill(prev.text, slots)}\" first"
                            if (retries > 1) device.key(SystemKey.BACK)
                            pickResult(Slots.fill(prev.text, slots).orEmpty(), prev, sl)
                        }
                    }
                }
                if (unsubmitted == pending) unsubmitted = null   // any other step consumes it
                if (lastQuery == query) lastQuery = null          // results only matter to the step right after the search
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
        // Towards the payment screen: close pop-ups (coupons, offers), tap View Cart/Continue, or give the page a second.
        var checkoutTaps = 0
        if (recipe.end == End.PAYMENT_SCREEN) for (i in 0 until 6) {
            if (SafetyGate.check(root, pkg) != null) break
            // ✕ icons / OCR "×" only while a pop-up is up: on a plain cart page they'd be delete buttons.
            val popup = Identity.isModal(root)
            val close = Finder.closeButton(root, anywhere = popup) ?: if (i > 0 && popup) ocrClose(root) else null
            val next = root.walk().firstOrNull { n -> n.visible && n.clickable && n.walk().any { c -> c.label?.let(CHECKOUT::containsMatchIn) == true } }
            when {
                close != null -> {
                    log.steps += StepLog(log.steps.size + 1, "close pop-up (${close.label ?: close.id})", "ok", note = "towards checkout")
                    device.actor.tap(close, root, pkg)
                }
                next != null && checkoutTaps < 3 -> {
                    checkoutTaps++
                    log.steps += StepLog(log.steps.size + 1, "tap ${next.walk().mapNotNull { it.label }.firstOrNull { CHECKOUT.containsMatchIn(it) }}", "ok", note = "towards checkout")
                    if (device.actor.tap(next, root, pkg) !is GatedActor.Result.Done) break
                }
                else -> device.pause(1_000)   // cart sheet still sliding in / loading
            }
            device.screen()?.let { root = it.first; pkg = it.second }
        }
        // T6 on Zomato: the address is picked on the cart itself (a Place Order screen: only address taps are allowed).
        if (wantAddress != null && !addressDone) { pickAddress(log); device.screen()?.let { root = it.first; pkg = it.second } }
        if (wantQty > 1 && !qtyDone) log.reason = "couldn't set the quantity to $wantQty — please change it"
        val block = SafetyGate.check(root, pkg)
        when {
            block != null -> {
                log.outcome = Outcome.HANDED_OFF
                log.reason = listOfNotNull(block.reason, log.reason, if (wantAddress != null && !addressDone) "couldn't switch the address to $wantAddress — please pick it" else null).joinToString("; ")
                device.actor.handOff(block)
            }
            recipe.end == End.PAYMENT_SCREEN -> { log.outcome = Outcome.FAILED; log.reason = "all steps done but the payment screen never appeared" }
            else -> log.outcome = Outcome.DONE
        }
    }

    private suspend fun runStep(step: Step, slots: Map<String, String>, sl: StepLog, recipe: Recipe) {
        if (device.stopRequested) throw Stop(Outcome.STOPPED, "stopped by user")
        when (step.type) {
            StepType.LAUNCH -> {
                if (!device.launchClean(step.pkg!!)) throw Stop(Outcome.FAILED, "couldn't open ${step.pkg} (not installed, or it didn't come to the front)")
                sl.status = "ok"
            }
            StepType.KEY -> { device.key(step.key!!); device.screen(); sl.status = "ok" }
            StepType.TAP, StepType.TYPE -> act(step, slots, sl)
            StepType.GOAL -> when (step.goal) {
                "search" -> search(step, slots, sl)
                "pick_result" -> pickResult(Slots.fill(step.args["query"], slots).orEmpty(), step, sl)
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
        var aiTried = false
        val seen = mutableMapOf<String, Int>()
        while (true) {
            if (device.stopRequested) throw Stop(Outcome.STOPPED, "stopped by user")
            val (root, pkg) = device.screen() ?: throw Stop(Outcome.FAILED, "screen unreadable")
            val screen = Identity.screen(root, pkg, null)
            sl.screen = screen.signature

            // Safety first: a payment/credential screen ends the run here, zero taps.
            SafetyGate.check(root, pkg)?.let { device.actor.handOff(it); throw Stop(Outcome.HANDED_OFF, it.reason) }
            checkStuck(root, step, screen.signature, seen, start)
            checkClosed(root)

            var found = Finder.find(root, target) ?: ocrFind(target)?.let { Finder.Found(it, 4, "ocr \"${it.label}\"") }
            // Many identical buttons right after a search ("Add to cart" on every Amazon result): use the one
            // in the row that best matches what was searched, never an ad's.
            if (found != null && lastQuery != null && step.type == StepType.TAP) {
                val row = bestRowFor(root, found!!.node, lastQuery!!)
                // Right after a search, a list button whose row doesn't match (or is an ad) is the wrong product:
                // treat as not found, so the "open the best result" recovery takes over.
                found = row?.let { found!!.copy(node = it, how = found!!.how + " in the best-matching row") }
                if (found == null) throw Stop(Outcome.STUCK, "no \"${target.label}\" in a result matching \"$lastQuery\"")
            }
            // A TYPE target that is only a search *button* (Amazon home): tap it to open the real field.
            if (found != null && step.type == StepType.TYPE && !found.node.editable) {
                val field = root.walk().firstOrNull { it.visible && it.editable }
                if (field != null) found = Finder.Found(field, found.level, "the text field")
                else if (!openedSearch) { openedSearch = true; device.actor.tap(found.node, root, pkg); continue }
            }
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
                    GatedActor.Result.Done -> {
                        lastTapped = found!!.node
                        if (step.type == StepType.TYPE && !step.submit) unsubmitted = text
                        if (step.type == StepType.TYPE && step.submit) lastQuery = text
                        verify(step, sl); sl.status = "ok"; return
                    }
                    is GatedActor.Result.Blocked -> throw Stop(Outcome.HANDED_OFF, r.block.reason)
                    GatedActor.Result.Failed -> throw Stop(Outcome.FAILED, "the app refused the ${step.type.name.lowercase()} on ${found.how}")
                }
            }

            // Not found. Fallbacks, cheapest first; each one can only happen a bounded number of times.
            unsubmitted?.let { q ->
                // The demo pressed the keyboard's search key after typing (apps don't report it): do the same.
                unsubmitted = null
                root.walk().firstOrNull { it.visible && it.editable }?.let { f ->
                    sl.note = "pressed search for \"$q\""
                    lastQuery = q
                    device.actor.type(f, q, root, pkg, submit = true)
                    continue
                }
            }
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
            // Web pages often mark nothing scrollable: then swipe the screen itself.
            val list = pageScroller(root)
            if (scrolls < MAX_SCROLLS) {
                scrolls++
                device.scroll(list, forward = target.scrollDir != "up")
                continue
            }
            // Last resort: one AI pick from a short numbered list of what's on screen (never random taps).
            if (aiHelper && !aiTried) {
                aiTried = true
                aiPick(root, step, target)?.let { n -> sl.note = "AI helper picked \"${n.label ?: Identity.primaryText(n) ?: n.id}\""; sl.level = 5
                    if (device.actor.tap(n, root, pkg) is GatedActor.Result.Done) { device.screen(); sl.status = "ok"; return } }
            }
            val soldOut = root.walk().mapNotNull { it.label }.firstOrNull(SOLD_OUT::containsMatchIn)
            throw Stop(Outcome.STUCK, "couldn't find ${describe(target)} on ${screen.title ?: screen.pkg} after $scrolls scrolls" +
                (soldOut?.let { " (the screen says \"$it\")" } ?: ""))
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
                if (pick == null) lastQuery = query   // with a pick we land on its page, not on a results list
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
            // Arrived: after at least one hop, the page names the pick as a heading (other "Domino's…" rows don't matter).
            if (heading && hop > 0) { sl.status = "ok"; sl.note = "opened \"$pick\" after $hop tap(s)"; return }
            val tapTarget = rows.firstOrNull()?.let(Finder::tappable)
                ?: if (heading && hop > 0) null else ocrFind(com.example.myna_mimicyourinteractionsautomate.recipe.Target(label = pick),
                    skipHeader = root.t + (root.b - root.t) * 12 / 100)
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
        // T5: set the count on the sheet's own stepper, before adding (after, "+" asks to repeat the customisation).
        if (wantQty > 1 && !qtyDone) qtyDone = setQty(wantQty, sl)
        for (attempt in 0 until 3) {
            val (root, pkg) = device.screen() ?: throw Stop(Outcome.FAILED, "screen unreadable")
            SafetyGate.check(root, pkg)?.let { device.actor.handOff(it); throw Stop(Outcome.HANDED_OFF, it.reason) }
            if (!Identity.isModal(root)) { sl.status = "ok"; if (attempt == 0) sl.note = "no sheet open"; return }
            // A labelled button first ("Add item", or "I'll choose" / "Repeat" on a repeat-customisation sheet),
            // then the blank drawn button (exact tree position), then OCR (pixels, can lag an animation).
            val sheet = Identity.sheetRoot(root)   // never the menu's own "ADD" buttons under the sheet
            val labelled = SHEET_BUTTONS.firstNotNullOfOrNull { re ->
                sheet.walk().firstOrNull { it.visible && it.clickable && it.walk().any { c -> c.label?.let(re::containsMatchIn) == true } }
            }
            val box = labelled ?: Identity.blankSheetButton(root)
            val line = if (box != null) null else device.ocr().filter { it.t >= sheet.t }.let { lines ->
                SHEET_BUTTONS.firstNotNullOfOrNull { re -> lines.filter { re.containsMatchIn(it.text) }.maxByOrNull { it.r - it.l } }
            }
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

    /** Last resort for a ✕ only the pixels show: a lone "X"/"×" in the top third of the screen. */
    private suspend fun ocrClose(root: UiNode): UiNode? {
        val top = root.t + (root.b - root.t) / 3
        return device.ocr().firstOrNull { it.t < top && it.text.trim() in setOf("X", "x", "×", "✕", "✖") }
            ?.let { UiNode(text = "×", cls = "OcrText", clickable = true, l = it.l, t = it.t, r = it.r, b = it.b) }
    }

    /** Open the highest result whose title holds most of the search words ("s25ultra phone cases" ~ "…Case for Galaxy S25 Ultra"). */
    private val opened = mutableSetOf<String>()

    private suspend fun pickResult(query: String, step: Step, sl: StepLog) {
        val want = Identity.stems(query).ifEmpty { throw Stop(Outcome.FAILED, "empty search") }
        for (attempt in 0..MAX_SCROLLS) {
            val (root, pkg) = device.screen() ?: throw Stop(Outcome.FAILED, "screen unreadable")
            SafetyGate.check(root, pkg)?.let { device.actor.handOff(it); throw Stop(Outcome.HANDED_OFF, it.reason) }
            checkLang(root, step)
            val best = root.walk().filter { it.visible && it.clickable && !it.editable }.mapNotNull { n ->
                val title = (n.label ?: Identity.primaryText(n))?.takeIf { it.length >= 12 } ?: return@mapNotNull null
                val flat = Identity.loose(title)
                // The search bar echoes the query; a result never equals it exactly. Ads first on the page aren't results.
                if (flat == Identity.loose(query) || n.id?.contains("search", ignoreCase = true) == true || title in opened) return@mapNotNull null
                // Ad links are labelled with their web address (…sspa…&keywords=laptop+stand): not a title, not a result.
                if (URLISH.containsMatchIn(title)) return@mapNotNull null
                // The row itself says it's an ad ("Sponsored Ad - …", or a "Sponsored" tag inside the row).
                if (n.walk().any { c -> c.label?.let(AD::containsMatchIn) == true }) return@mapNotNull null
                val score = want.count { w -> Identity.loose(w).let { it.isNotEmpty() && flat.contains(it) } }.toDouble() / want.size
                Triple(n, title, score).takeIf { score >= 0.5 }
            }.sortedWith(compareBy({ -it.third }, { it.first.t })).firstOrNull()
            if (best != null) {
                opened += best.second
                sl.level = 1; sl.note = "opened \"${best.second.take(50)}\" (${(best.third * 100).toInt()}% of the search words)"
                if (device.actor.tap(best.first, root, pkg) is GatedActor.Result.Blocked) throw Stop(Outcome.HANDED_OFF, "blocked opening a result")
                device.screen(); sl.status = "ok"; return
            }
            val list = pageScroller(root)
            device.scroll(list, forward = true)
        }
        throw Stop(Outcome.STUCK, "no result matches \"$query\"")
    }

    /**
     * What to scroll to reach more of the page: the biggest vertical list if it's a real part of the screen,
     * else the page itself (Amazon's product page: the only "scrollable" is a sideways offers carousel).
     */
    private fun pageScroller(root: UiNode): UiNode =
        root.walk().filter { it.visible && it.scrollable && (it.b - it.t) * 10 >= (root.b - root.t) * 4 && !(it.cls?.contains("Horizontal") ?: false) }
            .maxByOrNull { (it.r - it.l) * (it.b - it.t) } ?: root

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
                val list = pageScroller(root)
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

    /** T10: a closed restaurant / shop is a clear stop, not a hunt for buttons that aren't there. */
    private fun checkClosed(root: UiNode) {
        root.walk().filter { it.visible }.mapNotNull { it.label }.firstOrNull(CLOSED::containsMatchIn)?.let { line ->
            throw Stop(Outcome.STUCK, "it's closed right now — the app says \"${line.take(80)}\"")
        }
    }

    /** Was this the step that put the item in the cart? ("ADD", "Add to cart", or the options sheet's add). */
    private fun isAddStep(step: Step) = step.goal == "confirm_sheet" ||
        (step.target?.label ?: step.target?.key?.value)?.let(ADD_WORD::containsMatchIn) == true

    /**
     * T5: tap + (or −) on the stepper until its count reads [n]. The stepper = a number with a plus/minus beside it
     * (Zomato: "− 1 +" with ids button_remove/button_add; Amazon results: − 1 +). Nearest to the last tap wins.
     */
    private suspend fun setQty(n: Int, sl: StepLog): Boolean {
        repeat(n + 4) {
            val (root, pkg) = device.screen() ?: return false
            val area = if (Identity.isModal(root)) Identity.sheetRoot(root) else root
            val steppers = area.walk().filter { it.visible && it.label?.matches(Regex("\\d{1,2}")) == true }.mapNotNull { c ->
                val box = c.ancestors().take(2).lastOrNull() ?: return@mapNotNull null
                val plus = box.walk().firstOrNull { it.clickable && it !== c && isPlus(it) } ?: return@mapNotNull null
                Triple(c.label!!.toInt(), plus, box.walk().firstOrNull { it.clickable && it !== c && isMinus(it) })
            }.toList()
            val near = lastTapped
            val (count, plus, minus) = steppers.minByOrNull { (_, p, _) -> if (near == null) 0 else kotlin.math.abs(p.t - near.t) } ?: return false
            if (count == n) { sl.note = listOfNotNull(sl.note, "quantity set to $n").joinToString("; "); return true }
            val btn = (if (count < n) plus else minus) ?: return false
            if (device.actor.tap(btn, root, pkg) !is GatedActor.Result.Done) return false
            lastTapped = btn
            // "+" on a customised dish: "Repeat last customisation?" → Repeat.
            device.screen()?.let { (r2, p2) ->
                if (Identity.isModal(r2)) Identity.sheetRoot(r2).walk().firstOrNull { it.visible && it.clickable && it.walk().any { c -> c.label?.let(REPEAT::containsMatchIn) == true } }
                    ?.let { device.actor.tap(it, r2, p2) }
            }
        }
        return false
    }

    private fun isPlus(n: UiNode) = n.label == "+" || n.text == "\ue922" ||
        listOfNotNull(n.id, n.desc).any { Regex("(^|_)(add|plus|incr|increase)", RegexOption.IGNORE_CASE).containsMatchIn(it) && !it.contains("cart", true) }
    private fun isMinus(n: UiNode) = n.label == "-" || n.label == "−" || n.text == "\ue890" ||
        listOfNotNull(n.id, n.desc).any { Regex("(^|_)(remove|minus|decr|decrease)", RegexOption.IGNORE_CASE).containsMatchIn(it) }

    /**
     * T6: switch the delivery address to [wantAddress] ("Work"): open the address picker ("Delivering to Home",
     * "Deliver to …", "Change") and pick the saved address with that name. Gate-checked in address mode.
     */
    private suspend fun pickAddress(log: RunLog) {
        val name = wantAddress ?: return
        val names = when (name.lowercase()) { "work", "office" -> listOf("work", "office"); else -> listOf(name.lowercase()) }
        val named = { l: String -> names.any { Regex("\\b$it\\b", RegexOption.IGNORE_CASE).containsMatchIn(l) } }
        repeat(5) {
            val (root, pkg) = device.screen() ?: return
            val texts = root.walk().filter { it.visible }.toList()
            // Already delivering there?
            if (texts.any { n -> n.label?.let { l -> DELIVERING.containsMatchIn(l) && named(l) } == true ||
                    (n.label?.let(named) == true && n.parent?.walk()?.any { c -> c.label?.let(DELIVERING::containsMatchIn) == true } == true) }) {
                addressDone = true; log.steps += StepLog(log.steps.size + 1, "deliver to $name", "ok", note = "address set"); return
            }
            // Picker open (a sheet/list with the saved addresses): tap the one with that name.
            val row = texts.firstOrNull { it.clickable && it.walk().any { c -> c.label?.let(named) == true } }
            val opener = texts.firstOrNull { it.clickable && it.walk().any { c -> c.label?.let { l -> DELIVERING.containsMatchIn(l) || l.equals("change", true) } == true } }
            val tap = if (row != null && (Identity.isModal(root) || opener == null)) row else opener ?: row ?: return
            if (device.actor.tap(tap, root, pkg, addressPick = true) !is GatedActor.Result.Done) return
            // Some apps ask to confirm the picked address.
            device.screen()?.let { (r2, p2) ->
                r2.walk().firstOrNull { it.visible && it.clickable && it.label?.let(CONFIRM_ADDRESS::containsMatchIn) == true }?.let { device.actor.tap(it, r2, p2, addressPick = true) }
            }
        }
    }

    /** One AI call: which numbered element does this step (or ask / stuck)? Screen shortened, private data masked. */
    private suspend fun aiPick(root: UiNode, step: Step, target: com.example.myna_mimicyourinteractionsautomate.recipe.Target): UiNode? {
        val items = root.walk().filter { it.visible && (it.clickable || it.editable) }.mapNotNull { n ->
            (n.label ?: Identity.primaryText(n) ?: n.desc ?: n.id)?.let { n to Privacy.mask(it).take(60) }
        }.take(60).toList()
        if (items.isEmpty()) return null
        val prompt = buildString {
            appendLine("You help replay a phone task. Current step: ${step.describe()}" + (step.why.takeIf { it.isNotBlank() }?.let { " (why: $it)" } ?: ""))
            appendLine("It usually taps: ${target.label ?: target.key?.value ?: target.id} near ${target.nearby.take(3)}")
            appendLine("Tappable things on screen:")
            items.forEachIndexed { i, (n, t) -> appendLine("<button id=$i>${t}</button>" + if (n.editable) " (text field)" else "") }
            appendLine("Pick the ONE id that does this step. If none fits, action = stuck with a short reason. Never pick pay/order/login buttons.")
        }
        val out = runCatching { com.example.myna_mimicyourinteractionsautomate.llm.Llm.llm(prompt, AI_SCHEMA) }.getOrNull() ?: return null
        if (out.optString("action") != "tap") return null
        return items.getOrNull(out.optInt("id", -1))?.first   // invalid id → nothing (then the normal stuck message)
    }

    private fun checkLang(root: UiNode, step: Step) {
        if (step.screen?.lang == "en" && Identity.lang(root) == "hi")
            throw Stop(Outcome.STUCK, "the app is showing Hindi but I learned this in English")
    }

    /** Last resort for elements with no accessible text (Zomato's Compose suggestions): read the pixels. */
    private suspend fun ocrFind(t: com.example.myna_mimicyourinteractionsautomate.recipe.Target, skipHeader: Int = 0): UiNode? {
        val want = (t.key?.takeIf { it.by in OCR_KEYS }?.value ?: t.label)?.let(Identity::loose)?.takeIf { it.length >= 2 } ?: return null
        // The header ("< Domino's Pizza" = back arrow + search echo) is never the result.
        val lines = device.ocr().filter { it.t >= skipHeader && !it.text.trimStart().startsWith("<") }
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
        private val CLOSED = Regex("\\b(currently (closed|unavailable|not accepting orders)|closed (now|for (today|now|the day))|opens (at|tomorrow|in)\\b|" +
            "temporarily closed|not accepting orders|isn.t accepting orders|not delivering to your|outside (the )?delivery area|store is closed)", RegexOption.IGNORE_CASE)
        private val SOLD_OUT = Regex("\\b(sold out|out of stock|currently unavailable|not available)\\b", RegexOption.IGNORE_CASE)
        private val ADD_WORD = Regex("^(add|add to (cart|bag|basket))\\b", RegexOption.IGNORE_CASE)
        private val REPEAT = Regex("^(repeat)\\b", RegexOption.IGNORE_CASE)
        private val DELIVERING = Regex("\\b(deliver(ing)?|delivery) (to|at)\\b", RegexOption.IGNORE_CASE)
        private val CONFIRM_ADDRESS = Regex("^(deliver here|use this address|confirm (location|address)|done)\\b", RegexOption.IGNORE_CASE)
        private val AI_SCHEMA = org.json.JSONObject("""{"title":"helper","type":"object","required":["action"],
            "properties":{"action":{"type":"string","enum":["tap","stuck"]},"id":{"type":["integer","null"]},"reason":{"type":"string"}}}""")
        private val URLISH = Regex("(^ref=|https?://|sspa|[?&][a-z_]+=)", RegexOption.IGNORE_CASE)
        private val AD = Regex("^(sponsored|ad)\\b|\\bsponsored (ad|information)\\b", RegexOption.IGNORE_CASE)
        const val SHEET_ANIMATION_MS = 800L
        const val USER_TAP_WAIT_MS = 30_000L
        /** Sheet buttons in order of preference: confirm → pick options fresh (demo defaults) → repeat last time. */
        private val SHEET_BUTTONS = listOf(
            Regex("^(add item|add to cart|add|done|confirm|continue|save|apply|update)\\b", RegexOption.IGNORE_CASE),
            Regex("^(i.ll choose|choose|add new|customi[sz]e)\\b", RegexOption.IGNORE_CASE),
            Regex("^(repeat)\\b", RegexOption.IGNORE_CASE),
        )
        private val CHECKOUT = Regex("^(view cart|go to cart|checkout|proceed to checkout|proceed to buy|continue to checkout|continue)\\b", RegexOption.IGNORE_CASE)
        const val SAME_SCREEN_LIMIT = 3 + MAX_SCROLLS + 2   // scrolls/pop-up retries legitimately revisit a screen
        private val OCR_KEYS = setOf(KeyKind.OCR, KeyKind.LABEL, KeyKind.CHILD_TEXT, KeyKind.NEAR_TEXT)
    }
}
