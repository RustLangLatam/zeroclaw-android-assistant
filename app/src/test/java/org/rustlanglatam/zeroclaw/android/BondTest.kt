package org.rustlanglatam.zeroclaw.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BondTest {

    @Test
    fun theRainbowLightsUpAsSheLearns() {
        val none = Bond.of { Memory.Depth.NONE }
        assertEquals(0f, none.lit, 0.001f)
        assertEquals(0.65f, Bond.color(none.you), 0.001f) // minimum brightness: the ring reads as lit even with nothing known
        val whole = Bond.of { Memory.Depth.WELL }
        assertTrue(whole.whole)
        assertEquals(1f, Bond.color(whole.world), 0.001f)
    }

    @Test
    fun eachThirdIsAStrand() {
        // Only work, routines and places known well: the whole lit share is the "day" strand.
        val day = Bond.of { if (it == Memory.Area.WORK || it == Memory.Area.ROUTINES || it == Memory.Area.PLACES) Memory.Depth.WELL else Memory.Depth.NONE }
        assertEquals(Bond.Ring(0f, 1f, 0f), day)
        assertFalse(day.whole)
        assertEquals(Bond.Strand.YOU, Bond.strand(Memory.Area.HEALTH))
        assertEquals(Bond.Strand.WORLD, Bond.strand(Memory.Area.INTERESTS))
    }

    @Test
    fun herLoad() {
        assertEquals(Bond.Load.CALM, Bond.load(false, 0, 0, 0, 2))
        assertEquals(Bond.Load.BUSY, Bond.load(false, 0, 0, 0, 3))
        assertEquals(Bond.Load.BUSY, Bond.load(false, 1, 0, 0, 0))
        assertEquals(Bond.Load.URGENT, Bond.load(true, 0, 0, 0, 0))
    }
}

class LedTest {

    @Test
    fun theButtonAndTheFloatingMarkLightEachLedTheSame() {
        // Both renderers use ledColor and ledLight, so an LED's tone and brightness are computed in one place.
        val bond = Bond.Ring(you = 1f, day = 0.5f, world = 0f)
        listOf(0f, 0.3f, 0.5f, 0.7f, 1f).forEach { f ->
            assertTrue(ledLight(f, bond, grey = false) in 0.65f..1f)
            assertEquals(ledColor(f, Palettes.dusk, grey = false), along(f, Palettes.dusk.ringA, Palettes.dusk.ringB, Palettes.dusk.ringC))
        }
        assertEquals(1f, ledLight(0f, bond, grey = false), 0.001f)
        assertEquals(0.65f, ledLight(1f, bond, grey = false), 0.001f)
    }
}
