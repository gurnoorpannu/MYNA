package com.example.myna_mimicyourinteractionsautomate.safety

import com.example.myna_mimicyourinteractionsautomate.screen.UiNode

/**
 * The ONLY way replay touches another app. Every tap and every typed text goes through the gate first;
 * [click]/[setText] are never called when it blocks. Keep it that way: no other code may call
 * performAction/dispatchGesture on another app's nodes.
 */
class GatedActor(
    private val click: (UiNode) -> Boolean,
    private val setText: (UiNode, String, Boolean) -> Boolean,   // (field, text, press Enter after)
    private val onBlocked: (SafetyGate.Block) -> Unit,
) {
    sealed interface Result {
        data object Done : Result
        data object Failed : Result
        data class Blocked(val block: SafetyGate.Block) : Result
    }

    /** Give control to the user (banner + voice) without trying anything first. */
    fun handOff(block: SafetyGate.Block) = onBlocked(block)

    fun tap(target: UiNode, root: UiNode, pkg: String?, addressPick: Boolean = false): Result {
        SafetyGate.checkTap(target, root, pkg, addressPick)?.let { if (!addressPick) onBlocked(it); return Result.Blocked(it) }
        return if (click(target)) Result.Done else Result.Failed
    }

    fun type(target: UiNode, text: String, root: UiNode, pkg: String?, submit: Boolean = false): Result {
        SafetyGate.checkTap(target, root, pkg)?.let { onBlocked(it); return Result.Blocked(it) }
        return if (setText(target, text, submit)) Result.Done else Result.Failed
    }
}
