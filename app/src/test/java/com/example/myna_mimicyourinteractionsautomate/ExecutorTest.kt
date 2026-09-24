package com.example.myna_mimicyourinteractionsautomate

import com.example.myna_mimicyourinteractionsautomate.recipe.End
import com.example.myna_mimicyourinteractionsautomate.recipe.KeyKind
import com.example.myna_mimicyourinteractionsautomate.recipe.Recipe
import com.example.myna_mimicyourinteractionsautomate.recipe.Screen
import com.example.myna_mimicyourinteractionsautomate.recipe.Step
import com.example.myna_mimicyourinteractionsautomate.recipe.StepType
import com.example.myna_mimicyourinteractionsautomate.recipe.Subtask
import com.example.myna_mimicyourinteractionsautomate.recipe.SystemKey
import com.example.myna_mimicyourinteractionsautomate.recipe.Target
import com.example.myna_mimicyourinteractionsautomate.recipe.UniqueKey
import com.example.myna_mimicyourinteractionsautomate.replay.Device
import com.example.myna_mimicyourinteractionsautomate.replay.Executor
import com.example.myna_mimicyourinteractionsautomate.replay.OcrLine
import com.example.myna_mimicyourinteractionsautomate.replay.Outcome
import com.example.myna_mimicyourinteractionsautomate.safety.GatedActor
import com.example.myna_mimicyourinteractionsautomate.screen.UiNode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A fake Zomato shaped like the real one: search bar on home, a Compose suggestion list with NO text
 * (only OCR sees "Domino's Pizza"), menu cards with icon-font Add buttons, and a cart with "Place Order".
 */
class FakeZomato(var popup: Boolean = false, var hindi: Boolean = false) : Device {
    var state = "home"
    var sheetFor: String? = null      // customisation sheet open for this dish
    var crust: String? = null         // required group: nothing preselected (as on the 23 Sep replay)
    var typed = ""
    val cart = mutableListOf<String>()
    val taps = mutableListOf<String>()
    val blocks = mutableListOf<String>()
    private var clock = 0L

    private fun t(s: String, top: Int = 0) = UiNode(text = s, cls = "TextView", t = top, b = top + 50, r = 500)
    private fun card(dish: String, top: Int) = UiNode(cls = "FrameLayout", t = top, b = top + 300, children = listOf(
        t("In Recommended", top), UiNode(desc = dish, t = top + 5), t(dish, top + 10), t("₹299", top + 60),
        UiNode(text = "", id = "button_add", cls = "View", clickable = true, t = top + 200, b = top + 250, l = 800, r = 1000),
        UiNode(text = "ADD", id = "text_view_title", cls = "View", clickable = true, t = top + 200, b = top + 250, l = 600, r = 800)))

    private fun build(): UiNode = when (state) {
        "home" -> if (hindi) UiNode(cls = "FrameLayout", b = 2400, children = listOf(
            UiNode(id = "search_edit_text", desc = "खोज पेज खोलने के लिए डबल टैप करें", clickable = true, t = 300, b = 400, children = listOf(t("\"बिरयानी\" खोजें", 310))),
            t("डिलीवरी", 100), t("डाइनिंग", 100)))
        else UiNode(cls = "FrameLayout", b = 2400, children = listOf(
            UiNode(id = "search_edit_text", desc = "Double tap to open search page", clickable = true, t = 300, b = 400, children = listOf(t("Search \"biryani\"", 310))),
            t("Delivery", 100)))
        "search" -> UiNode(cls = "FrameLayout", b = 2400, children = listOf(
            UiNode(id = "edittext", hint = "Restaurant name or a dish...", text = "Type to search restaurants or dishes", editable = true, clickable = true, t = 100, b = 200),
            UiNode(cls = "ComposeView", scrollable = true, t = 300, b = 2400)))      // text-less suggestions
        // Options sheet after Add: the "Add item ₹109" button is a blank box (only OCR can read it).
        "sheet" -> UiNode(cls = "FrameLayout", b = 2400, children = listOf(
            UiNode(id = "touch_outside", clickable = true, b = 2400, r = 1080),
            t("Crust", 700), t("Required • Select any 1 option", 760), t(sheetFor!!, 450),
            UiNode(cls = "RecyclerView", scrollable = true, t = 850, b = 1900, r = 1080, children = listOf("New Hand Tossed", "Cheese Burst").mapIndexed { i, c ->
                UiNode(cls = "ViewGroup", clickable = true, t = 900 + i * 160, b = 990 + i * 160, r = 1047, children = listOf(
                    t(c, 910 + i * 160), UiNode(cls = "RadioButton", clickable = true, checkable = true, checked = crust == c, l = 940, t = 900 + i * 160, r = 1047, b = 990 + i * 160)))
            }),
            UiNode(id = "button_container", t = 1990, b = 2200, r = 1080, children = listOf(
                UiNode(id = "ll_root", t = 2030, b = 2165, r = 326, children = listOf(
                    UiNode(text = "\ue890", id = "button_remove", clickable = true, t = 2030, b = 2165, r = 131),
                    UiNode(text = "$sheetQty", id = "text_view_title", t = 2030, b = 2165, l = 131, r = 228),
                    UiNode(text = "\ue922", id = "button_add", clickable = true, t = 2030, b = 2165, l = 228, r = 326))),
                UiNode(cls = "ViewGroup", l = 360, t = 2025, r = 1046, b = 2171)))))
        // Results page (after Enter or a suggestion tap): accessible rows, the restaurant twice.
        "results" -> UiNode(cls = "FrameLayout", b = 2400, children = listOf(
            UiNode(id = "edittext", text = "Type to search restaurants or dishes", editable = true, t = 100, b = 200),
            UiNode(cls = "RecyclerView", scrollable = true, t = 250, b = 2400, children = listOf(
                UiNode(id = "top_row", clickable = true, t = 250, b = 350, children = listOf(t("Domino's Pizza", 260))),
                UiNode(id = "pizza_hut", clickable = true, t = 400, b = 500, children = listOf(t("Pizza Hut", 410))),
                UiNode(id = "res_card", clickable = true, t = 900, b = 1200, children = listOf(t("Domino's Pizza", 910), t("35–40 mins", 960)))))))
        // A real dialog is its own window: while it's up, the active window shows only the dialog.
        "menu" -> if (popup) UiNode(cls = "Dialog", t = 600, b = 1400, children = listOf(
                t("Flat ₹100 OFF!", 650), UiNode(text = "Order now", cls = "Button", clickable = true, t = 1200, b = 1260),
                UiNode(text = "Not now", cls = "Button", clickable = true, t = 1300, b = 1360)))
        else UiNode(cls = "FrameLayout", b = 2400, children = listOfNotNull(
            UiNode(desc = "Domino's Pizza", id = "title", t = 100, b = 150),
            if (closed) t("Currently closed · Opens at 11 AM", 170) else null,
            UiNode(id = "button1", clickable = true, t = 200, b = 260, children = listOf(t("Search", 210))),
            if (menuSearch != null) UiNode(id = "edittext", text = "Search in Domino's Pizza", editable = true, clickable = true, t = 300, b = 380) else null,
            UiNode(cls = "RecyclerView", scrollable = true, t = 400, b = 2400, children = listOf(
                card("Margherita Pizza", 400), card("Farmhouse Pizza", 800), card("Peppy Paneer Pizza", 1200))
                .filter { c -> menuSearch.isNullOrEmpty() || c.children[2].text!!.lowercase().contains(menuSearch!!.lowercase()) }),
            if (cart.isNotEmpty()) UiNode(id = "container", clickable = true, t = 2300, b = 2400, children = listOf(t("${cart.size} item added", 2310), t("Continue", 2310))) else null))
        // 23 Sep: a "coupon not applied" pop-up covered the cart right after Continue.
        "coupon" -> UiNode(cls = "FrameLayout", b = 2400, children = listOf(t("Coupon not applied", 900), t("Save ₹50 with HUNGRY50", 960),
            UiNode(text = "Apply coupon", cls = "Button", clickable = true, t = 1100, b = 1160),
            UiNode(id = "coupon_close", desc = "Close", clickable = true, t = 800, b = 860)))
        // The cart: "Delivering to Home" opens a picker sheet with the saved addresses.
        "cart" -> if (addressPicker) UiNode(cls = "FrameLayout", b = 2400, children = listOf(UiNode(id = "touch_outside", clickable = true, b = 2400, r = 1080),
            t("Select an address", 1400),
            UiNode(clickable = true, t = 1500, b = 1600, children = listOf(t("Home", 1510), t("50 Harkishan Garden", 1550))),
            UiNode(clickable = true, t = 1650, b = 1750, children = listOf(t("Work", 1660), t("Ranjit Avenue", 1700))),
            UiNode(id = "cv_checkout_container", clickable = true, t = 2200, b = 2400, children = listOf(t("Place Order", 2260)))))
        else UiNode(cls = "FrameLayout", b = 2400, children = listOf(t("Domino's Pizza", 100), t(cart.joinToString(), 300),
            UiNode(id = "address_chip", clickable = true, t = 2000, b = 2080, children = listOf(t("Delivering to $address", 2010))),
            UiNode(id = "cv_checkout_container", clickable = true, t = 2200, b = 2400, children = listOf(t("₹299", 2210), t("Place Order", 2260)))))
        else -> error(state)
    }

    override suspend fun screen() = build() to "com.application.zomato"
    override val actor = GatedActor(click = ::click, setText = { n, s, _ -> if (state == "menu") menuSearch = s else typed = s; n.id == "edittext" }, onBlocked = { blocks += it.reason })
    override suspend fun launchClean(pkg: String) = true.also { state = "home" }
    override fun key(key: SystemKey) {}
    override fun scroll(list: UiNode, forward: Boolean) = false
    override suspend fun ocr() = when {
        state == "search" && typed.isNotEmpty() -> listOf(OcrLine("Domino's Pizza", 40, 320, 600, 380), OcrLine("Dominos pizza near me", 40, 420, 600, 480))
        state == "sheet" -> listOf(OcrLine("Crust", 60, 700, 300, 760), OcrLine("Add item ₹109", 420, 2070, 980, 2130))
        else -> emptyList()
    }
    override suspend fun ask(question: String, options: List<String>): String? = null
    override fun say(text: String) {}
    override fun now() = clock.also { clock += 100 }
    override suspend fun pause(ms: Long) { clock += ms; if (userTapsSheet && prompts.isNotEmpty() && state == "sheet" && crust != null) { cart += sheetFor!!; state = "menu" } }
    var couponPopup = false
    var menuSearch: String? = null    // in-menu search box open (null = closed), with its text
    var sheetQty = 1                  // the sheet's "− 1 +" stepper
    var address = "Home"; var addressPicker = false
    var closed = false
    var ignoresA11yTaps = false       // Zomato's real "Add item": only a finger works
    var userTapsSheet = false
    val prompts = mutableListOf<String>()
    override fun prompt(text: String?) { text?.let { prompts += it } }
    override val stopRequested = false

    private fun click(n: UiNode): Boolean {
        taps += n.label ?: n.id ?: n.cls ?: "?"
        when {
            n.label == "Not now" -> popup = false
            n.label == "Order now" || n.label == "Apply coupon" -> error("tapped the pop-up's main action!")
            n.id == "search_edit_text" -> state = "search"
            n.id == "button1" -> menuSearch = ""
            n.cls == "OcrText" && n.text == "Domino's Pizza" -> state = "results"
            n.id == "top_row" || n.id == "res_card" -> state = "menu"
            n.id == "pizza_hut" -> error("opened the wrong restaurant!")
            state == "sheet" && n.id == "button_add" -> sheetQty++
            state == "sheet" && n.id == "button_remove" -> sheetQty = maxOf(1, sheetQty - 1)
            n.id == "button_add" || n.id == "text_view_title" -> { sheetFor = n.parent!!.children[2].text!!; sheetQty = 1; state = "sheet" }
            n.id == "address_chip" -> addressPicker = true
            addressPicker && n.children.firstOrNull()?.text in setOf("Home", "Work") -> { address = n.children[0].text!!; addressPicker = false }
            state == "sheet" && n.cls == "ViewGroup" && n.children.firstOrNull()?.text != null && n.t in 900..1100 -> crust = n.children[0].text
            // Add item only works once the required crust is chosen.
            (n.cls == "OcrText" && n.text!!.startsWith("Add item")) || (state == "sheet" && n.l == 360 && n.t == 2025) ->
                if (crust != null && !ignoresA11yTaps) { repeat(sheetQty) { cart += sheetFor!! }; state = "menu" }
            n.id == "container" -> state = if (couponPopup) "coupon" else "cart"
            n.id == "coupon_close" -> state = "cart"
            n.id == "cv_checkout_container" -> error("tapped Place Order!")
        }
        return true
    }
}

class ExecutorTest {
    private val app = "com.application.zomato"
    private fun step(type: StepType, target: Target? = null, text: String? = null, lang: String = "en") =
        Step(type, pkg = if (type == StepType.LAUNCH) app else null, target = target, text = text, screen = Screen("s", lang = lang))

    // Shaped like the 23 Sep recording (after the recorder fixes).
    private val recipe = Recipe("zomato_golden", app, "Order a Margherita pizza from Domino's", end = End.PAYMENT_SCREEN,
        subtasks = listOf(Subtask("demo", steps = listOf(
            step(StepType.LAUNCH),
            Step(StepType.GOAL, goal = "search", text = "{restaurant}", args = mapOf("pick" to "{restaurant_name}"), screen = Screen("s", lang = "en"),
                target = Target(label = "Restaurant name or a dish...", id = "edittext", key = UniqueKey(KeyKind.ID, "edittext"))),
            step(StepType.TAP, Target(id = "button_add", cls = "View", key = UniqueKey(KeyKind.NEAR_TEXT, "{item}"))),
            Step(StepType.GOAL, goal = "confirm_sheet", args = mapOf("choices" to "New Hand Tossed | Regular")),
            step(StepType.TAP, Target(id = "container", key = UniqueKey(KeyKind.CHILD_TEXT, "Continue"))),
        ))))
    private val demo = mapOf("restaurant" to "Domino's", "restaurant_name" to "Domino's Pizza", "item" to "Margherita Pizza")

    @Test fun exactReplayReachesCartAndHandsOffWithZeroTapsThere() = runBlocking {
        val z = FakeZomato()
        val log = Executor(z).run(recipe, demo)
        assertEquals(log.reason, Outcome.HANDED_OFF, log.outcome)
        assertEquals(listOf("Margherita Pizza"), z.cart)
        assertEquals("Domino's", z.typed)
        assertTrue(log.steps.any { it.note?.contains("opened \"Domino's Pizza\"") == true })   // OCR hop + results hop
        assertTrue(z.blocks.single().contains("Place Order"))
        assertFalse(z.taps.any { it.contains("checkout") })
    }

    @Test fun untaughtViewCartTapIsFoundAtTheEnd() = runBlocking {
        // The demo's View Cart tap sent no event, so the recipe stops after Add.
        val z = FakeZomato()
        val short = recipe.copy(subtasks = listOf(Subtask("demo", steps = recipe.subtasks[0].steps.dropLast(1))))
        val log = Executor(z).run(short, demo)
        assertEquals(log.reason, Outcome.HANDED_OFF, log.outcome)
        assertTrue(log.steps.last().note == "towards checkout")
    }

    @Test fun sheetThatIgnoresOurTapsAsksTheUserThenContinues() = runBlocking {
        val z = FakeZomato().apply { ignoresA11yTaps = true; userTapsSheet = true }
        val log = Executor(z).run(recipe, demo)
        assertEquals(log.reason, Outcome.HANDED_OFF, log.outcome)
        assertEquals(listOf("Margherita Pizza"), z.cart)
        assertTrue(z.prompts.single().contains("tap"))
    }

    @Test fun couponPopupOverCartIsClosedThenHandsOff() = runBlocking {
        val z = FakeZomato().apply { couponPopup = true }
        val log = Executor(z).run(recipe, demo)
        assertEquals(log.reason, Outcome.HANDED_OFF, log.outcome)
        assertTrue(log.steps.any { it.what.startsWith("close pop-up") })
    }

    @Test fun t5QuantityIsSetOnTheSheetStepper() = runBlocking {
        val z = FakeZomato()
        val log = Executor(z).run(recipe, demo + ("qty" to "2"))
        assertEquals(log.reason, Outcome.HANDED_OFF, log.outcome)
        assertEquals(listOf("Margherita Pizza", "Margherita Pizza"), z.cart)
    }

    @Test fun t6AddressIsSwitchedOnTheCartButPlaceOrderIsNeverTapped() = runBlocking {
        val z = FakeZomato()
        val log = Executor(z).run(recipe, demo + ("address" to "Work"))
        assertEquals(log.reason, Outcome.HANDED_OFF, log.outcome)
        assertEquals("Work", z.address)
        assertFalse(z.taps.any { it.contains("checkout") })
    }

    @Test fun t10ClosedRestaurantStopsWithTheReason() = runBlocking {
        val z = FakeZomato().apply { closed = true }
        val log = Executor(z).run(recipe, demo)
        assertEquals(Outcome.STUCK, log.outcome)
        assertTrue(log.reason, log.reason!!.contains("Opens at 11 AM"))
        assertTrue(z.cart.isEmpty())
    }

    @Test fun changedItemAddsFarmhouse() = runBlocking {
        val z = FakeZomato()
        Executor(z).run(recipe, demo + ("item" to "Farmhouse Pizza"))
        assertEquals(listOf("Farmhouse Pizza"), z.cart)
    }

    @Test fun popupIsClosedWithNotNowNeverOrderNow() = runBlocking {
        val z = FakeZomato(popup = true)
        val log = Executor(z).run(recipe, demo)
        assertEquals(log.reason, Outcome.HANDED_OFF, log.outcome)
        assertTrue("Not now" in z.taps)
    }

    @Test fun hindiScreenIsStuckWithReasonAndNoTaps() = runBlocking {
        val z = FakeZomato(hindi = true)
        val log = Executor(z).run(recipe, demo)
        assertEquals(Outcome.STUCK, log.outcome)
        assertTrue(log.reason!!.contains("Hindi"))
        assertTrue(z.taps.isEmpty())
    }

    @Test fun missingItemIsStuckNotRandomTaps() = runBlocking {
        val z = FakeZomato()
        val log = Executor(z).run(recipe, demo + ("item" to "Paneer Tikka Pizza"))
        assertEquals(Outcome.STUCK, log.outcome)
        assertTrue(log.reason, log.reason!!.contains("Paneer Tikka Pizza"))
        assertTrue(z.cart.isEmpty())
    }
}
