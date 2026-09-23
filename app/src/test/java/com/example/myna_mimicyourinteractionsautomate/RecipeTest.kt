package com.example.myna_mimicyourinteractionsautomate

import com.example.myna_mimicyourinteractionsautomate.recipe.End
import com.example.myna_mimicyourinteractionsautomate.recipe.Recipe
import com.example.myna_mimicyourinteractionsautomate.recipe.RecipeJson
import com.example.myna_mimicyourinteractionsautomate.recipe.StepType
import org.junit.Assert.assertEquals
import org.junit.Test

class RecipeTest {
    // The design doc's draft, verbatim shape.
    private val draft = """
    {
      "id": "order_food_zomato",
      "app": "com.application.zomato",
      "utterance": "Order a Margherita pizza from Domino's on Zomato",
      "paraphrases": ["get me a margherita from dominos"],
      "slots": {
        "item":       {"value": "Margherita", "neighbours": ["Farmhouse", "Peppy Paneer"]},
        "restaurant": {"value": "Domino's"},
        "qty":        {"default": 1},
        "address":    {"default": "Home"}
      },
      "defaults": {"size": "Regular"},
      "subtasks": [
        {"name": "search_restaurant", "why": "Find Domino's",
         "steps": [
           {"type": "tap",  "target": {"label": "Search", "id": "search", "cls": "EditText"},
            "screen": {"signature": "zomato|Home", "note": "Home feed"}, "why": "Open the search box"},
           {"type": "type", "text": "{restaurant}"},
           {"type": "goal", "goal": "set_qty", "args": {"n": "{qty}"}}
         ]}
      ],
      "end": "payment_screen",
      "forks": [],
      "someFutureField": true
    }
    """

    @Test fun parsesDesignDraftAndRoundTrips() {
        val r = RecipeJson.decodeFromString<Recipe>(draft)
        assertEquals("1", r.slots["qty"]!!.default)
        assertEquals(listOf("Farmhouse", "Peppy Paneer"), r.slots["item"]!!.neighbours)
        assertEquals(StepType.GOAL, r.subtasks[0].steps[2].type)
        assertEquals(End.PAYMENT_SCREEN, r.end)
        assertEquals(r, RecipeJson.decodeFromString<Recipe>(RecipeJson.encodeToString(r)))
    }
}
