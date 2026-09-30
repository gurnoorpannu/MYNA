package com.example.myna_mimicyourinteractionsautomate

import com.example.myna_mimicyourinteractionsautomate.compile.Compiler
import com.example.myna_mimicyourinteractionsautomate.recipe.KeyKind
import com.example.myna_mimicyourinteractionsautomate.recipe.Recording
import com.example.myna_mimicyourinteractionsautomate.recipe.Step
import com.example.myna_mimicyourinteractionsautomate.recipe.StepType
import com.example.myna_mimicyourinteractionsautomate.recipe.Target
import com.example.myna_mimicyourinteractionsautomate.recipe.UniqueKey
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 30 Sep device run (Amazon): said "…phone case for my nothing phone 3a…" but typed the shorter "phone case nothing3a".
 * The blank {item} = "nothing phone 3a" wasn't found in one piece, so the WHOLE search became "{item}" and every
 * replay searched "nothing phone 3a" and added a phone instead of a case.
 */
class ShortTypedSearchTest {
    private val said = "add phone case for my nothing phone 3a my Amazon cart"

    private fun searchStepAfterCompile(typed: String, said: String = this.said): String? {
        val rec = Recording(said, "in.amazon.mShop.android.shopping", 1, listOf(
            Step(StepType.LAUNCH, pkg = "in.amazon.mShop.android.shopping"),
            Step(StepType.TAP, target = Target(label = "Search", id = "chrome_search_box", key = UniqueKey(KeyKind.ID, "chrome_search_box"))),
            Step(StepType.TYPE, target = Target(label = "Search or ask a question", id = "rs_search_src_text"), text = typed, submit = true),
            Step(StepType.TAP, target = Target(label = "Add to cart", key = UniqueKey(KeyKind.LABEL, "Add to cart"))),
            Step(StepType.TAP, target = Target(key = UniqueKey(KeyKind.CHILD_TEXT, "Cart")))))
        val blanks = Compiler.findBlanks(rec)
        blanks.forEachIndexed { i, b -> b.name = if (i == 0) "item" else "item$i" }
        return Compiler.applyBlanks(rec.steps, blanks)[2].text
    }

    @Test fun bothSpokenPartsBecomeBlanksWhenOneIsTypedShorter() {
        // The ways it was typed on 30 Sep: "phone case" is a blank of its own (so "laptop stand" can replace it),
        // and the shortened model is the other blank. Neither may swallow the whole search.
        assertEquals("{item} {item1}", searchStepAfterCompile("phone case nothing3a"))
        assertEquals("{item} {item1}", searchStepAfterCompile("phone case nothing 3a"))
        assertEquals("{item} for {item1}", searchStepAfterCompile("phone case for nothing 3a"))
        assertEquals("{item} for {item1}", searchStepAfterCompile("phone case for nothing 3 a"))
        // 10:04 re-teach (compiled offline after an HTTP 503): only the model became a blank, "phone cover" stayed fixed.
        assertEquals("{item} {item1}", searchStepAfterCompile("phone cover nothing 3", said = "add phone cover for my nothing phone 3 to my Amazon card"))
    }

    @Test fun templateReplacesOnlyTheTypedPartOfTheBlank() {
        assertEquals("phone case {item}", Compiler.template("phone case nothing3a", "nothing phone 3a", "{item}"))
        assertEquals("{item} phone cases", Compiler.template("s25ultra phone cases", "S25 Ultra", "{item}"))   // one piece: unchanged behaviour
        assertEquals("{item}", Compiler.template("margherita", "Margherita", "{item}"))
    }
}
