package com.example.myna_mimicyourinteractionsautomate.a11y

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import org.json.JSONObject
import java.io.File

/**
 * Phase 0: while [dumping] is on, writes one JSON line per settled screen and per click to
 * files/dumps/<session>.jsonl. Pull with: adb pull /sdcard/Android/data/<pkg>/files/dumps
 */
class MynaService : AccessibilityService() {

    companion object {
        const val TAG = "Myna"
        @Volatile var instance: MynaService? = null
        @Volatile var dumping = false
            set(value) { field = value; if (value) instance?.newSession() }

        // ponytail: fixed list, grows when we meet more noise packages
        val IGNORED_PACKAGES = setOf("com.android.systemui", "com.google.android.inputmethod.latin",
            "com.samsung.android.honeyboard", "com.sec.android.app.launcher")
    }

    private val main = Handler(Looper.getMainLooper())
    private var out: File? = null
    private var lastPkg: String? = null
    private val settledDump = Runnable { dumpScreen("settled", null) }

    override fun onServiceConnected() {
        instance = this
        if (dumping) newSession()
        Log.i(TAG, "service connected")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {}

    fun newSession() {
        val dir = File(getExternalFilesDir(null), "dumps").apply { mkdirs() }
        out = File(dir, "dump-${System.currentTimeMillis()}.jsonl")
        Log.i(TAG, "dumping to ${out!!.absolutePath}")
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (!dumping) return
        val pkg = e.packageName?.toString() ?: return
        if (pkg == packageName || pkg in IGNORED_PACKAGES) return
        lastPkg = pkg
        when (e.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> dumpScreen("click", clickInfo(e))
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> log(JSONObject().put("event", "text")
                .put("pkg", pkg).put("text", e.text.joinToString(" ")).put("cls", e.className))
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // Settle: dump only after 700 ms without further changes.
                main.removeCallbacks(settledDump)
                main.postDelayed(settledDump, 700)
            }
        }
    }

    private fun clickInfo(e: AccessibilityEvent) = JSONObject()
        .put("cls", e.className).put("text", e.text.joinToString(" "))
        .put("desc", e.contentDescription).put("id", e.source?.viewIdResourceName)

    private fun dumpScreen(event: String, extra: JSONObject?) {
        val root = rootInActiveWindow ?: return
        val stats = TreeDump.Stats()
        val tree = TreeDump.dump(root, stats)
        log(JSONObject().put("event", event).put("pkg", root.packageName ?: lastPkg)
            .put("stats", stats.toJson()).put("target", extra).put("tree", tree))
        Log.i(TAG, "$event ${root.packageName} ${stats.toJson()}")
    }

    private fun log(line: JSONObject) {
        line.put("t", System.currentTimeMillis())
        out?.appendText(line.toString() + "\n")
    }
}
