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

/** Shapes copied from the real 23 Sep Zomato/Amazon dumps. */
class RealDumpQuirksTest {
    // Zomato dish card: section tag, title twice (image desc + text), icon-font "+" as the tappable node.
    private fun zomatoCard(section: String, dish: String, price: String, y: Int) = UiNode(cls = "FrameLayout", t = y, b = y + 400, children = listOf(
        UiNode(text = "In $section", cls = "TextView", t = y),
        UiNode(desc = dish, cls = "ImageView", t = y + 10),
        UiNode(text = dish, cls = "TextView", t = y + 20),
        UiNode(text = price, cls = "TextView", t = y + 30),
        UiNode(id = "ll_root", cls = "LinearLayout", t = y + 300, children = listOf(
            UiNode(text = "ADD", cls = "TextView", t = y + 300),
            UiNode(text = "", id = "button_add", cls = "View", clickable = true, t = y + 300),
        )),
    ))

    private val menu = UiNode(cls = "FrameLayout", b = 2400, children = listOf(
        UiNode(cls = "RecyclerView", scrollable = true, t = 400, b = 2400, children = listOf(
            zomatoCard("Recommended for you", "Margherita Pizza", "₹109", 400),
            zomatoCard("2in1 Cheese Burst", "Double Cheeseburst - Margherita (Reg)", "₹299", 800),
            zomatoCard("Value Meals", "Farmhouse", "₹459", 1200),
        )),
    ))

    @Test fun zomatoAddAnchorsOnSpokenDishDespiteRepeatsAndGlyphs() {
        val add = menu.walk().filter { it.id == "button_add" }.first()
        assertEquals(null, add.label)                                   // "" is not a label
        val t = Identity.target(add, menu, setOf("order", "margherita", "pizza", "domino"))
        assertEquals(KeyKind.NEAR_TEXT, t.key!!.by)
        assertEquals("Margherita Pizza", t.key!!.value)
        assertTrue("Farmhouse" in t.neighbours)
    }

    @Test fun rotatingSearchHintAndWebviewIdsDontBecomeKeys() {
        assertEquals("Search", Identity.norm("Search \"homestyle meals\""))
        assertEquals("Domino's Pizza", Identity.norm("Domino's Pizza"))
        val root = UiNode(children = listOf(
            UiNode(text = "", id = "pp-mDxkUF-246", cls = "View", clickable = true, children = listOf(UiNode(text = "Pay by any UPI App"))),
        ))
        val t = Identity.target(root.children[0], root)
        assertEquals(KeyKind.CHILD_TEXT, t.key!!.by)
    }
}

class InferTapTest {
    // Zomato search suggestions: tapping a row opens the restaurant but sends no click event.
    private fun row(name: String, sub: String) = UiNode(cls = "ViewGroup", clickable = true, children = listOf(
        UiNode(text = name, cls = "TextView"), UiNode(text = sub, cls = "TextView")))
    private val suggestions = UiNode(cls = "FrameLayout", children = listOf(
        UiNode(text = "Back", cls = "ImageButton", clickable = true),
        UiNode(cls = "RecyclerView", scrollable = true, children = listOf(
            row("Domino's Pizza", "Restaurant · 7.6 km"), row("Dominos pizza near me", "Search"), row("Shawarmajaan", "Restaurant")))))
    private val menu = UiNode(cls = "FrameLayout", children = listOf(
        UiNode(text = "Back", cls = "ImageButton", clickable = true),
        UiNode(text = "Domino's Pizza", cls = "TextView", t = 200, b = 260), UiNode(text = "Margherita Pizza", cls = "TextView", t = 900, b = 960)))

    @Test fun picksTheRowWhoseTitleIsOnTheNextScreen() {
        val tapped = Identity.inferTap(suggestions, menu, "dominos")
        assertEquals("Domino's Pizza", tapped?.let(Identity::primaryText))
    }

    @Test fun noGuessWhenNothingMatches() {
        assertEquals(null, Identity.inferTap(suggestions, UiNode(children = listOf(UiNode(text = "Back", clickable = true))), "dominos"))
    }
}

class PickedResultTest {
    // Zomato menu page after picking "Domino's Pizza" from a Compose (text-less) suggestion list.
    private val menu = UiNode(cls = "ScrollView", children = listOf(
        UiNode(desc = "Domino's Pizza", id = "title", cls = "View", t = 200),
        UiNode(desc = "7.6 km · Ranjit Avenue", cls = "View", t = 260),
        UiNode(text = "Search in Domino's Pizza", hint = "Search", cls = "EditText", editable = true, t = 900),
        UiNode(desc = "Farmhouse Pizza + Classic Stuffed Garlic Bread", cls = "View", t = 1400)))

    @Test fun namesTheResultFromTheNextScreen() {
        assertEquals("Domino's Pizza", Identity.pickedResult(menu, "Domino's"))
        assertEquals("Domino's Pizza", Identity.pickedResult(menu, "dominos"))
        assertEquals(null, Identity.pickedResult(menu, "Burger King"))
    }
}

class PlaceholderTest {
    @Test fun zomatoPlaceholderTwoLevelsUpIsIgnored() {
        val field = UiNode(text = "Type to search restaurants or dishes", hint = "Restaurant name or a dish...", id = "edittext", editable = true)
        UiNode(desc = "Type to search restaurants or dishes", id = "search_edit_text", children = listOf(
            UiNode(text = "Type to search restaurants or dishes", id = "leftIcon"),
            UiNode(id = "vsearch_parent", children = listOf(field))))
        assertTrue(Identity.isPlaceholder(field, "Type to search restaurants or dishes"))
        assertEquals(false, Identity.isPlaceholder(field, "Domino's"))
    }
}

class PersistentChromeTest {
    @Test fun cartBarOnBothScreensIsNotGuessed() {
        fun cartBar() = UiNode(cls = "RecyclerView", scrollable = true, children = listOf(
            UiNode(cls = "ViewGroup", clickable = true, children = listOf(UiNode(text = "Domino's Pizza", t = 2000, b = 2050), UiNode(text = "View Menu", t = 2060, b = 2100)))))
        val home = UiNode(children = listOf(cartBar(), UiNode(text = "Search", clickable = true)))
        val suggestions = UiNode(children = listOf(cartBar(), UiNode(text = "Type to search", editable = true)))
        assertEquals("Search", Identity.inferTap(home, suggestions)?.label)          // the search bar, not the cart bar
        assertEquals(null, Identity.inferTap(home, UiNode(children = listOf(cartBar())), "dominos"))
    }
}

class SearchedCardTest {
    // After searching "margherita" only one card shows, so button_add is unique — anchor must still win.
    @Test fun spokenCardBeatsMomentarilyUniqueId() {
        val card = UiNode(cls = "FrameLayout", children = listOf(
            UiNode(text = "Margherita Pizza", t = 10), UiNode(text = "₹109", t = 20),
            UiNode(text = "", id = "button_add", clickable = true, t = 30)))
        val root = UiNode(children = listOf(UiNode(cls = "RecyclerView", scrollable = true, children = listOf(card))))
        val t = Identity.target(card.children[2], root, setOf("margherita", "pizza"))
        assertEquals(KeyKind.NEAR_TEXT, t.key!!.by)
        assertEquals("Margherita Pizza", t.key!!.value)
    }

    @Test fun continueBeatsItemCount() {
        val btn = UiNode(id = "container", clickable = true, children = listOf(UiNode(text = "2 items added"), UiNode(text = "Continue")))
        val t = Identity.target(btn, UiNode(children = listOf(btn, UiNode(id = "container"))))
        assertEquals("Continue", t.key!!.value)
    }
}

/** Zomato's restaurant page is ONE big ScrollView: its children are sections, not cards. */
class WholePageScrollTest {
    private fun card(dish: String, top: Int) = UiNode(cls = "FrameLayout", t = top, b = top + 300, children = listOf(
        UiNode(text = dish, t = top + 10, b = top + 60), UiNode(text = "", id = "button_add", clickable = true, t = top + 200, b = top + 250)))
    private val search = UiNode(text = "Search in Domino's Pizza", id = "edittext", editable = true, clickable = true, t = 300, b = 400)
    private val searchBtn = UiNode(id = "button1", clickable = true, t = 200, b = 280, children = listOf(UiNode(text = "Search", t = 210, b = 270)))
    private val page = UiNode(cls = "FrameLayout", t = 0, b = 2400, children = listOf(
        UiNode(cls = "ScrollView", scrollable = true, t = 0, b = 2400, children = listOf(
            UiNode(desc = "Domino's Pizza", id = "title", t = 100, b = 160), searchBtn, search,
            UiNode(cls = "LinearLayout", t = 500, b = 2400, children = listOf(
                UiNode(cls = "RecyclerView", scrollable = true, t = 500, b = 2400, children = listOf(
                    card("Margherita Pizza", 500), card("Farmhouse Pizza", 900)))))))))
    private val spoken = setOf("order", "margherita", "pizza", "domino")

    @Test fun pageWidgetsKeepTheirOwnKeys() {
        assertEquals(KeyKind.ID, Identity.target(search, page, spoken).key!!.by)
        assertEquals(KeyKind.ID, Identity.target(searchBtn, page, spoken).key!!.by)   // not near "Domino's Pizza"
    }

    @Test fun addStillAnchorsOnItsSmallCard() {
        val add = page.walk().first { it.id == "button_add" }
        val k = Identity.target(add, page, spoken).key!!
        assertEquals(KeyKind.NEAR_TEXT, k.by); assertEquals("Margherita Pizza", k.value)
    }

    @Test fun clearTextCrossIsNotAPopupClose() {
        val bar = UiNode(children = listOf(UiNode(desc = "Double tap to clear text", id = "iconCross", clickable = true)))
        assertEquals(null, com.example.myna_mimicyourinteractionsautomate.replay.Finder.closeButton(bar))
    }
}


class HomeToSearchTest {
    // 23 Sep bug: home → search had no click event and the recorder guessed the "North Indian" chip.
    private val home = UiNode(cls = "FrameLayout", b = 2400, children = listOf(
        UiNode(id = "search_edit_text", desc = "Double tap to open search page", clickable = true, t = 300, b = 400),
        UiNode(cls = "RecyclerView", scrollable = true, t = 500, b = 700, children = listOf(
            UiNode(clickable = true, t = 500, b = 600, children = listOf(UiNode(text = "North Indian", t = 510, b = 560))),
            UiNode(clickable = true, t = 600, b = 700, children = listOf(UiNode(text = "Pizza", t = 610, b = 660)))))))
    private val search = UiNode(cls = "FrameLayout", b = 2400, children = listOf(
        UiNode(id = "edittext", editable = true, t = 100, b = 200),
        UiNode(text = "WHAT'S ON YOUR MIND?", t = 900, b = 950), UiNode(text = "North Indian", t = 1000, b = 1050)))

    @Test fun textFieldOnNextScreenMeansSearchBarWasTapped() {
        assertEquals("search_edit_text", Identity.inferTap(home, search)?.id)
    }

    @Test fun noRowGuessWithoutTyping() {
        val noField = UiNode(children = listOf(UiNode(text = "North Indian", t = 1000, b = 1050)))
        assertEquals(null, Identity.inferTap(home, noField))
    }
}


class AddLabelTest {
    // 23 Sep: user tapped the "ADD" text itself; it was unique after searching, and the item link got lost.
    @Test fun addTextOnNamedCardStillAnchorsOnTheDish() {
        val card = UiNode(cls = "FrameLayout", t = 500, b = 800, children = listOf(
            UiNode(text = "Margherita Pizza", t = 510, b = 560), UiNode(text = "ADD", id = "text_view_title", clickable = true, t = 700, b = 750)))
        val root = UiNode(b = 2400, children = listOf(UiNode(cls = "RecyclerView", scrollable = true, t = 500, b = 2400, children = listOf(card))))
        val k = Identity.target(card.children[1], root, setOf("margherita", "pizza")).key!!
        assertEquals(KeyKind.NEAR_TEXT, k.by); assertEquals("Margherita Pizza", k.value)
    }

    @Test fun resultsPageShowsQuery() {
        assertTrue(Identity.showsQuery(UiNode(children = listOf(UiNode(text = "dominos"))), "dominos"))
        assertEquals(false, Identity.showsQuery(UiNode(children = listOf(UiNode(text = "Search in Domino's Pizza"))), "dominos"))
    }
}
