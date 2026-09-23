package com.example.myna_mimicyourinteractionsautomate.a11y

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.example.myna_mimicyourinteractionsautomate.screen.UiNode

/** Live accessibility tree → [UiNode] snapshot. */
object UiTree {

    fun capture(n: AccessibilityNodeInfo): UiNode {
        val r = Rect().also(n::getBoundsInScreen)
        val kids = (0 until n.childCount).mapNotNull { n.getChild(it) }.map(::capture)
        return UiNode(
            text = n.text?.toString(),
            desc = n.contentDescription?.toString(),
            hint = n.hintText?.toString()?.takeIf { it.isNotBlank() },
            id = n.viewIdResourceName?.substringAfter(":id/"),
            cls = n.className?.toString()?.substringAfterLast('.'),
            l = r.left, t = r.top, r = r.right, b = r.bottom,
            clickable = n.isClickable,
            editable = n.isEditable,
            scrollable = n.isScrollable,
            password = n.isPassword,
            checkable = n.isCheckable,
            checked = n.isChecked,
            visible = n.isVisibleToUser,
            children = kids,
        ).also { it.live = n }
    }

    /** The snapshot node that corresponds to a live node (e.g. an event's source). */
    fun find(root: UiNode, live: AccessibilityNodeInfo): UiNode? {
        val r = Rect().also(live::getBoundsInScreen)
        val id = live.viewIdResourceName?.substringAfter(":id/")
        val cls = live.className?.toString()?.substringAfterLast('.')
        return root.walk().firstOrNull { it.l == r.left && it.t == r.top && it.r == r.right && it.b == r.bottom && it.cls == cls && it.id == id }
    }
}
