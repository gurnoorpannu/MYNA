package com.example.myna_mimicyourinteractionsautomate

import com.example.myna_mimicyourinteractionsautomate.recipe.RecipeJson
import com.example.myna_mimicyourinteractionsautomate.record.Recorder
import com.example.myna_mimicyourinteractionsautomate.replay.Finder
import com.example.myna_mimicyourinteractionsautomate.screen.Identity
import com.example.myna_mimicyourinteractionsautomate.screen.UiNode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Teaching in WhatsApp: the recording keeps what replay needs to find the chat, never the chats' messages. */
class PrivateRecordingTest {
    private fun t(s: String, top: Int) = UiNode(text = s, cls = "TextView", t = top, b = top + 40, r = 900)
    private fun chat(name: String, preview: String, time: String, top: Int) = UiNode(cls = "RelativeLayout", clickable = true, t = top, b = top + 180, r = 1080,
        children = listOf(t(name, top + 20), t(preview, top + 90), t(time, top + 20)))
    private val chats = UiNode(cls = "FrameLayout", b = 2400, r = 1080, children = listOf(t("WhatsApp", 100),
        UiNode(cls = "RecyclerView", scrollable = true, t = 300, b = 2400, r = 1080, children = listOf(
            chat("Mom", "Call me when you land", "9:12 PM", 300),
            chat("Tasty Bites", "Your order #4412 is ready for pickup", "8:40 PM", 500),
            chat("Rahul", "bro the exam is tomorrow??", "Yesterday", 700)))))
    private val tastyBites get() = chats.children[1].children[1]

    private val secrets = listOf("Call me when you land", "Your order", "ready for pickup", "exam is tomorrow", "Mom", "Rahul")

    private fun record(app: String): String {
        val rec = Recorder("send hi to Tasty Bites on WhatsApp", app)
        rec.onTap(Identity.target(tastyBites, chats, rec.spoken), Identity.screen(chats, app, null))
        return RecipeJson.encodeToString(rec.finish())
    }

    @Test fun whatsappRecordingStoresNoMessagesOrOtherChats() {
        val json = record("com.whatsapp")
        for (s in secrets) assertFalse("recording contains \"$s\":\n$json", json.contains(s))
        assertTrue(json.contains("Tasty Bites"))   // the chat the user tapped is the task itself
    }

    @Test fun scrubbedTargetStillFindsTheSameChat() {
        val rec = Recorder("send hi to Tasty Bites on WhatsApp", "com.whatsapp")
        rec.onTap(Identity.target(tastyBites, chats, rec.spoken), Identity.screen(chats, "com.whatsapp", null))
        assertSame(tastyBites, Finder.find(chats, rec.steps.last().target!!)?.node)
    }

    @Test fun shoppingAppsKeepTheFullIdentity() {
        // Nearby texts are what makes replay robust in normal apps: only messaging apps are scrubbed.
        assertTrue(record("com.application.zomato").contains("Your order #4412 is ready for pickup"))
    }
}
