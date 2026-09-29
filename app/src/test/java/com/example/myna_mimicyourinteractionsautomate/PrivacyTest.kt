package com.example.myna_mimicyourinteractionsautomate

import com.example.myna_mimicyourinteractionsautomate.compile.Compiler
import com.example.myna_mimicyourinteractionsautomate.llm.Llm
import com.example.myna_mimicyourinteractionsautomate.recipe.Recording
import com.example.myna_mimicyourinteractionsautomate.recipe.Step
import com.example.myna_mimicyourinteractionsautomate.recipe.StepType
import com.example.myna_mimicyourinteractionsautomate.recipe.Target
import com.example.myna_mimicyourinteractionsautomate.replay.Privacy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Privacy mask before any cloud call (design §4.6) and the no-AI rule for messaging apps. */
class PrivacyTest {

    @Test fun masksPhoneNumbersPincodesEmailsAndCards() {
        assertEquals("call <number>", Privacy.mask("call +91 98765 43210"))
        assertEquals("call <number>", Privacy.mask("call 9876543210"))
        assertEquals("Ranjit Avenue, Amritsar <number>", Privacy.mask("Ranjit Avenue, Amritsar 143001"))
        assertEquals("mail <email> today", Privacy.mask("mail durvish.k+test@example.co.in today"))
        assertEquals("card <number>", Privacy.mask("card 4111 1111 1111 1111"))
        assertEquals("card <number>", Privacy.mask("card 4111-1111-1111-1111"))
    }

    @Test fun leavesProductNamesPricesAndCountsAlone() {
        for (s in listOf("Galaxy S25 Ultra phone case", "₹1,299", "₹299", "2 items added", "35–40 mins", "Add item ₹109", "order 12 samosas"))
            assertEquals(s, Privacy.mask(s))
    }

    @Test fun messagingAppsArePrivateShoppingAppsAreNot() {
        for (p in listOf("com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "com.google.android.apps.messaging", "com.samsung.android.messaging"))
            assertTrue(p, Privacy.isPrivate(p))
        for (p in listOf("com.application.zomato", "in.amazon.mShop.android.shopping", null))
            assertFalse("$p", Privacy.isPrivate(p))
    }

    @Test fun teachingInWhatsappMakesNoAiCall() = runBlocking {
        Llm.mock = true; Llm.canned.clear()
        val rec = Recording("send hi to Tasty Bites on WhatsApp", "com.whatsapp", 1, listOf(
            Step(StepType.LAUNCH, pkg = "com.whatsapp"),
            Step(StepType.TAP, target = Target(label = "Tasty Bites")),
            Step(StepType.TYPE, target = Target(label = "Message"), text = "hi")))
        val before = Llm.calls
        val r = Compiler.compile(rec).recipe
        assertEquals("no AI call for a messaging app", before, Llm.calls)
        assertTrue(r.paraphrases.isEmpty())   // offline naming only
        // The same demo in a shopping app does use the one compile call.
        Compiler.compile(rec.copy(app = "com.application.zomato"))
        assertEquals(before + 1, Llm.calls)
    }
}
