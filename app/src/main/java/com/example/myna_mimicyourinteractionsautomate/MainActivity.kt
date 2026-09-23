package com.example.myna_mimicyourinteractionsautomate

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.text.format.DateUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.IconButton
import androidx.compose.foundation.layout.FlowRow
import com.example.myna_mimicyourinteractionsautomate.ui.MynaIcons
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myna_mimicyourinteractionsautomate.a11y.MynaService
import com.example.myna_mimicyourinteractionsautomate.intent.IntentMatcher
import com.example.myna_mimicyourinteractionsautomate.llm.Llm
import com.example.myna_mimicyourinteractionsautomate.recipe.Recipe
import com.example.myna_mimicyourinteractionsautomate.recipe.RecipeJson
import com.example.myna_mimicyourinteractionsautomate.recipe.Recipes
import com.example.myna_mimicyourinteractionsautomate.recipe.Recording
import com.example.myna_mimicyourinteractionsautomate.record.describe
import com.example.myna_mimicyourinteractionsautomate.replay.Outcome
import com.example.myna_mimicyourinteractionsautomate.replay.RunLog
import com.example.myna_mimicyourinteractionsautomate.replay.Slots
import com.example.myna_mimicyourinteractionsautomate.ui.AppBadge
import com.example.myna_mimicyourinteractionsautomate.ui.AutomationCard
import com.example.myna_mimicyourinteractionsautomate.ui.Bubble
import com.example.myna_mimicyourinteractionsautomate.ui.MicHero
import com.example.myna_mimicyourinteractionsautomate.ui.SectionTitle
import com.example.myna_mimicyourinteractionsautomate.ui.StatusPill
import com.example.myna_mimicyourinteractionsautomate.ui.StepRow
import com.example.myna_mimicyourinteractionsautomate.ui.theme.Beak
import com.example.myna_mimicyourinteractionsautomate.ui.theme.BeakSoft
import com.example.myna_mimicyourinteractionsautomate.ui.theme.Card
import com.example.myna_mimicyourinteractionsautomate.ui.theme.Ink
import com.example.myna_mimicyourinteractionsautomate.ui.theme.InkSoft
import com.example.myna_mimicyourinteractionsautomate.ui.theme.Line
import com.example.myna_mimicyourinteractionsautomate.ui.theme.MYNAMimicYourINteractionsAutomateTheme
import com.example.myna_mimicyourinteractionsautomate.ui.theme.Warn
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

class MainActivity : ComponentActivity() {

    private enum class Tab(val label: String, val icon: Int) {
        HOME("Home", R.drawable.home), TEACH("Teach", R.drawable.teaching), HISTORY("History", R.drawable.history)
    }

    private var tab by mutableStateOf(Tab.HOME)
    private var serviceOn by mutableStateOf(false)
    private var lastRecording by mutableStateOf<Recording?>(null)
    private var recipes by mutableStateOf<List<Recipe>>(emptyList())
    private var runs by mutableStateOf<List<RunLog>>(emptyList())
    private var teachUtterance by mutableStateOf("Order a Margherita pizza from Domino's on Zomato")
    private var teachApp by mutableStateOf(APPS[0].second)

    // Sheets
    private var openRecipe by mutableStateOf<Recipe?>(null)
    private var openRun by mutableStateOf<RunLog?>(null)
    private var settingsOpen by mutableStateOf(false)
    private var customizing by mutableStateOf<Recipe?>(null)

    // Ask MYNA (Phase 5) conversation state
    private val chat = mutableStateListOf<String>()
    private var pending by mutableStateOf<IntentMatcher.Decision?>(null)
    private var thinking by mutableStateOf(false)
    private var listening by mutableStateOf(false)   // speech input open: the mic circle turns green

    private companion object {
        val PING_SCHEMA = JSONObject("""{"type":"object","required":["reply"],"properties":{"reply":{"type":"string"}}}""")
        val APPS = listOf("Zomato" to "com.application.zomato", "Amazon" to "in.amazon.mShop.android.shopping",
            "Myntra" to "com.myntra.android", "Swiggy" to "in.swiggy.android")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // White design on a dark-mode phone: force dark status/nav icons.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.WHITE, android.graphics.Color.WHITE),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.WHITE, android.graphics.Color.WHITE),
        )
        setContent { MYNAMimicYourINteractionsAutomateTheme { App() } }
    }

    // ================================================================= shell

    @Composable
    private fun App() {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(selected = tab == t, onClick = { tab = t },
                            icon = { Icon(painterResource(t.icon), t.label, Modifier.size(24.dp)) }, label = { Text(t.label) },
                            colors = NavigationBarItemDefaults.colors(indicatorColor = Card, selectedIconColor = Ink, selectedTextColor = Ink,
                                unselectedIconColor = InkSoft, unselectedTextColor = InkSoft))
                    }
                }
            },
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                when (tab) {
                    Tab.HOME -> HomeScreen()
                    Tab.TEACH -> TeachScreen()
                    Tab.HISTORY -> HistoryScreen()
                }
            }
        }
        openRecipe?.let { RecipeSheet(it) }
        openRun?.let { RunSheet(it) }
        if (settingsOpen) SettingsSheet()
        customizing?.let { CustomizeSheet(it) }
    }

    @Composable
    private fun Page(content: @Composable () -> Unit) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) { content() }
    }

    /** Shown on every screen until the accessibility service is on: without it nothing works. */
    @Composable
    private fun ServiceBanner() {
        if (serviceOn) return
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Warn.copy(alpha = 0.10f)).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("MYNA needs Accessibility access to see and tap for you.", fontWeight = FontWeight.SemiBold)
            Button({ startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }, shape = RoundedCornerShape(12.dp)) { Text("Turn it on") }
        }
    }

    // ================================================================= Home

    @Composable
    private fun HomeScreen() = Page {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("MYNA", Modifier.weight(1f), fontWeight = FontWeight.Black, fontSize = 26.sp, letterSpacing = 2.sp)
            Box(Modifier.size(40.dp).clip(CircleShape).background(Card).border(1.dp, Line, CircleShape).clickable { settingsOpen = true },
                contentAlignment = Alignment.Center) { Icon(MynaIcons.Person, "Settings", Modifier.size(22.dp), tint = Ink) }
        }
        ServiceBanner()
        AskMyna()
        val pinned = recipes.filter { it.golden }
        SectionTitle("Your automations") {
            if (pinned.size > 1) TextButton({ replay(pinned) }, enabled = serviceOn) { Text("Run all") }
        }
        if (pinned.isEmpty()) {
            Text("Nothing here yet. Teach MYNA a task, then tap “Add to Home”.", color = InkSoft)
            OutlinedButton({ tab = Tab.TEACH }, shape = RoundedCornerShape(12.dp)) { Text("Teach a task") }
        }
        pinned.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { r ->
                    AutomationCard(title(r), r.app, serviceOn, onRun = { replay(listOf(r)) }, onSteps = { openRecipe = r },
                        onChange = { customizing = r }.takeIf { r.slots.isNotEmpty() }, modifier = Modifier.weight(1f))
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }

    /** The recipe's summary with last time's values: "order Margherita from Domino's on Zomato". */
    private fun title(r: Recipe) = Slots.fill(r.summary ?: r.utterance, r.slots.mapValues { it.value.value ?: it.value.default.orEmpty() })!!
        .replaceFirstChar { it.uppercase() }

    /** Ask MYNA: tap the mic (or type); MYNA picks the recipe, fills blanks, asks when unsure (T3, T12, T13). */
    @Composable
    private fun AskMyna() {
        val scope = rememberCoroutineScope()
        var input by remember { mutableStateOf("") }
        val listen = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            listening = false
            res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { heard -> input = ""; scope.launch { onUserSaid(heard) } }
        }
        val listenNow = {
            listening = true
            runCatching { listen.launch(speechIntent(if (pending != null) "Your answer" else "What should I do?")) }.onFailure { listening = false }
        }
        // When MYNA asks something, listen for the reply once it has finished speaking.
        val asking = pending
        LaunchedEffect(asking) { if (asking != null) { kotlinx.coroutines.delay(2_500); if (pending === asking) listenNow() } }

        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            MicHero(busy = thinking || pending != null, listening = listening,
                caption = when { listening -> "Listening…"; thinking -> "Thinking…"; pending != null -> "Tap to answer"; else -> "What do you want to do with MYNA today?" },
                onTap = { listenNow() })
        }
        chat.takeLast(4).forEach { Bubble(it.substringAfter(": "), mine = it.startsWith("You")) }
        // Quick answers for MYNA's questions (they wrap instead of squeezing).
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            (pending as? IntentMatcher.Decision.AskSlot)?.let { q ->
                if (q.skippable) Chip("None") { scope.launch { onUserSaid("none") } }
                q.recipe.slots[q.slot]?.value?.let { last -> Chip("Same ($last)") { scope.launch { onUserSaid("same") } } }
            }
            if (pending is IntentMatcher.Decision.DidYouMean || pending is IntentMatcher.Decision.Unknown ||
                (pending as? IntentMatcher.Decision.Run)?.confirm != null) {
                Chip("Yes") { scope.launch { onUserSaid("yes") } }
                Chip("No") { scope.launch { onUserSaid("no") } }
            }
        }
        OutlinedTextField(input, { input = it }, Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(16.dp),
            placeholder = { Text(if (pending != null) "Or type your answer" else "Or type a command") },
            trailingIcon = {
                TextButton({ val t = input; input = ""; scope.launch { onUserSaid(t) } }, enabled = input.isNotBlank() && !thinking) { Text("Send") }
            })
    }

    @Composable
    private fun Chip(label: String, onClick: () -> Unit) {
        Text(label, Modifier.clip(RoundedCornerShape(20.dp)).background(BeakSoft).border(1.dp, Beak, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 9.dp), fontWeight = FontWeight.SemiBold)
    }

    // ================================================================= Teach

    @Composable
    private fun TeachScreen() = Page {
        Text("Teach MYNA", fontWeight = FontWeight.Black, fontSize = 26.sp)
        ServiceBanner()
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Card).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Say what you want, then show MYNA once.", color = InkSoft)
            var heard by remember { mutableStateOf<String?>(null) }
            val listen = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
                res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { teachUtterance = it; heard = it }
            }
            OutlinedTextField(teachUtterance, { teachUtterance = it }, Modifier.fillMaxWidth(), label = { Text("Command") }, shape = RoundedCornerShape(16.dp),
                trailingIcon = { IconButton({ runCatching { listen.launch(speechIntent("Say the command")) } }) { Icon(painterResource(R.drawable.mic), "Speak", Modifier.size(22.dp), tint = Ink) } })
            // What speech-to-text produced, so a mis-hearing is caught before teaching.
            heard?.let { Text("Heard: “$it”", style = MaterialTheme.typography.bodySmall, color = InkSoft) }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                APPS.forEach { (name, pkg) -> FilterChip(teachApp == pkg, { teachApp = pkg }, label = { Text(name, maxLines = 1) }) }
            }
            var error by remember { mutableStateOf<String?>(null) }
            Button({
                val ok = MynaService.instance?.startRecording(teachUtterance, teachApp) == true
                error = if (ok) null else "${APPS.firstOrNull { it.second == teachApp }?.first ?: teachApp} isn't installed"
            }, Modifier.fillMaxWidth().height(52.dp), enabled = serviceOn, shape = RoundedCornerShape(16.dp)) { Text("Show me once", fontSize = 16.sp) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        MynaService.lastCompile?.let { c ->
            if (c.removed.isNotEmpty()) Text("Left out as mistakes: " + c.removed.joinToString("; "), style = MaterialTheme.typography.bodySmall, color = InkSoft)
            c.questions.forEach { Text("❓ $it", color = Warn) }
        }
        SectionTitle("All automations (${recipes.size})")
        recipes.reversed().forEach { RecipeRow(it) }
        lastRecording?.let { r ->
            var open by remember { mutableStateOf(false) }
            TextButton({ open = !open }) { Text(if (open) "Hide last raw recording" else "Show last raw recording (${r.steps.size} steps)") }
            if (open) {
                r.steps.forEachIndexed { i, s -> StepRow(i + 1, s.describe()) }
                OutlinedButton({ replay(listOf(Recipes.fromRecording(r))) }, enabled = serviceOn) { Text("Replay raw") }
            }
        }
    }

    /** A recipe in the Teach list: blanks as fields (voice fills them on Home), run, add to Home, steps, delete. */
    @Composable
    private fun RecipeRow(r: Recipe) {
        val values = remember(r.id) { r.slots.mapValues { mutableStateOf(it.value.value.orEmpty()) } }
        var confirmDelete by remember(r.id) { mutableStateOf(false) }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).border(1.dp, Line, RoundedCornerShape(20.dp)).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppBadge(r.app)
                Spacer(Modifier.weight(1f))
                Text("${r.subtasks.sumOf { s -> s.steps.count { !it.noise } }} steps", style = MaterialTheme.typography.bodySmall, color = InkSoft)
                // Two taps to delete: a recipe can't be recovered.
                TextButton({ if (confirmDelete) { Recipes(File(getExternalFilesDir(null), "recipes")).delete(r.id); refresh() } else confirmDelete = true }) {
                    if (confirmDelete) Text("Sure?") else Icon(MynaIcons.Delete, "Delete", Modifier.size(20.dp), tint = InkSoft)
                }
            }
            Text(r.summary ?: r.utterance, fontWeight = FontWeight.SemiBold)
            values.forEach { (name, v) ->
                OutlinedTextField(v.value, { v.value = it }, Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(14.dp),
                    label = { Text(name.replace('_', ' ')) },
                    supportingText = r.slots[name]?.neighbours?.takeIf { it.isNotEmpty() }?.let { n -> { Text("e.g. " + n.take(3).joinToString()) } })
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button({ replay(listOf(r), values.mapValues { it.value.value }) }, enabled = serviceOn, shape = RoundedCornerShape(12.dp)) { Text("Run", maxLines = 1) }
                OutlinedButton({ save(r.copy(golden = !r.golden)) }, shape = RoundedCornerShape(12.dp)) { Text(if (r.golden) "✓ On Home" else "Add to Home", maxLines = 1) }
                TextButton({ openRecipe = r }) { Text("Steps", maxLines = 1) }
            }
        }
    }

    // ================================================================= History

    @Composable
    private fun HistoryScreen() = Page {
        Text("History", fontWeight = FontWeight.Black, fontSize = 26.sp)
        if (runs.isEmpty()) Text("No runs yet.", color = InkSoft)
        runs.forEach { log ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Card).clickable { openRun = log }.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(log.utterance.replaceFirstChar { it.uppercase() }, fontWeight = FontWeight.SemiBold, maxLines = 2)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        StatusPill(log.outcome)
                        Text("${DateUtils.getRelativeTimeSpanString(log.startedAt)} · ${(log.endedAt - log.startedAt) / 1000}s · ${log.steps.size} steps",
                            style = MaterialTheme.typography.bodySmall, color = InkSoft)
                    }
                }
                Icon(MynaIcons.Chevron, null, tint = InkSoft)
            }
        }
    }

    // ================================================================= sheets

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun RunSheet(log: RunLog) {
        ModalBottomSheet({ openRun = null }, containerColor = MaterialTheme.colorScheme.background) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(log.utterance.replaceFirstChar { it.uppercase() }, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusPill(log.outcome)
                    Text("${DateUtils.getRelativeTimeSpanString(log.startedAt)} · ${(log.endedAt - log.startedAt) / 1000}s", color = InkSoft)
                }
                log.reason?.let { Text(it, Modifier.clip(RoundedCornerShape(14.dp)).background(Card).padding(12.dp)) }
                if (log.slots.isNotEmpty()) Text(log.slots.entries.joinToString("   ") { "${it.key}: ${it.value.ifBlank { "—" }}" }, color = InkSoft,
                    style = MaterialTheme.typography.bodySmall)
                log.steps.forEach { s ->
                    StepRow(s.index, s.what, s.status, listOfNotNull(s.note, s.level?.let { "found at level $it" }, "${s.ms} ms".takeIf { s.ms > 0 }).joinToString(" · "))
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun RecipeSheet(r0: Recipe) {
        // Re-read so deleting a step updates the sheet.
        val r = recipes.firstOrNull { it.id == r0.id } ?: r0
        ModalBottomSheet({ openRecipe = null }, containerColor = MaterialTheme.colorScheme.background) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AppBadge(r.app)
                Text(title(r), fontWeight = FontWeight.Bold, fontSize = 20.sp)
                if (r.slots.isNotEmpty()) Text("Blanks: " + r.slots.entries.joinToString { "${it.key} (last: ${it.value.value ?: it.value.default})" }, color = InkSoft)
                if (r.defaults.isNotEmpty()) Text("Habit defaults: " + r.defaults.values.joinToString(), color = InkSoft)
                if (r.paraphrases.isNotEmpty()) Text("Also understands: " + r.paraphrases.take(4).joinToString(" · "), color = InkSoft,
                    style = MaterialTheme.typography.bodySmall)
                var n = 0
                r.subtasks.forEachIndexed { si, sub ->
                    Text(sub.name.replace('_', ' ').replaceFirstChar { it.uppercase() } + (sub.why.takeIf { it.isNotBlank() }?.let { " — $it" } ?: ""),
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
                    sub.steps.forEachIndexed { i, st ->
                        n++
                        StepRow(n, (if (st.noise) "✗ " else "") + st.describe(), null,
                            listOfNotNull(st.why.takeIf { it.isNotBlank() }, st.screen?.note).joinToString(" · ")) {
                            TextButton({
                                val subs = r.subtasks.toMutableList()
                                subs[si] = sub.copy(steps = sub.steps.filterIndexed { k, _ -> k != i })
                                save(r.copy(subtasks = subs.filter { it.steps.isNotEmpty() }))
                            }) { Text("✕", color = InkSoft) }
                        }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button({ openRecipe = null; replay(listOf(r)) }, enabled = serviceOn, shape = RoundedCornerShape(12.dp)) { Text("Run", maxLines = 1) }
                    OutlinedButton({ save(r.copy(golden = !r.golden)) }, shape = RoundedCornerShape(12.dp)) { Text(if (r.golden) "Remove from Home" else "Add to Home", maxLines = 1) }
                }
            }
        }
    }

    /** "Make slight changes": the recipe's blanks as friendly fields, run once or save as the new usual. */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun CustomizeSheet(r: Recipe) {
        val values = remember(r.id) { r.slots.mapValues { mutableStateOf(it.value.value ?: it.value.default.orEmpty()) } }
        ModalBottomSheet({ customizing = null }, containerColor = MaterialTheme.colorScheme.background) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AppBadge(r.app)
                Text("Make slight changes", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                // Live preview of what will run.
                Text(Slots.fill(r.summary ?: r.utterance, values.mapValues { it.value.value })!!.replaceFirstChar { it.uppercase() }, color = InkSoft)
                values.forEach { (name, v) ->
                    OutlinedTextField(v.value, { v.value = it }, Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(14.dp),
                        label = { Text(name.replace('_', ' ').replaceFirstChar { it.uppercase() }) },
                        placeholder = { Text("Leave empty to skip it") })
                    // Other options MYNA saw on screen while learning (e.g. other pizzas on the menu).
                    r.slots[name]?.neighbours?.filter { it != v.value }?.take(6)?.takeIf { it.isNotEmpty() }?.let { opts ->
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            opts.forEach { o ->
                                Text(o, Modifier.clip(RoundedCornerShape(14.dp)).border(1.dp, Line, RoundedCornerShape(14.dp))
                                    .clickable { v.value = o }.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ customizing = null; replay(listOf(r), values.mapValues { it.value.value }) }, Modifier.weight(1f),
                        enabled = serviceOn, shape = RoundedCornerShape(12.dp)) { Text("Run with these", maxLines = 1) }
                    OutlinedButton({
                        save(r.copy(slots = r.slots.mapValues { (k, s) -> s.copy(value = values[k]?.value?.takeIf { it.isNotBlank() } ?: s.value) }))
                        customizing = null
                    }, Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) { Text("Save as usual", maxLines = 1) }
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun SettingsSheet() {
        ModalBottomSheet({ settingsOpen = false }, containerColor = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Settings", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text("Accessibility service: " + if (serviceOn) "on" else "off", color = InkSoft)
                OutlinedButton({ startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("Accessibility settings") }
                Text("Developer", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                var dumping by remember { mutableStateOf(MynaService.dumping) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(dumping, { MynaService.dumping = it; dumping = it }, enabled = serviceOn)
                    Text("Dump screen trees", Modifier.padding(start = 10.dp))
                }
                PingButton()
                Text("Files: /sdcard/Android/data/$packageName/files", style = MaterialTheme.typography.bodySmall, color = InkSoft)
            }
        }
    }

    @Composable
    private fun PingButton() {
        val scope = rememberCoroutineScope()
        var ping by remember { mutableStateOf(if (Llm.mock) "AI: offline mode (no key)" else "AI: not tested") }
        OutlinedButton({
            ping = "AI: calling…"
            scope.launch {
                ping = runCatching {
                    val t0 = System.currentTimeMillis()
                    val r = Llm.llm("Say hi to the hackathon judges in 5 words.", PING_SCHEMA)
                    "AI ok in ${System.currentTimeMillis() - t0} ms: ${r.getString("reply")}"
                }.getOrElse { "AI failed: ${it.message}" }
            }
        }) { Text("Test AI connection") }
        Text(ping, style = MaterialTheme.typography.bodySmall, color = InkSoft)
    }

    // ================================================================= assistant logic (unchanged behaviour)

    private fun speechIntent(prompt: String) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
        .putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)

    private fun myna(text: String) {
        chat += "MYNA: $text"
        MynaService.instance?.say(text)
    }

    private val YES = Regex("^(yes|yeah|yep|haan|ha|sure|ok|okay|go ahead|do it|correct|right|same|same as last time|like last time)\\b", RegexOption.IGNORE_CASE)
    private val NO = Regex("^(no|nope|nah|nahi|cancel|stop|wrong)\\b", RegexOption.IGNORE_CASE)

    /** One turn of the conversation: a new command, or an answer to MYNA's question. */
    private suspend fun onUserSaid(text: String) {
        chat += "You: $text"
        val p = pending
        pending = null
        when {
            p is IntentMatcher.Decision.AskSlot -> {
                // "same" / "yes" → keep last time's value; "none" / "skip" → leave it out.
                val v = when {
                    YES.containsMatchIn(text) -> p.values[p.slot].orEmpty()
                    Regex("^(none|nothing|skip|no|leave it)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text) -> ""
                    else -> text.trim()
                }
                if (v.isBlank() && !p.skippable) {   // it's the search itself: can't be empty
                    pending = p; myna("I need a ${p.slot.replace('_', ' ')} to search for. Which one?"); return
                }
                val values = p.values + (p.slot to v)
                if (p.rest.isNotEmpty()) handle(IntentMatcher.ask(p.recipe, values, p.rest)) else run(p.recipe, values)
            }
            p is IntentMatcher.Decision.DidYouMean -> if (YES.containsMatchIn(text)) run(p.recipe, p.values)
                else myna("Okay. Tell me again, or teach me how.")
            p is IntentMatcher.Decision.Run && p.confirm != null -> if (YES.containsMatchIn(text)) run(p.recipe, p.values)
                else myna("Okay, I won't. What should I change?")
            p is IntentMatcher.Decision.Unknown -> if (YES.containsMatchIn(text)) {
                teachUtterance = p.utterance
                APPS.firstOrNull { (name, _) -> p.utterance.contains(name, ignoreCase = true) }?.let { teachApp = it.second }
                tab = Tab.TEACH
                myna("Great. Pick the app, tap Show me once, and do it yourself.")
            } else myna("No problem.")
            NO.matches(text.trim()) -> myna("Okay.")
            YES.matches(text.trim().trimEnd('.', '!')) -> myna("There's nothing waiting for a yes. Tell me what to do.")
            else -> command(text)
        }
    }

    private suspend fun command(text: String) {
        val list = Recipes(File(getExternalFilesDir(null), "recipes")).all()
        thinking = true
        val d = runCatching { IntentMatcher.decide(text, list) }.getOrElse { myna("Sorry, I couldn't understand that (${it.message})."); thinking = false; return }
        thinking = false
        handle(d)
    }

    private fun handle(d: IntentMatcher.Decision) {
        when (d) {
            IntentMatcher.Decision.Report -> myna(lastRunReport())
            is IntentMatcher.Decision.Unknown -> { pending = d; myna("I haven't learned that yet. Want to teach me?") }
            is IntentMatcher.Decision.DidYouMean -> { pending = d; myna(d.question) }
            is IntentMatcher.Decision.AskSlot -> { pending = d; myna(d.question) }
            is IntentMatcher.Decision.Run -> if (d.confirm != null) { pending = d; myna(d.confirm) } else run(d.recipe, d.values)
        }
    }

    private fun run(r: Recipe, values: Map<String, String>) {
        myna("On it: " + Slots.fill(r.summary ?: r.utterance, values))
        replay(listOf(r), values)
    }

    /** T14: "Did it work?" answered from the last run log. */
    private fun lastRunReport(): String {
        val log = runs.firstOrNull() ?: return "I haven't run anything yet."
        val what = log.utterance
        return when (log.outcome) {
            Outcome.HANDED_OFF -> "Yes. For \"$what\" I reached the payment step and handed it to you."
            Outcome.DONE -> "Yes, \"$what\" finished."
            Outcome.STOPPED -> "You stopped it at step ${log.steps.size}."
            else -> {
                val s = log.steps.lastOrNull { it.status != "ok" } ?: log.steps.lastOrNull()
                "No. It stopped at step ${s?.index}, ${s?.what}, because ${log.reason}."
            }
        }
    }

    private fun replay(list: List<Recipe>, slots: Map<String, String> = emptyMap()) {
        MynaService.instance?.replay(list, slots) { runOnUiThread { refresh() } }
    }

    private fun save(r: Recipe) {
        Recipes(File(getExternalFilesDir(null), "recipes")).save(r); refresh()
    }

    override fun onResume() {
        super.onResume()
        MynaService.onChanged = { runOnUiThread { refresh() } }
        refresh()
    }

    private fun refresh() {
        serviceOn = MynaService.instance != null
        val files = getExternalFilesDir(null)
        lastRecording = File(files, "recordings").listFiles()?.maxByOrNull { it.lastModified() }
            ?.let { runCatching { RecipeJson.decodeFromString<Recording>(it.readText()) }.getOrNull() }
        runs = File(files, "runs").listFiles().orEmpty().sortedByDescending { it.lastModified() }.take(50)
            .mapNotNull { runCatching { RecipeJson.decodeFromString<RunLog>(it.readText()) }.getOrNull() }
        recipes = Recipes(File(files, "recipes")).all()
        openRecipe = openRecipe?.let { o -> recipes.firstOrNull { it.id == o.id } }
    }
}
