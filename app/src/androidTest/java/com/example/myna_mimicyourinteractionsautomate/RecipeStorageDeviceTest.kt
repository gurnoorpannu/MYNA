package com.example.myna_mimicyourinteractionsautomate

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myna_mimicyourinteractionsautomate.recipe.Recipe
import com.example.myna_mimicyourinteractionsautomate.recipe.Recipes
import com.example.myna_mimicyourinteractionsautomate.recipe.Slot
import com.example.myna_mimicyourinteractionsautomate.recipe.Step
import com.example.myna_mimicyourinteractionsautomate.recipe.StepType
import com.example.myna_mimicyourinteractionsautomate.recipe.Subtask
import com.example.myna_mimicyourinteractionsautomate.recipe.Target
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Recipes round-trip through the same folder the app uses on the phone (files/recipes on external app storage). */
@RunWith(AndroidJUnit4::class)
class RecipeStorageDeviceTest {
    private val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "recipes")
    private val id = "androidtest_${System.nanoTime()}"

    @After fun cleanUp() { File(dir, "$id.json").delete() }

    @Test fun saveLoadDeleteOnDeviceStorage() {
        val r = Recipe(id, "com.application.zomato", "Order a Margherita pizza from Domino's on Zomato",
            summary = "order {item} from {restaurant} on Zomato", golden = true,
            slots = mapOf("item" to Slot("Margherita", neighbours = listOf("Farmhouse")), "restaurant" to Slot("Domino's")),
            subtasks = listOf(Subtask("add_item", steps = listOf(Step(StepType.TAP, target = Target(label = "ADD"))))))
        Recipes(dir).save(r)
        assertTrue(File(dir, "$id.json").isFile)
        assertEquals(r, Recipes(dir).all().single { it.id == id })   // a fresh store, as after an app restart
        assertTrue(Recipes(dir).golden().any { it.id == id })
        assertTrue(Recipes(dir).delete(id))
        assertFalse(Recipes(dir).all().any { it.id == id })
    }
}
