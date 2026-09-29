package com.example.myna_mimicyourinteractionsautomate

import com.example.myna_mimicyourinteractionsautomate.intent.Extras
import com.example.myna_mimicyourinteractionsautomate.intent.IntentMatcher
import com.example.myna_mimicyourinteractionsautomate.intent.IntentMatcher.Decision
import com.example.myna_mimicyourinteractionsautomate.llm.Llm
import com.example.myna_mimicyourinteractionsautomate.recipe.Recipe
import com.example.myna_mimicyourinteractionsautomate.recipe.Slot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Quantity/address phrasings (T5/T6) and the unknown-command path (T12), including offline (no API key). */
class IntentEdgeTest {
    private val zomato = Recipe("z", "com.application.zomato", "Order a Margherita pizza from Domino's on Zomato",
        summary = "order {item} from {restaurant} on Zomato", subtasks = emptyList(), golden = true,
        slots = mapOf("item" to Slot("Margherita"), "restaurant" to Slot("Domino's")))

    // --- T5: quantity

    @Test fun digitsWordsAndCouple() {
        for ((said, n) in listOf("order 2 margheritas" to 2, "order two margheritas" to 2, "order a couple of margheritas" to 2,
                "order couple of margheritas" to 2, "get me 3 farmhouse" to 3, "order ten garlic breads" to 10,
                "order 2x margherita" to 2, "order margherita x2" to 2, "order 2 x margherita" to 2))
            assertEquals(said, n, Extras.parse(said).qty)
    }

    @Test fun numbersThatAreNotQuantities() {
        for (said in listOf("order a margherita from dominos", "add a phone case for my s25 ultra",
                "order someone a margherita", "order a double cheese burst"))
            assertNull(said, Extras.parse(said).qty)
        assertNull(Extras.parse("order 50 margheritas").qty)   // above 20: not trusted as a count
    }

    @Test fun countedWordIsMadeSingularButTheRestaurantIsNot() {
        assertEquals("order margherita from dominos", Extras.parse("order two margheritas from dominos").rest)
        val rest = Extras.parse("order a couple of farmhouse pizzas").rest
        assertTrue(rest, rest.endsWith("farmhouse pizza"))
    }

    // --- T6: address

    @Test fun addressPhrasings() {
        for ((said, where) in listOf("order a margherita and deliver to work" to "Work", "deliver it to my office" to "Office",
                "order a margherita at home" to "Home", "send them to home" to "Home", "use my work address" to "Work",
                "ship to the office" to "Office", "bring it to rahul's place" to "Rahul's place"))
            assertEquals(said, where, Extras.parse(said).address)
    }

    @Test fun homeOrWorkInsideANameIsNotAnAddress() {
        for (said in listOf("order home style chicken from dominos", "add a work bench to my amazon cart", "order a margherita"))
            assertNull(said, Extras.parse(said).address)
    }

    @Test fun quantityAndAddressTogether() {
        val p = Extras.parse("order 2 margheritas from dominos and deliver to work")
        assertEquals(2, p.qty); assertEquals("Work", p.address)
        assertTrue(p.rest, p.rest.startsWith("order margherita from dominos"))
    }

    @Test fun extrasReachTheRunDecision() = runBlocking {
        Llm.mock = true
        Llm.canned["fill"] = """{"slots":[{"name":"item","value":"Margherita","said":true},{"name":"restaurant","value":"Domino's","said":true}]}"""
        val d = IntentMatcher.decide("order two margheritas from dominos and deliver to work", listOf(zomato)) { _, rs -> listOf(IntentMatcher.Match(rs[0], 0.85f)) }
        assertTrue(d.toString(), d is Decision.Run)
        d as Decision.Run
        assertEquals("2", d.values["qty"]); assertEquals("Work", d.values["address"])
        Llm.canned.remove("fill"); Unit
    }

    // --- T12: unknown commands

    @Test fun unrelatedCommandIsUnknownOffline() = runBlocking {
        Llm.mock = true; Llm.canned.clear()
        for (said in listOf("book a cab to the airport", "set an alarm for 6", "play some music"))
            assertTrue(said, IntentMatcher.decide(said, listOf(zomato)) is Decision.Unknown)
    }

    @Test fun nothingTaughtYetIsUnknown() = runBlocking {
        assertTrue(IntentMatcher.decide("order a margherita", emptyList()) is Decision.Unknown)
    }

    @Test fun offlineParaphraseIsNotUnknown() = runBlocking {
        // No API key = mock embeddings (word overlap). A close paraphrase must still be recognised, not "haven't learned that".
        Llm.mock = true; Llm.canned.clear()
        val d = IntentMatcher.decide("get me a margherita from dominos", listOf(zomato))
        assertTrue(d.toString(), d !is Decision.Unknown)
        assertTrue(IntentMatcher.decide("Order a Margherita pizza from Domino's on Zomato", listOf(zomato)) is Decision.Run)
    }
}
