package com.example.myna_mimicyourinteractionsautomate.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Color
import android.graphics.Path
import android.speech.tts.TextToSpeech
import android.widget.TextView
import android.content.ComponentName
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Bundle
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
import com.example.myna_mimicyourinteractionsautomate.recipe.KeyKind
import com.example.myna_mimicyourinteractionsautomate.recipe.RecipeJson
import com.example.myna_mimicyourinteractionsautomate.recipe.Target
import com.example.myna_mimicyourinteractionsautomate.recipe.UniqueKey
import com.example.myna_mimicyourinteractionsautomate.recipe.Screen
import com.example.myna_mimicyourinteractionsautomate.recipe.SystemKey
import com.example.myna_mimicyourinteractionsautomate.recipe.StepType
import com.example.myna_mimicyourinteractionsautomate.record.Recorder
import com.example.myna_mimicyourinteractionsautomate.safety.GatedActor
import com.example.myna_mimicyourinteractionsautomate.safety.SafetyGate
import com.example.myna_mimicyourinteractionsautomate.screen.Identity
import com.example.myna_mimicyourinteractionsautomate.screen.UiNode
import com.example.myna_mimicyourinteractionsautomate.recipe.Recipe
import com.example.myna_mimicyourinteractionsautomate.recipe.Recipes
import com.example.myna_mimicyourinteractionsautomate.replay.Device
import com.example.myna_mimicyourinteractionsautomate.replay.Executor
import com.example.myna_mimicyourinteractionsautomate.replay.OcrLine
import com.example.myna_mimicyourinteractionsautomate.replay.Outcome
import com.example.myna_mimicyourinteractionsautomate.replay.RunLog
import android.graphics.Bitmap
import android.view.Display
import android.widget.LinearLayout
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.io.File

/**
 * The one AccessibilityService. Phase 0: tree dumps. Phase 1: teach recorder.
 * Files: /sdcard/Android/data/<pkg>/files/{dumps,recordings}
 */
class MynaService : AccessibilityService(), Device {

    companion object {
        const val TAG = "Myna"
        const val SETTLE_MS = 600L
        const val TICK_MS = 1_500L
        private val NOISY_EVENTS = setOf(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED, AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED)     // no UI change for this long = screen settled (recorder + replay)
        @Volatile var instance: MynaService? = null
        /** MainActivity listens so the list refreshes when a compile or run finishes. */
        @Volatile var onChanged: (() -> Unit)? = null
        /** Last compile's extras for the UI: removed steps and questions. */
        @Volatile var lastCompile: com.example.myna_mimicyourinteractionsautomate.compile.Compiler.Result? = null
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

    /** Pages with carousels/video (Amazon, Zomato) never go still: while teaching, also look every 1.5 s. */
    private val tick = object : Runnable {
        override fun run() {
            if (recorder == null) return
            if (System.currentTimeMillis() - lastChangeAt >= TICK_MS) onSettled()   // nothing moved since: already covered
            else captureFront()?.let { (root, _) ->
                val sig = root.walk().mapNotNull { it.label }.toSet().hashCode()
                if (sig != lastTickSig) { lastTickSig = sig; onSettled() }
            }
            main.postDelayed(this, TICK_MS)
        }
    }
    private var lastTickSig = 0

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
    private val sheetChoices = linkedSetOf<String>()
    private var typingWithKeyboard = false             // a text event arrived while recording; keyboard was up   // options seen selected while the current sheet was open (user may scroll)
    private val activityOf = mutableMapOf<String, String>()
    private var overlay: Button? = null
    private var handOffView: TextView? = null
    private var tts: TextToSpeech? = null

    /** Replay's only way to act on other apps: every tap/type is checked by the safety gate first. */
    override val actor = GatedActor(click = ::clickNode, setText = ::setNodeText, onBlocked = { handOff(it) })

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    @Volatile override var stopRequested = false
        private set
    var running = false
        private set
    private var runOverlay: Button? = null

    override fun onServiceConnected() {
        instance = this
        if (dumping) newDumpSession()
        tts = TextToSpeech(this) {}
        if (com.example.myna_mimicyourinteractionsautomate.BuildConfig.DEBUG) registerDebugHook()
        Log.i(TAG, "service connected, launcher=$launcherPkg")
    }

    override fun onDestroy() {
        instance = null
        scope.cancel()
        tts?.shutdown()
        super.onDestroy()
    }

    override fun onInterrupt() {}

    // ---------------------------------------------------------------- events

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        val pkg = e.packageName?.toString() ?: return
        if (pkg == packageName || pkg in IGNORED_PACKAGES || pkg in imePkgs) return
        // Debug: which events does a tap inside a web page produce? (content/scroll noise skipped)
        if (recorder != null && e.eventType !in NOISY_EVENTS) {
            val line = "${System.currentTimeMillis()} $pkg ${AccessibilityEvent.eventTypeToString(e.eventType)} ${e.className} " +
                "\"${e.text.joinToString(" ").take(80)}\" desc=${e.contentDescription?.take(60)} src=${e.source?.let { "${it.className}/${it.viewIdResourceName}/${it.text?.take(40)}" }}"
            File(getExternalFilesDir(null), "events.log").appendText(line + "\n")   // logcat's buffer is too small
        }
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
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> e.source?.let { onTextChanged(pkg, it, e.text.joinToString("")) }
            // Focus jumped from the text field to the page (Amazon's results WebView): the search was submitted.
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> recorder?.let { rec ->
                if (typingWithKeyboard && e.source?.isEditable == false) {
                    typingWithKeyboard = false
                    if (rec.steps.lastOrNull()?.type == StepType.TYPE) rec.markSubmit()
                }
            }
        }
        lastPkg = pkg
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP && event.keyCode == KeyEvent.KEYCODE_BACK) {
            typingWithKeyboard = false   // Back closed the keyboard: not a submit
            recorder?.onKey(SystemKey.BACK)
        }
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
        Log.d(TAG, "click $pkg ${src.className} text=\"${src.text}\" id=${src.viewIdResourceName} recording=${recorder != null}")
        val rec = recorder ?: return
        if (pkg == launcherPkg) return
        val (root, node) = snapshotAround(src) ?: return
        // The user already tapped it (their choice), but a recipe must never contain it.
        SafetyGate.checkTap(node, root, pkg)?.let { return stopRecording(it) }
        inferMissedTap(rec, root, pkg)
        rec.onTap(Identity.target(node, root, rec.spoken), screenOf(root, pkg))
        updateOverlay()
        // Pages with autoplay video never "settle": look once anyway, so an options sheet that opened gets seen.
        main.postDelayed({ if (recorder === rec) captureFront()?.first?.let { if (Identity.isModal(it)) sheetChoices += Identity.selectedOptions(it) } }, 1_200)
    }

    /** [eventText] = the event's own copy of the new text; Zomato's field value stays the placeholder while typing. */
    private fun onTextChanged(pkg: String, src: AccessibilityNodeInfo, eventText: String) {
        Log.d(TAG, "text event=\"$eventText\" node=\"${src.text}\" hintShowing=${src.isShowingHintText}")
        val rec = recorder ?: return
        val (root, node) = snapshotAround(src) ?: return
        // Never record what goes into an OTP/password/card field.
        SafetyGate.checkTap(node, root, pkg)?.let { return stopRecording(it) }
        if (src.isPassword) return
        inferMissedTap(rec, root, pkg)
        typingWithKeyboard = true
        val text = listOf(eventText, src.text?.toString().orEmpty())
            .firstOrNull { it.isNotBlank() && !Identity.isPlaceholder(node, it) }
            ?.takeUnless { src.isShowingHintText && it == src.text?.toString() }
            .orEmpty()
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
        val (root, pkg) = captureFront() ?: return
        if (pkg == packageName || pkg == launcherPkg || pkg in IGNORED_PACKAGES) return
        detectSubmit(rec)
        if (Identity.isModal(root)) sheetChoices += Identity.selectedOptions(root) else sheetChoices.clear()
        val screen = inferMissedTap(rec, root, pkg)
        rec.onScreen(screen, Identity.compact(root))
        // Teaching ends by itself at the payment/credential screen, before the user can tap "Place Order".
        SafetyGate.check(root, pkg)?.let { stopRecording(it) }
    }

    /**
     * New activity but no step since the last settled screen → the app ate the click event (Zomato suggestions).
     * Runs on settle AND right before recording the next tap/typing: pages with autoplay video never settle.
     */
    /**
     * Keyboard gone after typing, and the user didn't press Back: they pressed Go/Search/Enter
     * (the key itself is never reported to accessibility services).
     */
    private fun detectSubmit(rec: Recorder) {
        if (!typingWithKeyboard) return
        val keyboardUp = windows.any { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        if (keyboardUp) return
        typingWithKeyboard = false
        if (rec.steps.lastOrNull()?.type == com.example.myna_mimicyourinteractionsautomate.recipe.StepType.TYPE) rec.markSubmit()
    }

    private fun inferMissedTap(rec: Recorder, root: UiNode, pkg: String): Screen {
        val screen = screenOf(root, pkg)
        val prev = prevSettled
        val lastWasBack = rec.steps.lastOrNull()?.key == SystemKey.BACK
        if (prev != null && rec.steps.size == stepsAtPrevSettle && !lastWasBack &&
            Identity.isModal(prev.first) && !Identity.isModal(root)) {
            rec.onSheetConfirmed(prev.second, sheetChoices)
            sheetChoices.clear()
        } else if (prev != null && rec.steps.size == stepsAtPrevSettle && screen.title != prev.second.title) {
            val query = rec.lastTyped
            if (rec.inSearch && query != null) {
                // Typed, then landed somewhere new without a visible tap: it's a search. Name where we landed.
                rec.onSearchLanded(Identity.pickedResult(root, query))
            } else {
                Identity.inferTap(prev.first, root)?.let { rec.onInferredTap(Identity.target(it, prev.first, rec.spoken), prev.second) }
            }
        } else if (prev != null && rec.steps.size == stepsAtPrevSettle && screen.pkg == prev.second.pkg &&
            rec.steps.lastOrNull()?.let { it.type == StepType.TYPE && !it.submit } != true) {   // not while typing
            // Same activity, new content, no event (taps inside web pages): infer from what's new on screen.
            if (rec.resultsPending) rec.resultsPending = false
            else Identity.inferByDiff(prev.first, root)?.let { rec.onInferredTap(Identity.target(it, prev.first, rec.spoken), prev.second) }
        }
        prevSettled = root to screen
        stepsAtPrevSettle = rec.steps.size
        return screen
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
        val root = captureFront()?.first ?: src.window?.root?.let(UiTree::capture) ?: return null
        val node = UiTree.find(root, src) ?: UiTree.capture(src)
        return root to node
    }

    /**
     * Every window of the front app as ONE tree. Bottom sheets, their sticky footers ("Add item ₹109")
     * and dialogs can be separate windows; the active window alone misses them.
     */
    private fun captureFront(): Pair<UiNode, String>? {
        val active = rootInActiveWindow ?: return null
        val pkg = active.packageName?.toString() ?: return null
        val roots = windows.mapNotNull { it.root }.filter { it.packageName?.toString() == pkg }
        if (roots.size <= 1) return UiTree.capture(active) to pkg
        val kids = roots.reversed().map(UiTree::capture)   // getWindows() is top-most first; keep screen order bottom → top
        return UiNode(cls = "Windows", r = kids.maxOf { it.r }, b = kids.maxOf { it.b }, children = kids) to pkg
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
        main.removeCallbacks(tick); main.postDelayed(tick, TICK_MS)
        Toast.makeText(this, "Your turn: do the task yourself. Tap ■ Done when finished.", Toast.LENGTH_LONG).show()
        return true
    }

    private fun stopRecording(block: SafetyGate.Block) {
        stopRecording("safety_gate", block.reason)
        handOff(block)
    }

    fun stopRecording(stoppedBy: String = "user", reason: String? = null) {
        val rec = recorder ?: return
        recorder = null
        hideOverlay()
        val recording = rec.finish(stoppedBy, reason)
        val dir = File(getExternalFilesDir(null), "recordings").apply { mkdirs() }
        val file = File(dir, "rec-${recording.startedAt}.json")
        file.writeText(RecipeJson.encodeToString(recording))
        Log.i(TAG, "saved ${recording.steps.size} steps → $file (stopped by $stoppedBy)")
        Toast.makeText(this, "Saved ${recording.steps.size} steps — learning…", Toast.LENGTH_SHORT).show()
        scope.launch { learn(recording) }
        // At a secure screen, leave the user there to finish it; otherwise show the step list.
        if (stoppedBy != "safety_gate") {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
    }

    /** Compile the demo into a recipe (one AI call), save it, and confirm out loud (T1). */
    private suspend fun learn(recording: com.example.myna_mimicyourinteractionsautomate.recipe.Recording) {
        // New tasks go straight to Home (voice only searches Home); re-teaching the same command replaces the old one there.
        val res = com.example.myna_mimicyourinteractionsautomate.compile.Compiler.compile(recording, golden = true)
        val same = { a: String -> Identity.loose(a) == Identity.loose(recording.utterance) }
        recipes.all().filter { it.golden && it.app == recording.app && same(it.utterance) }.forEach { recipes.save(it.copy(golden = false)) }
        recipes.save(res.recipe)
        lastCompile = res
        val spoken = com.example.myna_mimicyourinteractionsautomate.replay.Slots.fill(
            res.recipe.summary ?: res.recipe.utterance, res.recipe.slots.mapValues { it.value.value.orEmpty() })
        say("Learned: $spoken.")
        if (res.removed.isNotEmpty()) say("I left out ${res.removed.size} step${if (res.removed.size > 1) "s" else ""} that looked like mistakes.")
        res.questions.firstOrNull()?.let { say(it) }
        Log.i(TAG, "learned ${res.recipe.id}: ${res.recipe.summary} slots=${res.recipe.slots.keys} removed=${res.removed} q=${res.questions}")
        onChanged?.invoke()
    }

    // ---------------------------------------------------------------- safety hand-off + actions

    /** "Your turn": big banner + spoken message. MYNA makes no further taps. */
    fun handOff(block: SafetyGate.Block) {
        Log.w(TAG, "hand-off: ${block.kind} ${block.reason}")
        val what = when (block.kind) {
            SafetyGate.Kind.PAYMENT, SafetyGate.Kind.FINAL_ORDER -> "the payment step"
            SafetyGate.Kind.CREDENTIAL -> "a secure field"
            SafetyGate.Kind.LOGIN -> "a login screen"
        }
        val msg = "Your turn. This is $what, so I won't tap here."
        tts?.speak(msg, TextToSpeech.QUEUE_FLUSH, null, "handoff")
        main.post {
            handOffView?.let { getSystemService(WindowManager::class.java).removeView(it) }
            val v = TextView(this).apply {
                text = "🔒 $msg\n(${block.reason})\nTap to dismiss"
                textSize = 18f
                setTextColor(Color.WHITE)
                setBackgroundColor(0xE6202124.toInt())
                setPadding(48, 40, 48, 40)
                setOnClickListener { dismissHandOff() }
            }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.TOP; y = 120 }
            getSystemService(WindowManager::class.java).addView(v, lp)
            handOffView = v
            main.postDelayed(::dismissHandOff, 10_000)
        }
    }

    private fun dismissHandOff() {
        handOffView?.let { getSystemService(WindowManager::class.java).removeView(it) }
        handOffView = null
    }

    /** Only called by [actor], after the gate said yes. */
    private fun clickNode(n: UiNode): Boolean {
        val live = n.live as? AccessibilityNodeInfo
        if (live?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return true
        // Click refused (custom views): tap the centre of its bounds.
        val x = (n.l + n.r) / 2f; val y = (n.t + n.b) / 2f
        val path = Path().apply { moveTo(x, y) }
        return dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 80)).build(),
            object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription) { Log.d(TAG, "gesture tap ($x,$y) completed on $n") }
                override fun onCancelled(g: GestureDescription) { Log.w(TAG, "gesture tap ($x,$y) CANCELLED on $n") }
            }, null)
    }

    /** Only called by [actor], after the gate said yes. */
    private fun setNodeText(n: UiNode, text: String, submit: Boolean): Boolean {
        val live = n.live as? AccessibilityNodeInfo ?: return false
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        if (!live.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return false
        if (!submit) return true
        Thread.sleep(400)   // let the app's text watcher run before submitting
        return live.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
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

    // ---------------------------------------------------------------- replay (Device for the Executor)

    val recipes by lazy { Recipes(File(getExternalFilesDir(null), "recipes")) }

    /** Replay [list] one after another (the golden button passes several). Results land in files/runs/. */
    fun replay(list: List<Recipe>, slots: Map<String, String> = emptyMap(), onDone: (List<RunLog>) -> Unit = {}) {
        if (running || recorder != null || list.isEmpty()) return
        running = true
        stopRequested = false
        scope.launch {
            showRunOverlay()
            val logs = mutableListOf<RunLog>()
            for (r in list) {
                if (stopRequested) break
                val log = Executor(this@MynaService, onStep = { st, n -> runOverlay?.text = "▶ ${st.index}/$n ${st.what.take(28)}  ■ Stop" })
                    .run(r, slots)
                logs += log
                saveRun(log)
                when (log.outcome) {
                    Outcome.STUCK, Outcome.FAILED -> say("I'm stuck. ${log.reason}")
                    Outcome.DONE -> say("Done.")
                    else -> {}
                }
            }
            hideRunOverlay()
            running = false
            onDone(logs)
            // Stuck/handed off: leave the app on screen so the user sees where and can take over.
            if (logs.lastOrNull()?.outcome in setOf(Outcome.DONE, Outcome.STOPPED)) {
                startActivity(Intent(this@MynaService, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }
        }
    }

    private fun saveRun(log: RunLog) {
        val dir = File(getExternalFilesDir(null), "runs").apply { mkdirs() }
        File(dir, "run-${log.startedAt}.json").writeText(RecipeJson.encodeToString(log))
        Log.i(TAG, "run ${log.recipeId}: ${log.outcome} ${log.reason ?: ""}")
    }

    override suspend fun screen(): Pair<UiNode, String>? {
        delay(250)                 // let the last action's events start arriving
        awaitSettled()
        return captureFront()
    }

    /** Clean start (design §4.1): Home, then open the app from its launcher entry with a fresh task. */
    override suspend fun launchClean(pkg: String): Boolean {
        val launch = packageManager.getLaunchIntentForPackage(pkg) ?: return false
        performGlobalAction(GLOBAL_ACTION_HOME)
        delay(600)
        startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        val deadline = System.currentTimeMillis() + 10_000
        while (rootInActiveWindow?.packageName?.toString() != pkg && System.currentTimeMillis() < deadline) delay(200)
        delay(1_500)               // splash screens
        awaitSettled()
        return rootInActiveWindow?.packageName?.toString() == pkg
    }

    override fun key(key: SystemKey) {
        performGlobalAction(if (key == SystemKey.BACK) GLOBAL_ACTION_BACK else GLOBAL_ACTION_HOME)
    }

    override fun scroll(list: UiNode, forward: Boolean): Boolean {
        val live = list.live as? AccessibilityNodeInfo
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        if (live?.performAction(action) == true) return true
        // Compose lists often ignore the action: swipe inside the list instead (a swipe can't buy anything).
        val x = (list.l + list.r) / 2f
        val (from, to) = if (forward) list.t + (list.b - list.t) * 0.75f to list.t + (list.b - list.t) * 0.25f
                         else list.t + (list.b - list.t) * 0.25f to list.t + (list.b - list.t) * 0.75f
        val path = Path().apply { moveTo(x, from); lineTo(x, to) }
        return dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 350)).build(), null, null)
    }

    override suspend fun ocr(): List<OcrLine> {
        // Our own overlays ("► 3/3 TAP CART", banners) must not be read as the app's screen.
        val mine = listOfNotNull(runOverlay, promptView, handOffView, overlay)
        mine.forEach { it.visibility = android.view.View.INVISIBLE }
        delay(120)
        val bmp = try { screenshot() } finally { mine.forEach { it.visibility = android.view.View.VISIBLE } } ?: return emptyList()
        return suspendCancellableCoroutine { cont ->
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process(InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener { text ->
                    cont.resume(text.textBlocks.flatMap { it.lines }.mapNotNull { l ->
                        l.boundingBox?.let { b -> OcrLine(l.text, b.left, b.top, b.right, b.bottom) }
                    })
                }
                .addOnFailureListener { cont.resume(emptyList()) }
        }.also { Log.d(TAG, "ocr: ${it.joinToString(" | ") { l -> l.text }.take(300)}") }
    }

    private suspend fun screenshot(): Bitmap? = suspendCancellableCoroutine { cont ->
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                cont.resume(hw?.copy(Bitmap.Config.ARGB_8888, false))
                result.hardwareBuffer.close()
            }
            override fun onFailure(errorCode: Int) { Log.w(TAG, "screenshot failed $errorCode"); cont.resume(null) }
        })
    }

    /** Spoken question + buttons on an overlay. Null after 20 s without an answer. */
    override suspend fun ask(question: String, options: List<String>): String? {
        say(question)
        val answer = CompletableDeferred<String?>()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xF0202124.toInt())
            setPadding(48, 40, 48, 40)
            addView(TextView(context).apply { text = question; textSize = 18f; setTextColor(Color.WHITE) })
            options.forEach { o -> addView(Button(context).apply { text = o; setOnClickListener { answer.complete(o) } }) }
        }
        val wm = getSystemService(WindowManager::class.java)
        wm.addView(box, WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.CENTER })
        return try { withTimeoutOrNull(20_000) { answer.await() } } finally { wm.removeView(box) }
    }

    override fun say(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, text.hashCode().toString())
    }

    override fun now() = System.currentTimeMillis()

    override suspend fun pause(ms: Long) = delay(ms)

    private var promptView: TextView? = null

    override fun prompt(text: String?) {
        promptView?.let { getSystemService(WindowManager::class.java).removeView(it) }
        promptView = null
        if (text == null) return
        say(text)
        val v = TextView(this).apply {
            this.text = "👆 $text"; textSize = 18f; setTextColor(Color.WHITE)
            setBackgroundColor(0xE61A73E8.toInt()); setPadding(48, 36, 48, 36)
        }
        // Top of the screen and not touchable: it must never cover or intercept the button the user is asked to tap.
        getSystemService(WindowManager::class.java).addView(v, WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP; y = 100 })
        promptView = v
    }

    fun requestStop() { stopRequested = true }

    private fun showRunOverlay() {
        val btn = Button(this).apply { text = "▶ starting…  ■ Stop"; setOnClickListener { requestStop(); text = "stopping…" } }
        getSystemService(WindowManager::class.java).addView(btn, WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.END; y = 300 })
        runOverlay = btn
    }

    private fun hideRunOverlay() {
        runOverlay?.let { getSystemService(WindowManager::class.java).removeView(it) }
        runOverlay = null
    }

    // ---------------------------------------------------------------- debug hook (debug builds only)

    /**
     * From the Mac:  adb shell am broadcast -a myna.DEBUG --es cmd dump
     *                adb shell am broadcast -a myna.DEBUG --es cmd tap --ei x 703 --ei y 2098
     * Guarded by android.permission.DUMP, which only the shell (adb) holds — other apps can't drive MYNA through it.
     */
    private fun registerDebugHook() {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context, i: Intent) {
                when (i.getStringExtra("cmd")) {
                    "dump" -> captureFront()?.let { (root, pkg) ->
                        val f = File(getExternalFilesDir(null), "debug-tree.txt")
                        f.writeText("pkg=$pkg modal=${Identity.isModal(root)} selected=${Identity.selectedOptions(root)} " +
                            "sheetButton=${Identity.blankSheetButton(root)?.bounds} gate=${SafetyGate.check(root, pkg)}\n" + debugTree(root))
                        Log.i(TAG, "debug dump → $f")
                    }
                    "learn" -> {   // re-compile a saved recording into a Home recipe (recovers deleted recipes)
                        val f = File(File(getExternalFilesDir(null), "recordings"), i.getStringExtra("file") ?: return)
                        val rec = runCatching { RecipeJson.decodeFromString<com.example.myna_mimicyourinteractionsautomate.recipe.Recording>(f.readText()) }.getOrNull() ?: return
                        scope.launch { learn(rec); Log.i(TAG, "debug relearn ${f.name} done") }
                    }
                    "tap" -> {   // bypasses the gate on purpose: dev only, adb only
                        val x = i.getIntExtra("x", 0).toFloat(); val y = i.getIntExtra("y", 0).toFloat()
                        val dur = i.getIntExtra("dur", 80).toLong(); val move = i.getIntExtra("move", 0)
                        val path = Path().apply { moveTo(x, y); if (move > 0) lineTo(x + move, y) }
                        dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, dur)).build(),
                            object : GestureResultCallback() {
                                override fun onCompleted(g: GestureDescription) { Log.d(TAG, "debug tap ($x,$y) dur=$dur move=$move completed") }
                                override fun onCancelled(g: GestureDescription) { Log.w(TAG, "debug tap CANCELLED") }
                            }, null)
                    }
                    "click" -> {   // ACTION_CLICK on the deepest node at (x,y), then each ancestor until one accepts
                        val x = i.getIntExtra("x", 0); val y = i.getIntExtra("y", 0)
                        val root = captureFront()?.first ?: return
                        val hit = root.walk().filter { it.visible && x in it.l..it.r && y in it.t..it.b }.minByOrNull { (it.r - it.l) * (it.b - it.t) } ?: return
                        (hit.live as? AccessibilityNodeInfo)?.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
                        for (n in sequenceOf(hit) + hit.ancestors()) {
                            val ok = (n.live as? AccessibilityNodeInfo)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
                            Log.d(TAG, "debug click on $n ${n.bounds} → $ok")
                            if (ok) break
                        }
                    }
                }
            }
        }
        registerReceiver(receiver, android.content.IntentFilter("myna.DEBUG"), android.Manifest.permission.DUMP, null, RECEIVER_EXPORTED)
    }

    private fun debugTree(n: UiNode, depth: Int = 0): String = buildString {
        append("  ".repeat(depth)).append(n.cls).append(" \"").append(n.label ?: "").append("\" #").append(n.id ?: "")
            .append(" ").append(n.bounds).append(if (n.clickable) " c" else "").append(if (n.checkable) (if (n.checked) " [x]" else " [ ]") else "")
            .append(if (n.visible) "" else " invisible").append('\n')
        n.children.forEach { append(debugTree(it, depth + 1)) }
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
        // Every window, not just the active one: sheets/footers/dialogs can live in their own window.
        val all = org.json.JSONArray()
        windows.forEach { w -> w.root?.let { r -> all.put(JSONObject().put("title", w.title).put("type", w.type).put("layer", w.layer)
            .put("pkg", r.packageName).put("tree", TreeDump.dump(r))) } }
        val line = JSONObject().put("t", System.currentTimeMillis()).put("event", event)
            .put("pkg", root.packageName ?: lastPkg).put("stats", stats.toJson()).put("target", extra).put("tree", tree).put("windows", all)
        dumpFile?.appendText(line.toString() + "\n")
        Log.i(TAG, "$event ${root.packageName} ${stats.toJson()} windows=${all.length()}")
    }
}
