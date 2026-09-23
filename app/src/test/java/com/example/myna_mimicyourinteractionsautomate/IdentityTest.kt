package com.example.myna_mimicyourinteractionsautomate

import com.example.myna_mimicyourinteractionsautomate.recipe.KeyKind
import com.example.myna_mimicyourinteractionsautomate.screen.Identity
import com.example.myna_mimicyourinteractionsautomate.screen.UiNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A fake Zomato menu: title bar, search box, and a list of pizza cards each with its own "Add". */
object FakeScreens {
    fun card(name: String, price: String, y: Int, add: String = "Add") = UiNode(
        cls = "ViewGroup", l = 0, t = y, r = 1080, b = y + 300, children = listOf(
            UiNode(text = name, cls = "TextView", l = 40, t = y + 20, r = 600, b = y + 80),
            UiNode(text = price, cls = "TextView", l = 40, t = y + 90, r = 300, b = y + 140),
            UiNode(text = "Veg", cls = "TextView", l = 40, t = y + 150, r = 300, b = y + 200),
            UiNode(text = add, id = "add_button", cls = "Button", clickable = true, l = 800, t = y + 200, r = 1040, b = y + 280),
        ))

    fun menu(vararg titleTexts: String = arrayOf("Domino's Pizza", "Menu"), add: String = "Add") = UiNode(
        cls = "FrameLayout", l = 0, t = 0, r = 1080, b = 2400, children = listOf(
            UiNode(cls = "LinearLayout", l = 0, t = 0, r = 1080, b = 200,
                children = titleTexts.map { UiNode(text = it, cls = "TextView", l = 0, t = 50, r = 500, b = 150) }),
            UiNode(hint = "Search within menu", text = "", id = "search", cls = "EditText", editable = true, clickable = true,
                l = 0, t = 220, r = 1080, b = 320),
            UiNode(cls = "RecyclerView", scrollable = true, l = 0, t = 400, r = 1080, b = 2400, children = listOf(
                card("Margherita", "₹299", 400, add), card("Farmhouse", "₹459", 700, add), card("Peppy Paneer", "₹459", 1000, add),
            )),
        ))

    fun find(root: UiNode, pred: (UiNode) -> Boolean) = root.walk().first(pred)
}

class IdentityTest {
    private val menu = FakeScreens.menu()

    @Test fun addButtonAnchorsOnItsCardAndKnowsNeighbours() {
        val margheritaCard = FakeScreens.find(menu) { it.walk().any { n -> n.text == "Margherita" } && it.cls == "ViewGroup" }
        val add = margheritaCard.children.last()
        val t = Identity.target(add, menu)
        assertEquals("Add", t.label)
        assertEquals(KeyKind.NEAR_TEXT, t.key!!.by)
        assertEquals("Margherita", t.key!!.value)
        assertEquals(listOf("Farmhouse", "Peppy Paneer"), t.neighbours)
    }

    @Test fun tappingTheCardItselfUsesChildText() {
        val card = menu.walk().first { it.cls == "ViewGroup" }
        val t = Identity.target(card, menu)
        assertEquals(KeyKind.CHILD_TEXT, t.key!!.by)
        assertEquals("Margherita", t.key!!.value)
        assertEquals(listOf("Margherita", "₹299", "Veg", "Add"), t.subTexts)
    }

    @Test fun editFieldIsNamedByHintNotTypedText() {
        val search = FakeScreens.find(menu) { it.editable }
        val t = Identity.target(search, menu)
        assertEquals("Search within menu", t.label)
        assertEquals(KeyKind.ID, t.key!!.by)
    }

    @Test fun screenSignatureSkipsPricesAndDetectsHindi() {
        val s = Identity.screen(menu, "com.application.zomato", "com.zomato.MenuActivity")
        assertEquals("com.application.zomato|MenuActivity|Domino's Pizza|Menu", s.signature)
        assertEquals("en", s.lang)
        // Zomato in Hindi: chrome and buttons translated, dish names still English.
        assertEquals("hi", Identity.lang(FakeScreens.menu("डोमिनोज़ पिज़्ज़ा", "मेन्यू", add = "जोड़ें")))
    }

    @Test fun compactListsTappableThings() {
        val c = Identity.compact(menu)
        assertTrue(c.contains("Button \"Add\" #add_button [tap]"))
        assertTrue(c.contains("EditText hint=\"Search within menu\" #search [tap] [edit]"))
    }
}
