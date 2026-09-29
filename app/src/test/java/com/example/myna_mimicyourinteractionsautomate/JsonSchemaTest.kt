package com.example.myna_mimicyourinteractionsautomate

import com.example.myna_mimicyourinteractionsautomate.llm.JsonSchema
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The validator that decides whether a Gemini reply is used, retried once, or thrown away. */
class JsonSchemaTest {
    // Same shape as IntentMatcher's blank-filling schema.
    private val fill = JSONObject("""
    {"title": "fill", "type": "object", "required": ["slots"],
     "properties": {"slots": {"type": "array", "items": {"type": "object", "required": ["name", "value", "said"],
        "properties": {"name": {"type": "string"}, "value": {"type": ["string", "null"]}, "said": {"type": "boolean"}}}}}}""")

    @Test fun goodRepliesPass() {
        assertNull(JsonSchema.validate(JSONObject("""{"slots":[{"name":"item","value":"Farmhouse","said":true}]}"""), fill))
        assertNull(JsonSchema.validate(JSONObject("""{"slots":[{"name":"item","value":null,"said":false}]}"""), fill))
        assertNull(JsonSchema.validate(JSONObject("""{"slots":[]}"""), fill))
        assertNull(JsonSchema.validate(JSONObject("""{"slots":[],"extra":"ignored"}"""), fill))
    }

    @Test fun badRepliesSayWhereAndWhy() {
        assertEquals("$: missing \"slots\"", JsonSchema.validate(JSONObject("{}"), fill))
        assertEquals("$.slots: expected array, got object", JsonSchema.validate(JSONObject("""{"slots":{}}"""), fill))
        assertEquals("$.slots[1]: missing \"said\"",
            JsonSchema.validate(JSONObject("""{"slots":[{"name":"a","value":"x","said":true},{"name":"b","value":"y"}]}"""), fill))
        assertEquals("$.slots[0].said: expected boolean, got String",
            JsonSchema.validate(JSONObject("""{"slots":[{"name":"a","value":"x","said":"yes"}]}"""), fill))
        assertEquals("$.slots[0].value: expected string|null, got Int",
            JsonSchema.validate(JSONObject("""{"slots":[{"name":"a","value":3,"said":true}]}"""), fill))
    }

    @Test fun malformedJsonIsRejectedBeforeValidation() {
        // Llm.llm only validates what parses: these never reach the executor.
        for (bad in listOf("", "not json", "{\"slots\": [", "```json {} ```", "[]")) {
            val parsed = runCatching { JSONObject(bad) }
            assertTrue(bad, parsed.isFailure)
        }
    }

    @Test fun integerMeansWholeNumber() {
        val s = JSONObject("""{"type":"integer"}""")
        assertNull(JsonSchema.validate(3, s))
        assertNull(JsonSchema.validate(3.0, s))
        assertEquals("$: expected integer, got Double", JsonSchema.validate(2.5, s))
    }

    @Test fun enumAndNull() {
        val s = JSONObject("""{"type":"string","enum":["tap","stuck"]}""")
        assertNull(JsonSchema.validate("tap", s))
        assertTrue(JsonSchema.validate("buy", s)!!.contains("not in"))
        assertNull(JsonSchema.validate(JSONObject.NULL, JSONObject("""{"type":"null"}""")))
        assertNull(JsonSchema.validate("anything", JSONObject("{}")))   // no type = anything goes
    }

    @Test fun exampleIsTheSmallestValidValue() {
        val ex = JsonSchema.example(fill) as JSONObject
        assertNull(JsonSchema.validate(ex, JSONObject("""{"type":"object","properties":{"slots":{"type":"array"}}}""")))
        assertEquals(0, (ex.get("slots") as JSONArray).length())
        assertEquals("tap", JsonSchema.example(JSONObject("""{"type":"string","enum":["tap","stuck"]}""")))
        assertEquals(false, JsonSchema.example(JSONObject("""{"type":"boolean"}""")))
        assertEquals(0, JsonSchema.example(JSONObject("""{"type":["integer","null"]}""")))
    }
}
