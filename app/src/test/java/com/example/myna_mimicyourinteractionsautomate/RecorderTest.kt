package com.example.myna_mimicyourinteractionsautomate

import com.example.myna_mimicyourinteractionsautomate.recipe.KeyKind
import com.example.myna_mimicyourinteractionsautomate.recipe.Screen
import com.example.myna_mimicyourinteractionsautomate.recipe.StepType
import com.example.myna_mimicyourinteractionsautomate.recipe.SystemKey
import com.example.myna_mimicyourinteractionsautomate.recipe.Target
import com.example.myna_mimicyourinteractionsautomate.recipe.UniqueKey
import com.example.myna_mimicyourinteractionsautomate.record.Recorder
import com.example.myna_mimicyourinteractionsautomate.record.describe
import org.junit.Assert.assertEquals
import org.junit.Test

class RecorderTest {
    private var clock = 0L
    private val rec = Recorder("order margherita", "com.application.zomato") { clock }
    private val home = Screen("zomato|Home")
    private val menu = Screen("zomato|Menu")
    private fun tap(label: String) = Target(label = label, key = UniqueKey(KeyKind.LABEL, label))
    private val search = Target(label = "Search", id = "search", cls = "EditText")

    @Test fun typingMergesIntoFinalText() {
        rec.onTap(search, home)
        listOf("D", "Do", "Dom", "Domino's").forEach { rec.onText(search, it, home) }
        assertEquals(listOf("tap Search", "type \"Domino's\" into Search"), rec.steps.map { it.describe() })
    }

    @Test fun doubleTapIsDroppedButSlowRepeatIsKept() {
        rec.onTap(tap("+"), menu); clock += 200
        rec.onTap(tap("+"), menu); clock += 900
        rec.onTap(tap("+"), menu)    // deliberate second "+" (qty 3)
        assertEquals(2, rec.steps.size)
    }

    @Test fun clearedFieldKeepsTypedText() {
        rec.onText(search, "Domino's", home)
        rec.onText(search, "", home)
        assertEquals("Domino's", rec.steps.single().text)
    }

    @Test fun tapThenBackIsMarkedAsMistake() {
        rec.onTap(tap("Farmhouse"), menu)
        rec.onKey(SystemKey.BACK)
        rec.onTap(tap("Margherita"), menu)
        assertEquals(listOf(true, true, false), rec.steps.map { it.noise })
        assertEquals("(mistake?) tap Farmhouse", rec.steps[0].describe())
    }

    @Test fun firstSettledScreenBecomesNext() {
        rec.onLaunch("com.application.zomato")
        rec.onScreen(home, "home")
        rec.onScreen(menu, "menu")          // later screen change doesn't overwrite
        val r = rec.finish()
        assertEquals(StepType.LAUNCH, r.steps[0].type)
        assertEquals(home, r.steps[0].next)
        assertEquals(setOf(home.signature, menu.signature), r.snapshots.keys)
    }
}

class SearchGoalRecorderTest {
    @Test fun typingThenLandingBecomesOneSearchStep() {
        val rec = Recorder("order margherita from dominos", "com.application.zomato") { 0L }
        val field = Target(label = "Restaurant name or a dish...", id = "edittext")
        rec.onText(field, "dominos", Screen("s"))
        rec.onSearchLanded("Domino's Pizza")   // suggestions → results
        rec.onSearchLanded("Domino's Pizza")   // results → restaurant page
        assertEquals(listOf("search \"dominos\" and open \"Domino's Pizza\""), rec.steps.map { it.describe() })
        assertEquals(StepType.GOAL, rec.steps.single().type)
    }
}
