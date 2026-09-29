package com.example.myna_mimicyourinteractionsautomate

import com.example.myna_mimicyourinteractionsautomate.recipe.End
import com.example.myna_mimicyourinteractionsautomate.recipe.Recipe
import com.example.myna_mimicyourinteractionsautomate.recipe.Recipes
import com.example.myna_mimicyourinteractionsautomate.recipe.Recording
import com.example.myna_mimicyourinteractionsautomate.recipe.Slot
import com.example.myna_mimicyourinteractionsautomate.recipe.Step
import com.example.myna_mimicyourinteractionsautomate.recipe.StepType
import com.example.myna_mimicyourinteractionsautomate.recipe.Subtask
import com.example.myna_mimicyourinteractionsautomate.recipe.Target
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The recipe store: plain JSON files in files/recipes/, one per recipe. */
class RecipesStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun recipe(id: String, golden: Boolean = false) = Recipe(id, "com.application.zomato", "Order a Margherita pizza from Domino's on Zomato",
        summary = "order {item} from {restaurant} on Zomato", slots = mapOf("item" to Slot("Margherita"), "restaurant" to Slot("Domino's")),
        subtasks = listOf(Subtask("add_item", "put it in the cart", listOf(Step(StepType.TAP, target = Target(label = "ADD"))))), golden = golden)

    @Test fun saveLoadDeleteRoundTrip() {
        val dir = File(tmp.root, "recipes")
        val store = Recipes(dir)
        assertTrue(dir.isDirectory)                     // created on first use
        store.save(recipe("a", golden = true))
        assertEquals(listOf(recipe("a", golden = true)), Recipes(dir).all())   // a fresh store reads it back identically
        assertTrue(store.delete("a"))
        assertTrue(store.all().isEmpty())
        assertFalse(store.delete("a"))                  // deleting twice is harmless
    }

    @Test fun savingTheSameIdReplacesIt() {
        val store = Recipes(tmp.root)
        store.save(recipe("a"))
        store.save(recipe("a").copy(summary = "order {item} on Zomato"))
        assertEquals(listOf("order {item} on Zomato"), store.all().map { it.summary })
    }

    @Test fun goldenIsTheHomeList() {
        val store = Recipes(tmp.root)
        store.save(recipe("home", golden = true)); store.save(recipe("old"))
        assertEquals(listOf("home"), store.golden().map { it.id })
    }

    @Test fun malformedOrForeignFilesAreSkippedNotFatal() {
        val store = Recipes(tmp.root)
        store.save(recipe("good"))
        File(tmp.root, "truncated.json").writeText("""{"id": "bad", "app": "com.application.zomato", "subtasks": [""")
        File(tmp.root, "wrong-shape.json").writeText("""[1, 2, 3]""")
        File(tmp.root, "missing-fields.json").writeText("""{"id": "x"}""")
        File(tmp.root, "empty.json").writeText("")
        File(tmp.root, "notes.txt").writeText("""{"id": "txt"}""")
        assertEquals(listOf("good"), store.all().map { it.id })
    }

    @Test fun oldestFirst() {
        val store = Recipes(tmp.root)
        store.save(recipe("second")); store.save(recipe("first"))
        File(tmp.root, "first.json").setLastModified(1_000_000)
        File(tmp.root, "second.json").setLastModified(2_000_000)
        assertEquals(listOf("first", "second"), store.all().map { it.id })
    }

    @Test fun fromRecordingDropsMistakesAndEndsAtPaymentWhenTheGateStopped() {
        val rec = Recording("order pizza", "com.application.zomato", 42, listOf(
            Step(StepType.LAUNCH, pkg = "com.application.zomato"),
            Step(StepType.TAP, target = Target(label = "Offers"), noise = true),
            Step(StepType.TAP, target = Target(label = "ADD"))), stoppedBy = "safety_gate")
        val r = Recipes.fromRecording(rec)
        assertEquals("zomato_42", r.id)
        assertEquals(2, r.subtasks.single().steps.size)
        assertEquals(End.PAYMENT_SCREEN, r.end)
        assertEquals(End.LAST_SCREEN, Recipes.fromRecording(rec.copy(stoppedBy = "user")).end)
    }
}
