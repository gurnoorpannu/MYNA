package com.example.myna_mimicyourinteractionsautomate

import com.example.myna_mimicyourinteractionsautomate.intent.SpeechFix
import com.example.myna_mimicyourinteractionsautomate.recipe.Recipe
import com.example.myna_mimicyourinteractionsautomate.recipe.Slot
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechFixTest {
    private val zomato = Recipe("z", "com.application.zomato", "Order a Margherita pizza from Domino's on Zomato", subtasks = emptyList(),
        summary = "order {item} from {restaurant} on Zomato",
        slots = mapOf("item" to Slot("Margherita", neighbours = listOf("Farmhouse Pizza", "Peppy Paneer Pizza")), "restaurant" to Slot("Domino's")))
    private val vocab = SpeechFix.vocabulary(listOf(zomato))

    @Test fun misheardDishIsFixed() {
        val r = SpeechFix.fix("order a marherator from dominos", vocab)
        assertEquals("order a Margherita from Domino's", r.text)
    }

    @Test fun splitWordIsJoined() {
        assertEquals("order a Farmhouse", SpeechFix.fix("order a farm house", vocab).text)
    }

    @Test fun everydayWordsAreLeftAlone() {
        assertEquals("did the last run work", SpeechFix.fix("did the last run work", vocab).text)
        assertEquals("order pizza", SpeechFix.fix("order pizza", vocab).text)
    }
}
