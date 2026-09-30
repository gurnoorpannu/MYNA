package com.example.myna_mimicyourinteractionsautomate

import com.example.myna_mimicyourinteractionsautomate.intent.IntentMatcher
import com.example.myna_mimicyourinteractionsautomate.recipe.Recipe
import com.example.myna_mimicyourinteractionsautomate.recipe.Slot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * No-AI blank filling (Gemini free quota ran out on 30 Sep: every call HTTP 429). Leftover words used to become new
 * values in template order: "put a phone cover for nothing 3A…" → item = "put", item2 = "phone cover".
 */
class OfflineFillTest {
    // Shapes of the two recipes on the phone on 30 Sep.
    private val amazon = Recipe("a", "in.amazon.mShop.android.shopping", "add a phone case for my nothing phone 3a to my Amazon cart",
        summary = "add a {item} for my {item2} to my Amazon cart", subtasks = emptyList(),
        slots = mapOf("item" to Slot("phone case"), "item2" to Slot("nothing phone 3a")))
    private val zomato = Recipe("z", "com.application.zomato", "order a margherita pizza from Domino's on Zomato",
        summary = "order {item} from {restaurant} on Zomato", subtasks = emptyList(),
        slots = mapOf("item" to Slot("margherita"), "restaurant" to Slot("Domino's")))

    @Test fun aVerbIsNeverAValue() {
        val f = IntentMatcher.fillByTemplate("put a phone cover for nothing 3A to my Amazon cart", amazon)
        assertEquals(mapOf("item" to "phone cover", "item2" to "nothing 3A"), f.values)
    }

    @Test fun wordsOfTheTaughtCommandAreNotNewValues() {
        // T3 paraphrase 2: "pizza" was part of what was taught, not a restaurant.
        val f = IntentMatcher.fillByTemplate("I want to order margherita pizza on zomato", zomato)
        assertEquals("margherita", f.values["item"])
        assertFalse(f.values.toString(), "restaurant" in f.values)
        // T4: the new dish, without the fixed word "pizza".
        assertEquals("Farmhouse", IntentMatcher.fillByTemplate("Order a Farmhouse pizza from Domino's on Zomato", zomato).values["item"])
    }

    @Test fun t3FirstParaphraseKeepsBothValues() {
        val f = IntentMatcher.fillByTemplate("Get me a margherita from dominos", zomato)
        assertEquals(mapOf("item" to "margherita", "restaurant" to "Domino's"), f.values)
    }
}
