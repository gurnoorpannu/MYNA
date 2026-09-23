package com.example.myna_mimicyourinteractionsautomate.llm

import com.example.myna_mimicyourinteractionsautomate.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.sqrt

class LlmException(msg: String) : Exception(msg)

/**
 * The only door to the cloud model. Never called on the normal replay path.
 * Mock mode (no API key, or set [mock] = true) returns [canned] replies keyed by the schema's "title",
 * else the smallest JSON that fits the schema; [embed] falls back to a local hashed bag-of-words.
 */
object Llm {
    var mock = BuildConfig.GEMINI_API_KEY.isBlank()
    val canned = mutableMapOf<String, String>()
    @Volatile var calls = 0          // AI calls — shown in the run log / PPT numbers

    private const val BASE = "https://generativelanguage.googleapis.com/v1beta/models/"

    suspend fun llm(prompt: String, schema: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        calls++
        if (mock) return@withContext mockReply(schema)
        val full = "$prompt\n\nReply with JSON only, matching this JSON Schema:\n$schema"
        val first = generate(full)
        val err = problem(first, schema) ?: return@withContext JSONObject(first)
        val second = generate("$full\n\nYour previous reply was invalid ($err):\n$first\nReply again with corrected JSON only.")
        problem(second, schema)?.let { throw LlmException("invalid after retry: $it") }
        JSONObject(second)
    }

    suspend fun embed(text: String): FloatArray = withContext(Dispatchers.IO) {
        if (mock) return@withContext hashEmbed(text)
        val body = JSONObject().put("content", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", text))))
        val values = post("${BuildConfig.GEMINI_EMBED_MODEL}:embedContent", body)
            .getJSONObject("embedding").getJSONArray("values")
        FloatArray(values.length()) { values.getDouble(it).toFloat() }
    }

    fun cosine(a: FloatArray, b: FloatArray): Float {
        var dot = 0f; var na = 0f; var nb = 0f
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        return if (na == 0f || nb == 0f) 0f else dot / (sqrt(na) * sqrt(nb))
    }

    internal fun mockReply(schema: JSONObject): JSONObject =
        canned[schema.optString("title")]?.let(::JSONObject) ?: (JsonSchema.example(schema) as JSONObject)

    /** Offline stand-in for embeddings: words hashed into 256 buckets. "domino's" == "dominos". */
    internal fun hashEmbed(text: String): FloatArray {
        val v = FloatArray(256)
        text.lowercase().replace("'", "").split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.isNotEmpty() }
            .forEach { v[Math.floorMod(it.hashCode(), 256)] += 1f }
        return v
    }

    private fun problem(text: String, schema: JSONObject): String? =
        runCatching { JSONObject(text) }.fold({ JsonSchema.validate(it, schema) }, { "not a JSON object" })

    private fun generate(prompt: String): String {
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            .put("generationConfig", JSONObject().put("temperature", 0).put("responseMimeType", "application/json")
                // No hidden "thinking" tokens: our calls are short JSON picks, latency matters more.
                .put("thinkingConfig", JSONObject().put("thinkingBudget", 0)))
        val text = post("${BuildConfig.GEMINI_MODEL}:generateContent", body)
            .getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
        return text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    }

    private fun post(path: String, body: JSONObject): JSONObject {
        val c = URL(BASE + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 10_000
            c.readTimeout = 30_000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("x-goog-api-key", BuildConfig.GEMINI_API_KEY)
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val ok = c.responseCode in 200..299
            val reply = (if (ok) c.inputStream else c.errorStream).bufferedReader().use { it.readText() }
            if (!ok) throw LlmException("HTTP ${c.responseCode}: ${reply.take(300)}")
            return JSONObject(reply)
        } finally {
            c.disconnect()
        }
    }
}
