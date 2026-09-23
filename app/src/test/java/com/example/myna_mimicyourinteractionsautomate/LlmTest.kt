package com.example.myna_mimicyourinteractionsautomate

import com.example.myna_mimicyourinteractionsautomate.llm.JsonSchema
import com.example.myna_mimicyourinteractionsautomate.llm.Llm
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmTest {
    // The AI helper's allowed outputs (design §4.6), as one schema.
    private val action = JSONObject("""
      {"title": "action", "type": "object", "required": ["kind"],
       "properties": {
         "kind": {"type": "string", "enum": ["tap", "type", "scroll", "ask", "stuck"]},
         "id":   {"type": ["integer", "null"]},
         "text": {"type": "string"}
       }}""")

    @Test fun validatorCatchesBadReplies() {
        assertNull(JsonSchema.validate(JSONObject("""{"kind":"tap","id":3}"""), action))
        assertNull(JsonSchema.validate(JSONObject("""{"kind":"stuck","id":null,"text":"login"}"""), action))
        assertNotNull(JsonSchema.validate(JSONObject("""{"id":3}"""), action))                 // missing kind
        assertNotNull(JsonSchema.validate(JSONObject("""{"kind":"dance"}"""), action))         // not in enum
        assertNotNull(JsonSchema.validate(JSONObject("""{"kind":"tap","id":2.5}"""), action))  // not integer
    }

    @Test fun mockModeIsCannedThenSchemaShaped() = runBlocking {
        Llm.mock = true
        assertNull(JsonSchema.validate(Llm.llm("anything", action), action))
        Llm.canned["action"] = """{"kind":"ask","text":"Which restaurant?"}"""
        assertEquals("Which restaurant?", Llm.llm("anything", action).getString("text"))
    }

    @Test fun mockEmbedRanksParaphraseAboveUnrelated() = runBlocking {
        Llm.mock = true
        val taught = Llm.embed("Order a Margherita pizza from Domino's on Zomato")
        val para = Llm.embed("get me a margherita from dominos")
        val other = Llm.embed("book a cab to the airport")
        assertTrue(Llm.cosine(taught, para) > Llm.cosine(taught, other))
    }
}
