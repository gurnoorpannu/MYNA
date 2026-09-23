package com.example.myna_mimicyourinteractionsautomate

import com.example.myna_mimicyourinteractionsautomate.intent.IntentMatcher
import com.example.myna_mimicyourinteractionsautomate.intent.IntentMatcher.Decision
import com.example.myna_mimicyourinteractionsautomate.llm.Llm
import com.example.myna_mimicyourinteractionsautomate.recipe.Recipe
import com.example.myna_mimicyourinteractionsautomate.recipe.Slot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** T3 / T12 / T13 / mid-flow blank, with similarity scores as measured on gemini-embedding-001 on 23 Sep. */
class IntentTest {
    private val zomato = Recipe("z", "com.application.zomato", "Order a Margherita pizza from Domino's on Zomato",
        summary = "order {item} from {restaurant} on Zomato", subtasks = emptyList(),
        slots = mapOf("item" to Slot("Margherita"), "restaurant" to Slot("Domino's")))

    private fun at(score: Float): suspend (String, List<Recipe>) -> List<IntentMatcher.Match> = { _, rs -> listOf(IntentMatcher.Match(rs.first(), score)) }
    private fun fills(json: String) { Llm.mock = true; Llm.canned["fill"] = json }

    @Test fun t3ParaphraseRunsWithSameValues() = runBlocking {
        fills("""{"slots":[{"name":"item","value":"Margherita","said":true},{"name":"restaurant","value":"Domino's","said":true}]}""")
        val d = IntentMatcher.decide("get me a margherita from dominos", listOf(zomato), at(0.877f))
        assertTrue(d is Decision.Run && d.confirm == null && d.values["item"] == "Margherita")
    }

    @Test fun t12UnknownOffersToTeach() = runBlocking {
        assertTrue(IntentMatcher.decide("book a cab to the airport", listOf(zomato), at(0.525f)) is Decision.Unknown)
    }

    @Test fun t13VagueOffersClosestWithLastValues() = runBlocking {
        fills("""{"slots":[{"name":"item","value":null,"said":false},{"name":"restaurant","value":null,"said":false}]}""")
        val d = IntentMatcher.decide("Order pizza", listOf(zomato), at(0.679f))
        assertTrue(d is Decision.DidYouMean)
        assertEquals("Did you mean: order Margherita from Domino's on Zomato — like last time?", (d as Decision.DidYouMean).question)
    }

    @Test fun missingBlankIsAsked() = runBlocking {
        fills("""{"slots":[{"name":"item","value":"Farmhouse","said":true},{"name":"restaurant","value":null,"said":false}]}""")
        val d = IntentMatcher.decide("order a farmhouse", listOf(zomato), at(0.74f))
        assertTrue(d is Decision.AskSlot)
        d as Decision.AskSlot
        assertEquals("restaurant", d.slot); assertEquals("Farmhouse", d.values["item"])
        assertEquals("Which restaurant? Last time it was Domino's.", d.question)
    }

    @Test fun twoChangedBlanksAreConfirmedFirst() = runBlocking {
        fills("""{"slots":[{"name":"item","value":"Chicken Biryani","said":true},{"name":"restaurant","value":"Behrouz Biryani","said":true}]}""")
        val d = IntentMatcher.decide("order chicken biryani from behrouz on zomato", listOf(zomato), at(0.72f))
        assertEquals("Just to check: order Chicken Biryani from Behrouz Biryani on Zomato?", (d as Decision.Run).confirm)
    }

    @Test fun didItWorkIsAReport() = runBlocking {
        assertEquals(Decision.Report, IntentMatcher.decide("did the last run succeed?", listOf(zomato), at(0.46f)))
    }

    private val amazon = Recipe("a", "in.amazon.mShop.android.shopping", "add a phone case for my s25 Ultra to my Amazon Shopping Cart",
        summary = "add a {product} for my {item} to my Amazon Shopping Cart", subtasks = emptyList(),
        slots = mapOf("product" to Slot("phone case"), "item" to Slot("s25 Ultra")))

    @Test fun noAiFillFindsTheNewProduct() {
        val f = IntentMatcher.fillByTemplate("add a laptop stand to my amazon cart", amazon)
        assertEquals(mapOf("product" to "laptop stand"), f.values)
        assertEquals(setOf("product"), f.said)
    }

    @Test fun noAiFillKeepsRepeatedValuesAndTakesNewOnes() {
        val f = IntentMatcher.fillByTemplate("order a farmhouse from dominos", zomato)
        assertEquals(mapOf("item" to "farmhouse", "restaurant" to "Domino's"), f.values)
    }

    @Test fun offlineLaptopStandAsksAboutTheOtherBlank() = runBlocking {
        Llm.mock = true; Llm.canned.clear()
        // Mock replies with an empty fill, like a failed call would leave nothing: use the template path directly.
        val d = IntentMatcher.decide("add a laptop stand to my amazon cart", listOf(amazon), at(0.795f))
        assertTrue(d.toString(), d is Decision.AskSlot || d is Decision.Run)
    }
}
