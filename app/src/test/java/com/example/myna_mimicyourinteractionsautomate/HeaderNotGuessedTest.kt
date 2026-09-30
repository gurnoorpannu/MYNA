package com.example.myna_mimicyourinteractionsautomate

import com.example.myna_mimicyourinteractionsautomate.screen.Identity
import com.example.myna_mimicyourinteractionsautomate.screen.UiNode
import org.junit.Assert.assertNull
import org.junit.Test

/** 30 Sep 11:38 Zomato teach: a tap on Domino's page header was guessed although the user never made it. */
class HeaderNotGuessedTest {
    @Test fun headerOnBothScreensIsNeverAGuessedTap() {
        // After the options sheet closed and the menu search was cleared, the full menu came back: lots of new text,
        // and the page's own header "Domino's Pizza" (same place on both screens) was guessed as the tap.
        fun t(s: String, top: Int, id: String? = null, clickable: Boolean = false) =
            UiNode(text = s, id = id, cls = "View", clickable = clickable, t = top, b = top + 85, l = 31, r = 839)
        val title = { t("Domino's Pizza", 531, id = "title", clickable = true) }
        val searching = UiNode(cls = "FrameLayout", b = 2392, r = 1080, children = listOf(title(), t("Search in Domino's Pizza", 208),
            t("Margherita", 900), t("Cheese n Corn", 1200)))
        val menu = UiNode(cls = "FrameLayout", b = 2392, r = 1080, children = listOf(title(), t("Recommended", 900),
            t("Domino's Pizza Mania Combo", 1000), t("Pizza Mania Veg", 1100), t("Garlic Breadsticks", 1300), t("1 item added", 2150)))
        assertNull(Identity.inferByDiff(searching, menu))
    }
}
