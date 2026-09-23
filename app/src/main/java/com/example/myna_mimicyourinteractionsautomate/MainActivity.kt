package com.example.myna_mimicyourinteractionsautomate

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
        // ponytail: typed command until Phase 5 brings voice
        var utterance by remember { mutableStateOf("Order a Margherita pizza from Domino's on Zomato") }
        var app by remember { mutableStateOf(APPS[0].second) }
        var error by remember { mutableStateOf<String?>(null) }
        OutlinedTextField(utterance, { utterance = it }, Modifier.fillMaxWidth(), label = { Text("Command") })
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = serviceOn, onClick = { replay(listOf(Recipes.fromRecording(r))) }) { Text("Replay now") }
            Button(onClick = {
                MynaService.instance?.recipes?.save(Recipes.fromRecording(r, golden = true)); refresh()
            }) { Text("Save as golden") }
        }
    }

    @Composable
    private fun RecipesCard() {
        val golden = recipes.filter { it.golden }
        Text("Recipes (${recipes.size})", style = MaterialTheme.typography.titleMedium)
        Button(enabled = serviceOn && golden.isNotEmpty(), onClick = { replay(golden) }) {
            Text("★ Golden run (${golden.size})")
        }
        recipes.forEach { r ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text((if (r.golden) "★ " else "") + "${r.utterance} · ${r.app.substringAfterLast('.')}",
                    Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                Button(enabled = serviceOn, onClick = { replay(listOf(r)) }) { Text("Run") }
            }
        }
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

    private fun replay(list: List<Recipe>) {
        MynaService.instance?.replay(list) { runOnUiThread { refresh() } }
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
