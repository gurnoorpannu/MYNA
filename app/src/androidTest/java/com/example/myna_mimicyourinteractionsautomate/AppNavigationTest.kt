package com.example.myna_mimicyourinteractionsautomate

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.myna_mimicyourinteractionsautomate.a11y.MynaService
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The app starts on Home, and the Home / Teach / History tabs render and switch. */
@RunWith(AndroidJUnit4::class)
class AppNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun launchesOnHome() {
        compose.onNodeWithText("MYNA").assertIsDisplayed()
        compose.onNodeWithText("Your automations").assertIsDisplayed()
    }

    @Test fun tabsSwitchAndRender() {
        compose.onNodeWithText("Teach").performClick()
        compose.onNodeWithText("Teach MYNA").assertIsDisplayed()
        compose.onNodeWithText("Show me once").assertIsDisplayed()

        compose.onNodeWithText("History").performClick()
        compose.onAllNodesWithText("History").assertCountEquals(2)   // the page title and the tab label
        compose.onNodeWithText("Teach MYNA").assertDoesNotExist()

        compose.onNodeWithText("Home").performClick()
        compose.onNodeWithText("Your automations").assertIsDisplayed()
    }

    @Test fun teachingIsOffUntilAccessibilityIsOn() {
        if (MynaService.instance != null) return   // service enabled on this phone: nothing to check
        compose.onNodeWithText("MYNA needs Accessibility access to see and tap for you.").assertIsDisplayed()
        compose.onNodeWithText("Teach").performClick()
        compose.onNodeWithText("Show me once").assertIsNotEnabled()
    }
}
