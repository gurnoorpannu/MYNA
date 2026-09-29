package com.example.myna_mimicyourinteractionsautomate

import com.example.myna_mimicyourinteractionsautomate.recipe.KeyKind
import com.example.myna_mimicyourinteractionsautomate.recipe.Target
import com.example.myna_mimicyourinteractionsautomate.recipe.UniqueKey
import com.example.myna_mimicyourinteractionsautomate.replay.Finder
import com.example.myna_mimicyourinteractionsautomate.replay.Slots
import com.example.myna_mimicyourinteractionsautomate.screen.UiNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** The three finder levels and the pop-up rule (T7), on small fake screens shaped like Zomato's menu. */
class FinderTest {
    private fun screen(vararg kids: UiNode) = UiNode(cls = "FrameLayout", b = 2400, r = 1080, children = kids.toList())
    private fun text(s: String, top: Int = 0) = UiNode(text = s, cls = "TextView", t = top, b = top + 50, r = 500)
    private fun button(s: String, top: Int = 0, id: String? = null) = UiNode(text = s, id = id, cls = "Button", clickable = true, t = top, b = top + 60, r = 400)

    // A menu list: each card has its dish name and its own ADD button.
    private fun card(dish: String, top: Int) = UiNode(cls = "FrameLayout", t = top, b = top + 300, r = 1080, children = listOf(
        text(dish, top + 10), text("₹299", top + 60),
        UiNode(text = "ADD", id = "add_button", cls = "View", clickable = true, t = top + 200, b = top + 250, l = 600, r = 800)))
    private val menu = screen(UiNode(cls = "RecyclerView", scrollable = true, t = 300, b = 2400, r = 1080, children = listOf(
        card("Margherita Pizza", 300), card("Farmhouse Pizza", 700), card("Peppy Paneer Pizza", 1100))))

    @Test fun level1FindsTheRecordedKey() {
        val s = screen(button("View Cart", 2300, id = "cart_bar"), button("Search", 200))
        val f = Finder.find(s, Target(label = "View Cart", key = UniqueKey(KeyKind.LABEL, "View Cart")))!!
        assertEquals(1, f.level)
        assertEquals("View Cart", f.node.label)
    }

    @Test fun level1NearTextTapsAddOnTheRightCard() {
        val t = Target(label = "ADD", id = "add_button", key = UniqueKey(KeyKind.NEAR_TEXT, "Farmhouse Pizza"))
        val f = Finder.find(menu, t)!!
        assertEquals(1, f.level)
        assertEquals(900, f.node.t)   // Farmhouse card's ADD (card top 700 + 200), not Margherita's
    }

    @Test fun looseBlankKeyMatchesTheLongerScreenLabel() {
        // The user said "Farmhouse"; the card says "Farmhouse Pizza".
        val t = Slots.bind(Target(label = "ADD", id = "add_button", key = UniqueKey(KeyKind.NEAR_TEXT, "{item}", loose = true)), mapOf("item" to "Farmhouse"))
        assertEquals(900, Finder.find(menu, t)!!.node.t)
    }

    @Test fun level2FindsTheSameElementWhenTheKeyIsGone() {
        // Recorded key was an id that the app has since renamed; the label is still the same.
        val s = screen(button("Continue", 2300, id = "checkout_v2"))
        val f = Finder.find(s, Target(label = "Continue", id = "checkout_v1", key = UniqueKey(KeyKind.ID, "checkout_v1")))!!
        assertEquals(2, f.level)
        assertEquals("Continue", f.node.label)
    }

    @Test fun level3FuzzyMatchesASmallWordingChange() {
        val s = screen(button("Proceed to Checkout", 2300))
        val f = Finder.find(s, Target(label = "Proceed to checkout.", key = UniqueKey(KeyKind.LABEL, "Proceed to checkout.")))!!
        assertEquals(3, f.level)
        assertEquals("Proceed to Checkout", f.node.label)
    }

    @Test fun noMatchReturnsNothingRatherThanAWrongNode() {
        // Nothing on screen is close to "Garlic Breadsticks": no guess.
        assertNull(Finder.find(menu, Target(label = "Garlic Breadsticks", key = UniqueKey(KeyKind.LABEL, "Garlic Breadsticks"))))
        // An ADD anchored on a dish that isn't on the menu is not any other card's ADD.
        assertNull(Finder.find(menu, Target(label = "ADD", id = "add_button", key = UniqueKey(KeyKind.NEAR_TEXT, "Chicken Dominator"))))
        // Below the fuzzy threshold ("Farmhouse" vs "Farmhouse Pizza" ≈ 0.6) is not a match either.
        assertNull(Finder.find(screen(text("Farmhouse Pizza")), Target(label = "Farmhouse", key = UniqueKey(KeyKind.LABEL, "Farmhouse"))))
    }

    @Test fun invisibleNodesAreNeverFound() {
        val s = screen(UiNode(text = "View Cart", clickable = true, visible = false))
        assertNull(Finder.find(s, Target(label = "View Cart", key = UniqueKey(KeyKind.LABEL, "View Cart"))))
    }

    @Test fun labelInsideAClickableRowTapsTheRow() {
        val row = UiNode(id = "res_card", clickable = true, children = listOf(text("Domino's Pizza")))
        val f = Finder.find(screen(row), Target(label = "Domino's Pizza", key = UniqueKey(KeyKind.LABEL, "Domino's Pizza")))!!
        assertSame(row, f.node)
    }

    // --- T7: pop-ups are only ever closed, never accepted.

    @Test fun t7PopupCloseIsNotNowNeverTheMainAction() {
        val offer = UiNode(cls = "Dialog", t = 600, b = 1400, r = 1080, children = listOf(
            text("Flat ₹100 OFF!", 650), button("Order now", 1200), button("Not now", 1300)))
        assertEquals("Not now", Finder.closeButton(offer)?.label)
    }

    @Test fun t7EveryCloseWordIsAccepted() {
        for (w in listOf("Close", "Dismiss", "Skip", "No thanks", "No, thanks", "Maybe later", "Later", "×", "✕", "X")) {
            val d = UiNode(cls = "Dialog", r = 1080, b = 1000, children = listOf(button("Apply coupon", 800), button(w, 100)))
            assertEquals(w, w, Finder.closeButton(d)?.label)
        }
    }

    @Test fun t7PopupWithOnlyActionButtonsHasNoClose() {
        // "Cancel" is left out on purpose (Amazon checkout); "Allow"/"Order now"/"Apply" are main actions.
        val d = UiNode(cls = "Dialog", r = 1080, b = 1000, children = listOf(
            button("Order now", 700), button("Apply coupon", 800), button("Allow", 850), button("Cancel", 900)))
        assertNull(Finder.closeButton(d))
    }

    @Test fun t7CloseByIdWhenTheButtonHasNoText() {
        val d = UiNode(cls = "Dialog", r = 1080, b = 1000, children = listOf(button("Order now", 700),
            UiNode(id = "iv_close", cls = "ImageView", clickable = true, l = 950, t = 20, r = 1050, b = 120)))
        assertEquals("iv_close", Finder.closeButton(d)?.id)
    }

    @Test fun t7SearchClearCrossIsNotAClose() {
        // Zomato's "iconCross" clears the search text: tapping it would lose the typed query.
        val s = screen(UiNode(id = "search_bar", children = listOf(
            UiNode(text = "dominos", editable = true), UiNode(id = "iconCross", clickable = true))))
        assertNull(Finder.closeButton(s))
    }

    @Test fun similarityIsCaseInsensitiveLevenshtein() {
        assertEquals(1.0, Finder.similarity("Checkout", "checkout"), 0.0)
        assertEquals(0.875, Finder.similarity("checkout", "checkour"), 1e-9)
        assertNotNull(Finder.similarity("", ""))
    }
}
