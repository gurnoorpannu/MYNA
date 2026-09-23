package com.example.myna_mimicyourinteractionsautomate.screen

/**
 * Plain-Kotlin copy of one accessibility node, captured once per screen.
 * Recorder, finder and safety gate all read this instead of live AccessibilityNodeInfo,
 * so the logic is unit-testable on the JVM.
 */
class UiNode(
    val text: String? = null,
    val desc: String? = null,
    val hint: String? = null,
    val id: String? = null,           // resource-id without "pkg:id/"
    val cls: String? = null,          // short class name ("Button")
    val l: Int = 0, val t: Int = 0, val r: Int = 0, val b: Int = 0,
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val password: Boolean = false,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val visible: Boolean = true,
    val children: List<UiNode> = emptyList(),
) {
    var parent: UiNode? = null
        private set
    /** The live AccessibilityNodeInfo this was captured from (Any so JVM tests don't need Android). */
    var live: Any? = null

    init { children.forEach { it.parent = this } }

    /** Own visible text, else content description. Blank or icon-font-only (Zomato's "\ue922") counts as none. */
    val label: String? get() = clean(text) ?: clean(desc)

    val index: Int get() = parent?.children?.indexOf(this) ?: 0
    val bounds: List<Int> get() = listOf(l, t, r, b)

    fun walk(): Sequence<UiNode> = sequence {
        yield(this@UiNode)
        children.forEach { yieldAll(it.walk()) }
    }

    fun ancestors(): Sequence<UiNode> = generateSequence(parent) { it.parent }

    private fun clean(s: String?) = s?.filterNot { it in '\uE000'..'\uF8FF' }?.trim()?.takeIf { it.isNotEmpty() }

    override fun toString() = "${cls ?: "?"}(${label ?: id ?: ""})"
}
