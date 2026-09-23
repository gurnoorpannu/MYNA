package com.example.myna_mimicyourinteractionsautomate.llm

import org.json.JSONArray
import org.json.JSONObject

/**
 * Tiny JSON Schema subset: type (incl. type lists), properties, required, items, enum.
 * ponytail: subset only — add keywords (minItems, pattern…) when a prompt actually needs them.
 */
object JsonSchema {

    /** First problem found, or null when [value] matches [schema]. */
    fun validate(value: Any?, schema: JSONObject, path: String = "$"): String? {
        val types = when (val t = schema.opt("type")) {
            is String -> listOf(t)
            is JSONArray -> List(t.length()) { t.getString(it) }
            else -> emptyList()
        }
        if (types.isNotEmpty() && types.none { matches(value, it) }) return "$path: expected ${types.joinToString("|")}, got ${describe(value)}"

        schema.optJSONArray("enum")?.let { e ->
            if ((0 until e.length()).none { e.get(it) == value }) return "$path: $value not in $e"
        }
        if (value is JSONObject) {
            schema.optJSONArray("required")?.let { req ->
                for (i in 0 until req.length()) if (!value.has(req.getString(i))) return "$path: missing \"${req.getString(i)}\""
            }
            val props = schema.optJSONObject("properties")
            props?.keys()?.forEach { k ->
                if (value.has(k)) validate(value.opt(k), props.getJSONObject(k), "$path.$k")?.let { return it }
            }
        }
        if (value is JSONArray) schema.optJSONObject("items")?.let { items ->
            for (i in 0 until value.length()) validate(value.opt(i), items, "$path[$i]")?.let { return it }
        }
        return null
    }

    /** Smallest value that satisfies [schema] — used by mock mode. */
    fun example(schema: JSONObject): Any? {
        schema.optJSONArray("enum")?.let { return it.opt(0) }
        val type = schema.opt("type").let { if (it is JSONArray) it.optString(0) else it as? String }
        return when (type) {
            "object" -> JSONObject().also { o ->
                val props = schema.optJSONObject("properties")
                props?.keys()?.forEach { k -> o.put(k, example(props.getJSONObject(k)) ?: JSONObject.NULL) }
            }
            "array" -> JSONArray()
            "string" -> ""
            "number", "integer" -> 0
            "boolean" -> false
            else -> null
        }
    }

    private fun matches(v: Any?, type: String) = when (type) {
        "object" -> v is JSONObject
        "array" -> v is JSONArray
        "string" -> v is String
        "number" -> v is Number
        "integer" -> v is Number && v.toDouble() % 1.0 == 0.0
        "boolean" -> v is Boolean
        "null" -> v == null || v == JSONObject.NULL
        else -> true
    }

    private fun describe(v: Any?) = when (v) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> "object"
        is JSONArray -> "array"
        else -> v::class.simpleName
    }
}
