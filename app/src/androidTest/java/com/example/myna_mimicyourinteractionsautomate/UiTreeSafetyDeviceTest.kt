package com.example.myna_mimicyourinteractionsautomate

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.myna_mimicyourinteractionsautomate.a11y.UiTree
import com.example.myna_mimicyourinteractionsautomate.safety.SafetyGate
import com.example.myna_mimicyourinteractionsautomate.screen.UiNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The safety gate on real framework AccessibilityNodeInfo objects (Android 11+ constructor), captured with the
 * same UiTree code the service uses. Children need a live window, so each node is captured on its own and the
 * screen is assembled from the captures.
 */
@RunWith(AndroidJUnit4::class)
class UiTreeSafetyDeviceTest {
    private fun node(text: String? = null, hint: String? = null, id: String? = null, cls: String = "android.widget.TextView",
                     clickable: Boolean = false, editable: Boolean = false, password: Boolean = false) = AccessibilityNodeInfo().apply {
        this.text = text
        hintText = hint
        viewIdResourceName = id
        className = cls
        isClickable = clickable
        isEditable = editable
        isPassword = password
        isVisibleToUser = true
        setBoundsInScreen(Rect(0, 100, 1080, 200))
    }

    private fun screen(vararg n: AccessibilityNodeInfo) = UiNode(cls = "FrameLayout", children = n.map(UiTree::capture))

    @Test fun captureKeepsWhatTheGateReads() {
        val u = UiTree.capture(node(hint = "Enter OTP", id = "com.application.zomato:id/otp_field", cls = "android.widget.EditText",
            clickable = true, editable = true))
        assertEquals("otp_field", u.id)
        assertEquals("EditText", u.cls)
        assertEquals("Enter OTP", u.hint)
        assertTrue(u.editable && u.clickable && u.visible)
        assertEquals(listOf(0, 100, 1080, 200), u.bounds)
    }

    @Test fun realNodesBlockPaymentOtpPasswordLoginAndPlaceOrder() {
        assertEquals(SafetyGate.Kind.CREDENTIAL, SafetyGate.check(screen(node(cls = "android.widget.EditText", editable = true, password = true)), "x")?.kind)
        assertEquals(SafetyGate.Kind.CREDENTIAL, SafetyGate.check(screen(node(hint = "Enter OTP", editable = true, cls = "android.widget.EditText")), "x")?.kind)
        assertEquals(SafetyGate.Kind.FINAL_ORDER, SafetyGate.check(screen(node("Domino's Pizza"),
            node("Place Order", cls = "android.widget.Button", clickable = true)), "com.application.zomato")?.kind)
        assertEquals(SafetyGate.Kind.PAYMENT, SafetyGate.check(screen(node("UPI"), node("Credit or debit card"), node("Net Banking")),
            "in.amazon.mShop.android.shopping")?.kind)
        assertEquals(SafetyGate.Kind.LOGIN, SafetyGate.check(screen(node(hint = "Email address", editable = true, cls = "android.widget.EditText"),
            node("Log in", cls = "android.widget.Button", clickable = true)), "x")?.kind)
    }

    @Test fun realNodesLetAMenuThrough() {
        assertNull(SafetyGate.check(screen(node("Margherita Pizza"), node("ADD", cls = "android.widget.Button", clickable = true),
            node("View Cart", cls = "android.widget.Button", clickable = true)), "com.application.zomato"))
    }
}
