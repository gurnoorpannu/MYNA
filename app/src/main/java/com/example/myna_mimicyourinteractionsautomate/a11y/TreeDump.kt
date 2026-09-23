package com.example.myna_mimicyourinteractionsautomate.a11y

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

/** Phase 0 spike: turn the accessibility tree into JSON plus the stats we need to answer "is this app automatable?". */
object TreeDump {

    class Stats {
        var nodes = 0
        var clickable = 0
        var clickableNoText = 0   // clickable, and no text/desc anywhere in its subtree → OCR candidate
        var clickableWithId = 0
        var webViews = 0
        var passwordFields = 0

        fun toJson() = JSONObject()
            .put("nodes", nodes).put("clickable", clickable).put("clickableNoText", clickableNoText)
            .put("clickableWithId", clickableWithId).put("webViews", webViews).put("passwordFields", passwordFields)
    }

    fun dump(root: AccessibilityNodeInfo, stats: Stats = Stats()): JSONObject = node(root, stats)

    private fun node(n: AccessibilityNodeInfo, stats: Stats): JSONObject {
        stats.nodes++
        val o = JSONObject()
        n.className?.let { o.put("cls", it.toString().substringAfterLast('.')) }
        n.text?.takeIf { it.isNotBlank() }?.let { o.put("text", it.toString()) }
        n.contentDescription?.takeIf { it.isNotBlank() }?.let { o.put("desc", it.toString()) }
        n.viewIdResourceName?.let { o.put("id", it.substringAfter(":id/")) }
        n.hintText?.takeIf { it.isNotBlank() }?.let { o.put("hint", it.toString()) }
        val r = Rect().also(n::getBoundsInScreen)
        o.put("b", "${r.left},${r.top},${r.right},${r.bottom}")
        val flags = buildString {
            if (n.isClickable) append('c')
            if (n.isLongClickable) append('l')
            if (n.isEditable) append('e')
            if (n.isScrollable) append('s')
            if (n.isCheckable) append(if (n.isChecked) 'X' else 'x')
            if (n.isPassword) append('p')
            if (!n.isVisibleToUser) append('i')
        }
        if (flags.isNotEmpty()) o.put("f", flags)

        if (n.className?.contains("WebView") == true) stats.webViews++
        if (n.isPassword) stats.passwordFields++
        if (n.isClickable) {
            stats.clickable++
            if (n.viewIdResourceName != null) stats.clickableWithId++
            if (!hasAnyText(n)) stats.clickableNoText++
        }

        val kids = JSONArray()
        for (i in 0 until n.childCount) n.getChild(i)?.let { kids.put(node(it, stats)) }
        if (kids.length() > 0) o.put("k", kids)
        return o
    }

    private fun hasAnyText(n: AccessibilityNodeInfo): Boolean {
        if (!n.text.isNullOrBlank() || !n.contentDescription.isNullOrBlank()) return true
        for (i in 0 until n.childCount) if (n.getChild(i)?.let(::hasAnyText) == true) return true
        return false
    }
}
