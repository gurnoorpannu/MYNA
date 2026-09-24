package com.example.myna_mimicyourinteractionsautomate

import com.example.myna_mimicyourinteractionsautomate.intent.Extras
import org.junit.Assert.assertEquals
import org.junit.Test

class ExtrasTest {
    @Test fun quantities() {
        assertEquals(2, Extras.parse("order 2 margheritas from dominos").qty)
        assertEquals(3, Extras.parse("get me three farmhouse pizzas").qty)
        assertEquals(2, Extras.parse("order a couple of margheritas").qty)
        assertEquals(null, Extras.parse("add a phone case for my s25 ultra").qty)     // never the "25" in s25
        assertEquals(null, Extras.parse("order a double cheese burst margherita").qty) // a dish name, not a count
        assertEquals("order margheritas from dominos", Extras.parse("order 2 margheritas from dominos").rest)
    }

    @Test fun addresses() {
        assertEquals("Work", Extras.parse("order a margherita from dominos and deliver to work").address)
        assertEquals("Office", Extras.parse("send it to my office").address)
        assertEquals("Home", Extras.parse("order farmhouse to my home address").address)
        assertEquals(null, Extras.parse("add a laptop stand to my amazon cart").address)
    }

    @Test fun singular() {
        assertEquals("margherita", Extras.singular("margheritas"))
        assertEquals("box", Extras.singular("boxes"))
        assertEquals("glass", Extras.singular("glass"))
    }
}
