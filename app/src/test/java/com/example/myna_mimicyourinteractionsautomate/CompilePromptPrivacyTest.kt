package com.example.myna_mimicyourinteractionsautomate

import com.example.myna_mimicyourinteractionsautomate.compile.Compiler
import com.example.myna_mimicyourinteractionsautomate.recipe.Recording
import com.example.myna_mimicyourinteractionsautomate.recipe.Step
import com.example.myna_mimicyourinteractionsautomate.recipe.StepType
import com.example.myna_mimicyourinteractionsautomate.recipe.Target
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one compile call goes to Gemini: phone numbers, pincodes and e-mails seen while teaching must be masked first. */
class CompilePromptPrivacyTest {
    private val rec = Recording("Order a Margherita pizza from Domino's on Zomato", "com.application.zomato", 1, listOf(
        Step(StepType.LAUNCH, pkg = "com.application.zomato"),
        Step(StepType.TYPE, target = Target(label = "Search for restaurant"), text = "Domino's", submit = true),
        Step(StepType.TAP, target = Target(label = "ADD", nearby = listOf("Margherita Pizza", "₹299"))),
        // The cart's address chip: its card shows the saved address, a phone number and an e-mail.
        Step(StepType.TAP, target = Target(label = "Delivering to Home",
            nearby = listOf("50 Harkishan Garden, Amritsar 143001", "+91 98765 43210", "durvish@example.com")))))

    @Test fun compilePromptMasksPersonalData() {
        val p = Compiler.prompt(rec, rec.steps, Compiler.findBlanks(rec))
        for (s in listOf("143001", "98765 43210", "durvish@example.com")) assertFalse("prompt leaks \"$s\":\n$p", p.contains(s))
        // Still useful to the AI: dish, price and screen words stay.
        for (s in listOf("Margherita Pizza", "₹299", "Delivering to Home", "Domino's")) assertTrue("prompt lost \"$s\"", p.contains(s))
    }
}
