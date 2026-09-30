package com.example.myna_mimicyourinteractionsautomate

import com.example.myna_mimicyourinteractionsautomate.compile.Compiler
import com.example.myna_mimicyourinteractionsautomate.recipe.Recording
import com.example.myna_mimicyourinteractionsautomate.recipe.Step
import com.example.myna_mimicyourinteractionsautomate.recipe.StepType
import com.example.myna_mimicyourinteractionsautomate.recipe.Target
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 30 Sep 11:26 teach (Zomato): in Domino's menu search the user typed only "ma", then tapped ADD, which sent no event.
 * The recipe got {query} = Domino's but no {item}, and no ADD step before "confirm the options sheet".
 */
class ShortMenuSearchTest {
    private val app = "com.application.zomato"

    @Test fun twoLetterPrefixOfOneSpokenWordIsThatBlank() {
        val rec = Recording("Order a Margherita pizza from Domino's on Zomato", app, 1, listOf(
            Step(StepType.LAUNCH, pkg = app),
            Step(StepType.GOAL, goal = "search", text = "dominos", args = mapOf("pick" to "Domino's Pizza")),
            Step(StepType.TYPE, target = Target(label = "Search in Domino's Pizza", id = "edittext"), text = "ma")))
        val blanks = Compiler.findBlanks(rec)
        assertEquals(listOf("Margherita", "Domino's"), blanks.map { it.value })
        blanks.forEachIndexed { i, b -> b.name = listOf("item", "restaurant")[i] }
        assertEquals("{item}", Compiler.applyBlanks(rec.steps, blanks)[2].text)
    }

    @Test fun twoLettersMatchingSeveralSpokenWordsAreNotABlank() {
        // "pi" could be "pizza" or "pineapple": too short to tell, so no guess.
        val rec = Recording("order a pineapple pizza", app, 1, listOf(Step(StepType.TYPE, target = Target(label = "Search"), text = "pi")))
        assertTrue(Compiler.findBlanks(rec).isEmpty())
    }
}
