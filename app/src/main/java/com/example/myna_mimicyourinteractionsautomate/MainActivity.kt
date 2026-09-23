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
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.myna_mimicyourinteractionsautomate.a11y.MynaService
import com.example.myna_mimicyourinteractionsautomate.llm.Llm
import kotlinx.coroutines.launch
import org.json.JSONObject
import com.example.myna_mimicyourinteractionsautomate.ui.theme.MYNAMimicYourINteractionsAutomateTheme

class MainActivity : ComponentActivity() {

    private var serviceOn by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MYNAMimicYourINteractionsAutomateTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { pad ->
                    Column(Modifier.padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Accessibility service: " + if (serviceOn) "ON" else "OFF")
                        Button(onClick = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) {
                            Text("Open accessibility settings")
                        }
                        var dumping by remember { mutableStateOf(MynaService.dumping) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(checked = dumping, enabled = serviceOn, onCheckedChange = {
                                MynaService.dumping = it; dumping = it
                            })
                            Text("  Dump screen trees", Modifier.padding(start = 8.dp))
                        }
                        Text("Dumps: adb pull /sdcard/Android/data/$packageName/files/dumps")

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
                }
            }
        }
    }

    private companion object {
        val PING_SCHEMA = JSONObject("""{"type":"object","required":["reply"],"properties":{"reply":{"type":"string"}}}""")
    }

    override fun onResume() {
        super.onResume()
        serviceOn = MynaService.instance != null
    }
}
