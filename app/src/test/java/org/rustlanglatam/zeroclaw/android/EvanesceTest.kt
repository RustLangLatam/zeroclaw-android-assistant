package org.rustlanglatam.zeroclaw.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EvanesceTest {
    private fun at(ms: Int) = ms.toFloat() / Evanesce.TOTAL_MS
    private val hold = at(Evanesce.HOLD_MS)
    private val cardGone = at(Evanesce.HOLD_MS + Evanesce.CARD_MS)
    private val smoked = at(Evanesce.SMOKE_AT + Evanesce.DISSOLVE_MS)

    @Test fun wholeWhileHeld() {
        assertEquals(1f, Evanesce.alpha(hold), 0f)
        assertEquals(1f, Evanesce.words(hold), 0f)
        assertEquals(0f, Evanesce.dissolve(hold), 0f)
    }

    @Test fun theCardStartsFirstAndTheWordsStartHalfwayThroughIt() {
        val quarter = at(Evanesce.HOLD_MS + Evanesce.CARD_MS / 4)
        assertTrue(Evanesce.alpha(quarter) < 1f)
        assertEquals(0f, Evanesce.dissolve(quarter), 0f)
        val half = at(Evanesce.SMOKE_AT)
        assertTrue(Evanesce.alpha(half) in 0.4f..0.6f)
        assertEquals(0f, Evanesce.dissolve(half), 0f)
        assertEquals(0f, Evanesce.alpha(cardGone), 0f)
        assertTrue(Evanesce.dissolve(cardGone) > 0f)
        assertEquals(1f, Evanesce.words(cardGone), 0f)
    }

    @Test fun cellsGoAtTheirOwnMomentsAndNothingIsLeftAtTheEnd() {
        val mid = cardGone + (smoked - cardGone) * 0.5f
        assertTrue(Evanesce.cell(mid, 0f) < Evanesce.cell(mid, 1f))
        for (d in listOf(0f, 0.3f, 0.7f)) assertEquals(0f, Evanesce.cell(smoked, d), 0f)
        assertEquals(0f, Evanesce.words(1f), 0f)
        assertEquals(0f, Evanesce.trail(at(Evanesce.SMOKE_AT)), 0f)
        assertEquals(0f, Evanesce.trail(smoked), 0f)
    }

    @Test fun strandsAreStillUntilTheSmokeThenSwayAndRiseWithinBounds() {
        assertEquals(0f to 0f, Evanesce.strand(at(Evanesce.SMOKE_AT), 3, 20, 12f, 40f))
        val moved = (0 until 20).map { Evanesce.strand(smoked, it, 20, it * 5f, 40f) }
        assertTrue(moved.all { (sway, rise) -> sway in -1f..1f && rise in 0f..1.25f })
        assertTrue(moved.map { it.second }.toSet().size > 10)
    }

    @Test fun neighboursDifferAndDelaysStayInRange() {
        val delays = (0 until 40).flatMap { x -> (0 until 10).map { y -> Evanesce.delay(x, y) } }
        assertTrue(delays.all { it in 0f..1f })
        assertTrue(delays.toSet().size > 300)
    }
}
