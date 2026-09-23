package com.example.myna_mimicyourinteractionsautomate.a11y

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.Toast
import com.example.myna_mimicyourinteractionsautomate.MainActivity
import com.example.myna_mimicyourinteractionsautomate.recipe.RecipeJson
import com.example.myna_mimicyourinteractionsautomate.recipe.Screen
import com.example.myna_mimicyourinteractionsautomate.recipe.SystemKey
import com.example.myna_mimicyourinteractionsautomate.record.Recorder
import com.example.myna_mimicyourinteractionsautomate.screen.Identity
import com.example.myna_mimicyourinteractionsautomate.screen.UiNode
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.io.File

/**
 * The one AccessibilityService. Phase 0: tree dumps. Phase 1: teach recorder.
 * Files: /sdcard/Android/data/<pkg>/files/{dumps,recordings}
 */
class MynaService : AccessibilityService() {

    companion object {
        const val TAG = "Myna"
        const val SETTLE_MS = 600L     // no UI change for this long = screen settled (recorder + replay)
        @Volatile var instance: MynaService? = null
        @Volatile var dumping = false
            set(value) { field = value; if (value) instance?.newDumpSession() }

        // ponytail: fixed list; add packages as we meet more noise (dialer, OEM overlays…)
        val IGNORED_PACKAGES = setOf("com.android.systemui", "com.google.android.dialer", "com.samsung.android.incallui",
            "com.android.incallui", "com.google.android.permissioncontroller")
    }

    private val main = Handler(Looper.getMainLooper())
    private var dumpFile: File? = null
    private var lastPkg: String? = null
    private var lastChangeAt = 0L
    private val settled = Runnable { onSettled() }

    private val launcherPkg by lazy {
        packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            ?.activityInfo?.packageName
    }
    private val imePkgs by lazy {
        getSystemService(InputMethodManager::class.java).enabledInputMethodList.map { it.packageName }.toSet()
    }

    // --- recording state ---
    var recorder: Recorder? = null
        private set
    private var fgPkg: String? = null            // package currently in front (launcher counts)
    private var awaitingApp: String? = null      // set during clean start
    private var prevSettled: Pair<UiNode, Screen>? = null
    private var stepsAtPrevSettle = 0
    private val activityOf = mutableMapOf<String, String>()
    private var overlay: Button? = null

    override fun onServiceConnected() {
        instance = this
        if (dumping) newDumpSession()
        Log.i(TAG, "service connected, launcher=$launcherPkg")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {}

    // ---------------------------------------------------------------- events

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        val pkg = e.packageName?.toString() ?: return
        if (pkg == packageName || pkg in IGNORED_PACKAGES || pkg in imePkgs) return
        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                onWindow(pkg, e.className?.toString())
                scheduleSettle()
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> scheduleSettle()
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                e.source?.let { onClick(pkg, it) }
                if (dumping) dumpScreen("click", JSONObject().put("text", e.text.joinToString(" ")).put("id", e.source?.viewIdResourceName))
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> e.source?.let { onTextChanged(pkg, it) }
        }
        lastPkg = pkg
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP && event.keyCode == KeyEvent.KEYCODE_BACK) recorder?.onKey(SystemKey.BACK)
        updateOverlay()
        return false   // never swallow keys
    }

    private fun onWindow(pkg: String, cls: String?) {
        if (cls != null && isActivity(pkg, cls)) activityOf[pkg] = cls
        val rec = recorder ?: return
        // Clean start in progress: ignore everything until the target app is in front.
        awaitingApp?.let { if (pkg == it) { awaitingApp = null; fgPkg = pkg; updateOverlay() }; return }
        // Background windows (Samsung home's Google feed) fire events too; only the active window counts.
        if (pkg == fgPkg || rootInActiveWindow?.packageName?.toString() != pkg) return
        when {
            pkg == launcherPkg -> if (rec.steps.isNotEmpty()) rec.onKey(SystemKey.HOME)
            fgPkg == launcherPkg -> rec.onLaunch(pkg)   // opened from the launcher
            // else: the app handed off to another app (share sheet, payment…) — its taps are recorded as usual
        }
        fgPkg = pkg
        updateOverlay()
    }

    private fun onClick(pkg: String, src: AccessibilityNodeInfo) {
        val rec = recorder ?: return
        if (pkg == launcherPkg) return
        val (root, node) = snapshotAround(src) ?: return
        rec.onTap(Identity.target(node, root, rec.spoken), screenOf(root, pkg))
        updateOverlay()
    }

    private fun onTextChanged(pkg: String, src: AccessibilityNodeInfo) {
        val rec = recorder ?: return
        if (src.isPassword) return stopRecording("safety_gate")   // Phase 2 gate will generalise this
        val (root, node) = snapshotAround(src) ?: return
        val text = if (src.isShowingHintText) "" else src.text?.toString().orEmpty()
        rec.onText(Identity.target(node, root, rec.spoken), text, screenOf(root, pkg))
        updateOverlay()
    }

    private fun scheduleSettle() {
        lastChangeAt = System.currentTimeMillis()
        main.removeCallbacks(settled)
        main.postDelayed(settled, SETTLE_MS)
    }

    private fun onSettled() {
        if (dumping) dumpScreen("settled", null)
        val rec = recorder ?: return
        val live = rootInActiveWindow ?: return
        val pkg = live.packageName?.toString() ?: return
        if (pkg == packageName || pkg == launcherPkg || pkg in IGNORED_PACKAGES) return
        val root = UiTree.capture(live)
        val screen = screenOf(root, pkg)
        // New activity but no step since the last settled screen → the app ate the click event.
        val prev = prevSettled
        if (prev != null && rec.steps.size == stepsAtPrevSettle && screen.title != prev.second.title) {
            Identity.inferTap(prev.first, root)?.let { rec.onInferredTap(Identity.target(it, prev.first, rec.spoken), prev.second) }
        }
        prevSettled = root to screen
        stepsAtPrevSettle = rec.steps.size
        rec.onScreen(screen, Identity.compact(root))
        if (root.walk().any { it.password && it.visible }) stopRecording("safety_gate")
    }

    /** Replay (Phase 3) waits on this; same rule the recorder uses. */
    suspend fun awaitSettled(timeoutMs: Long = 6_000): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (System.currentTimeMillis() - lastChangeAt >= SETTLE_MS) return true
            delay(50)
        }
        return false
    }

    private fun snapshotAround(src: AccessibilityNodeInfo): Pair<UiNode, UiNode>? {
        val live = src.window?.root ?: rootInActiveWindow ?: return null
        val root = UiTree.capture(live)
        val node = UiTree.find(root, src) ?: UiTree.capture(src)
        return root to node
    }

    private fun screenOf(root: UiNode, pkg: String): Screen = Identity.screen(root, pkg, activityOf[pkg])

    private fun isActivity(pkg: String, cls: String) =
        runCatching { packageManager.getActivityInfo(ComponentName(pkg, cls), 0) }.isSuccess

    // ---------------------------------------------------------------- teach session

    /** Clean start (Home, then launch from the launcher) and begin recording. False if the app isn't installed. */
    fun startRecording(utterance: String, app: String): Boolean {
        val launch = packageManager.getLaunchIntentForPackage(app) ?: return false
        recorder = Recorder(utterance, app).also { it.onLaunch(app) }
        prevSettled = null
        awaitingApp = app
        performGlobalAction(GLOBAL_ACTION_HOME)
        main.postDelayed({
            startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        }, 500)
        showOverlay()
        Toast.makeText(this, "Your turn: do the task yourself. Tap ■ Done when finished.", Toast.LENGTH_LONG).show()
        return true
    }

    fun stopRecording(stoppedBy: String = "user") {
        val rec = recorder ?: return
        recorder = null
        hideOverlay()
        val recording = rec.finish(stoppedBy)
        val dir = File(getExternalFilesDir(null), "recordings").apply { mkdirs() }
        val file = File(dir, "rec-${recording.startedAt}.json")
        file.writeText(RecipeJson.encodeToString(recording))
        Log.i(TAG, "saved ${recording.steps.size} steps → $file (stopped by $stoppedBy)")
        val why = if (stoppedBy == "safety_gate") "Stopped at a secure screen — your turn. " else ""
        Toast.makeText(this, "${why}Saved ${recording.steps.size} steps", Toast.LENGTH_LONG).show()
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    private fun showOverlay() {
        if (overlay != null) return
        val btn = Button(this).apply { setOnClickListener { stopRecording("user") } }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.END; y = 300 }
        getSystemService(WindowManager::class.java).addView(btn, lp)
        overlay = btn
        updateOverlay()
    }

    private fun updateOverlay() {
        val rec = recorder ?: return
        val n = rec.steps.count { !it.noise } - 1   // don't count the automatic launch
        overlay?.text = if (awaitingApp != null) "● opening app…" else if (n == 0) "● REC: show me  ■ Done" else "● REC $n  ■ Done"
    }

    private fun hideOverlay() {
        overlay?.let { getSystemService(WindowManager::class.java).removeView(it) }
        overlay = null
    }

    // ---------------------------------------------------------------- Phase 0 dumps

    fun newDumpSession() {
        val dir = File(getExternalFilesDir(null), "dumps").apply { mkdirs() }
        dumpFile = File(dir, "dump-${System.currentTimeMillis()}.jsonl")
        Log.i(TAG, "dumping to $dumpFile")
    }

    private fun dumpScreen(event: String, extra: JSONObject?) {
        val root = rootInActiveWindow ?: return
        val stats = TreeDump.Stats()
        val tree = TreeDump.dump(root, stats)
        val line = JSONObject().put("t", System.currentTimeMillis()).put("event", event)
            .put("pkg", root.packageName ?: lastPkg).put("stats", stats.toJson()).put("target", extra).put("tree", tree)
        dumpFile?.appendText(line.toString() + "\n")
        Log.i(TAG, "$event ${root.packageName} ${stats.toJson()}")
    }
}
