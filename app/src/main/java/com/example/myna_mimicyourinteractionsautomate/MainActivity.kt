package com.example.myna_mimicyourinteractionsautomate

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.speech.RecognizerIntent
import androidx.compose.material3.IconButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.myna_mimicyourinteractionsautomate.a11y.MynaService
import com.example.myna_mimicyourinteractionsautomate.llm.Llm
import com.example.myna_mimicyourinteractionsautomate.recipe.RecipeJson
import com.example.myna_mimicyourinteractionsautomate.recipe.Recipe
import com.example.myna_mimicyourinteractionsautomate.recipe.Recipes
import com.example.myna_mimicyourinteractionsautomate.recipe.Recording
import com.example.myna_mimicyourinteractionsautomate.replay.RunLog
import com.example.myna_mimicyourinteractionsautomate.record.describe
import com.example.myna_mimicyourinteractionsautomate.ui.theme.MYNAMimicYourINteractionsAutomateTheme
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

class MainActivity : ComponentActivity() {

    private var serviceOn by mutableStateOf(false)
    private var lastRecording by mutableStateOf<Recording?>(null)
    private var recipes by mutableStateOf<List<Recipe>>(emptyList())
    private var lastRun by mutableStateOf<RunLog?>(null)

    private companion object {
        val PING_SCHEMA = JSONObject("""{"type":"object","required":["reply"],"properties":{"reply":{"type":"string"}}}""")
        val APPS = listOf("Zomato" to "com.application.zomato", "Amazon" to "in.amazon.mShop.android.shopping")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MYNAMimicYourINteractionsAutomateTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { pad ->
                    Column(
                        Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("Accessibility service: " + if (serviceOn) "ON" else "OFF")
                        if (!serviceOn) Button(onClick = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) {
                            Text("Open accessibility settings")
                        }

                        TeachCard()
                        lastRecording?.let { RecordingCard(it) }
                        HorizontalDivider()
                        RecipesCard()
                        lastRun?.let { RunCard(it) }

                        HorizontalDivider()
                        Text("Dev tools", style = MaterialTheme.typography.titleMedium)
                        var dumping by remember { mutableStateOf(MynaService.dumping) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(checked = dumping, enabled = serviceOn, onCheckedChange = {
                                MynaService.dumping = it; dumping = it
                            })
                            Text("Dump screen trees", Modifier.padding(start = 8.dp))
                        }
                        PingButton()
                        Text("Files: adb pull /sdcard/Android/data/$packageName/files", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }

    @Composable
    private fun TeachCard() {
        Text("Teach", style = MaterialTheme.typography.titleMedium)
        var utterance by remember { mutableStateOf("Order a Margherita pizza from Domino's on Zomato") }
        var heard by remember { mutableStateOf<String?>(null) }
        val listen = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { utterance = it; heard = it }
        }
        var app by remember { mutableStateOf(APPS[0].second) }
        var error by remember { mutableStateOf<String?>(null) }
        OutlinedTextField(utterance, { utterance = it }, Modifier.fillMaxWidth(), label = { Text("Command") },
            trailingIcon = {
                IconButton(onClick = {
                    val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
                        .putExtra(RecognizerIntent.EXTRA_PROMPT, "Say the command")
                    runCatching { listen.launch(i) }.onFailure { heard = "Speech input isn't available on this phone" }
                }) { Text("🎤", style = MaterialTheme.typography.titleLarge) }
            })
        // What speech-to-text produced, so a mis-hearing is caught before teaching.
        heard?.let { Text("Heard: \"$it\"", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            APPS.forEach { (name, pkg) -> FilterChip(app == pkg, { app = pkg }, label = { Text(name) }) }
        }
        OutlinedTextField(app, { app = it.trim() }, Modifier.fillMaxWidth(), label = { Text("App package") })
        Button(enabled = serviceOn, onClick = {
            val ok = MynaService.instance?.startRecording(utterance, app) == true
            error = if (ok) null else "$app is not installed"
        }) { Text("Teach: show me once") }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }

    @Composable
    private fun RecordingCard(r: Recording) {
        Text("Last recording: \"${r.utterance}\" (${r.steps.size} steps, stopped by ${r.stoppedBy}${r.stopReason?.let { ": $it" } ?: ""})",
            style = MaterialTheme.typography.titleSmall)
        r.steps.forEachIndexed { i, s ->
            Text("${i + 1}. ${s.describe()}", style = MaterialTheme.typography.bodySmall)
        }
        Button(enabled = serviceOn, onClick = { replay(listOf(Recipes.fromRecording(r))) }) { Text("Replay raw recording") }
    }

    @Composable
    private fun RecipesCard() {
        val golden = recipes.filter { it.golden }
        Text("Recipes (${recipes.size})", style = MaterialTheme.typography.titleMedium)
        MynaService.lastCompile?.let { c ->
            if (c.removed.isNotEmpty()) Text("Left out as mistakes: " + c.removed.joinToString("; "), style = MaterialTheme.typography.bodySmall)
            c.questions.forEach { Text("❓ $it", color = MaterialTheme.colorScheme.error) }
        }
        Button(enabled = serviceOn && golden.isNotEmpty(), onClick = { replay(golden) }) { Text("★ Golden run (${golden.size})") }
        recipes.reversed().forEach { RecipeCard(it) }
    }

    /** One recipe: blanks as fields (voice replaces this in Phase 5), run, golden star, inspectable/editable steps (T1). */
    @Composable
    private fun RecipeCard(r: Recipe) {
        var open by remember(r.id) { mutableStateOf(false) }
        val values = remember(r.id) { r.slots.mapValues { mutableStateOf(it.value.value.orEmpty()) } }
        HorizontalDivider()
        val steps = r.subtasks.sumOf { s -> s.steps.count { !it.noise } }
        var confirmDelete by remember(r.id) { mutableStateOf(false) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text((if (r.golden) "★ " else "") + (r.summary ?: r.utterance) + "  · $steps steps · ${r.app.substringAfterLast('.')}",
                Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Button(onClick = { save(r.copy(golden = !r.golden)) }) { Text(if (r.golden) "Unstar" else "★") }
            // Two taps to delete: a recipe can't be recovered.
            Button(onClick = {
                if (confirmDelete) { Recipes(File(getExternalFilesDir(null), "recipes")).delete(r.id); refresh() } else confirmDelete = true
            }) { Text(if (confirmDelete) "Sure?" else "🗑") }
        }
        values.forEach { (name, v) ->
            OutlinedTextField(v.value, { v.value = it }, Modifier.fillMaxWidth(), label = { Text("{$name}") },
                supportingText = r.slots[name]?.neighbours?.takeIf { it.isNotEmpty() }?.let { n -> { Text("e.g. " + n.take(3).joinToString()) } })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = serviceOn, onClick = { replay(listOf(r), values.mapValues { it.value.value }) }) { Text("Run") }
            Button(onClick = { open = !open }) { Text(if (open) "Hide steps" else "Steps") }
        }
        if (open) {
            if (r.paraphrases.isNotEmpty()) Text("Also understands: " + r.paraphrases.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            if (r.defaults.isNotEmpty()) Text("Habit defaults: " + r.defaults.values.joinToString(), style = MaterialTheme.typography.bodySmall)
            r.subtasks.forEachIndexed { si, sub ->
                Text("▸ ${sub.name}${sub.why.takeIf { it.isNotBlank() }?.let { " — $it" } ?: ""}", style = MaterialTheme.typography.labelLarge)
                sub.steps.forEachIndexed { i, st ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text((if (st.noise) "✗ " else "") + st.describe() + (st.why.takeIf { it.isNotBlank() }?.let { "\n   $it" } ?: "") +
                            (st.screen?.note?.let { "  [$it]" } ?: ""), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        Button(onClick = {
                            val subs = r.subtasks.toMutableList()
                            subs[si] = sub.copy(steps = sub.steps.filterIndexed { k, _ -> k != i })
                            save(r.copy(subtasks = subs.filter { it.steps.isNotEmpty() }))
                        }) { Text("✕") }
                    }
                }
            }
        }
    }

    private fun save(r: Recipe) {
        Recipes(File(getExternalFilesDir(null), "recipes")).save(r); refresh()
    }

    @Composable
    private fun RunCard(log: RunLog) {
        Text("Last run: ${log.outcome}${log.reason?.let { " — $it" } ?: ""} (${(log.endedAt - log.startedAt) / 1000}s)",
            style = MaterialTheme.typography.titleSmall)
        log.steps.forEach { s ->
            Text("${s.index}. [${s.status}${s.level?.let { " L$it" } ?: ""}] ${s.what}${s.note?.let { " — $it" } ?: ""}",
                style = MaterialTheme.typography.bodySmall)
        }
    }

    private fun replay(list: List<Recipe>, slots: Map<String, String> = emptyMap()) {
        MynaService.instance?.replay(list, slots) { runOnUiThread { refresh() } }
    }

    @Composable
    private fun PingButton() {
        val scope = rememberCoroutineScope()
        var ping by remember { mutableStateOf(if (Llm.mock) "LLM: mock mode (no key)" else "LLM: not tested") }
        Button(onClick = {
            ping = "LLM: calling…"
            scope.launch {
                ping = runCatching {
                    val t0 = System.currentTimeMillis()
                    val r = Llm.llm("Say hi to the hackathon judges in 5 words.", PING_SCHEMA)
                    "LLM ok in ${System.currentTimeMillis() - t0} ms: ${r.getString("reply")}"
                }.getOrElse { "LLM failed: ${it.message}" }
            }
        }) { Text("Ping LLM") }
        Text(ping)
    }

    override fun onResume() {
        super.onResume()
        MynaService.onChanged = { runOnUiThread { refresh() } }
        refresh()
    }

    private fun refresh() {
        serviceOn = MynaService.instance != null
        lastRecording = latest("recordings")?.let { runCatching { RecipeJson.decodeFromString<Recording>(it.readText()) }.getOrNull() }
        lastRun = latest("runs")?.let { runCatching { RecipeJson.decodeFromString<RunLog>(it.readText()) }.getOrNull() }
        recipes = Recipes(File(getExternalFilesDir(null), "recipes")).all()
    }

    private fun latest(dir: String) = File(getExternalFilesDir(null), dir).listFiles()?.maxByOrNull { it.lastModified() }
}
