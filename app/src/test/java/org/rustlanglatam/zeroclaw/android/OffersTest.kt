package org.rustlanglatam.zeroclaw.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OffersTest {
    private val day = 86_400_000L

    @Test
    fun theMorningBriefingIsOfferedOnceInTheFirstWeek() {
        assertTrue(Offers.morningDue(setupAt = 0, now = 2 * day, hour = 8, asked = false, hasRoutines = false))
        assertFalse(Offers.morningDue(0, 2 * day, 8, asked = true, hasRoutines = false))
        assertFalse(Offers.morningDue(0, 2 * day, 8, asked = false, hasRoutines = true))
        assertFalse(Offers.morningDue(0, 2 * day, 15, asked = false, hasRoutines = false))
        assertFalse(Offers.morningDue(0, day / 2, 8, asked = false, hasRoutines = false))
        assertFalse(Offers.morningDue(0, 9 * day, 8, asked = false, hasRoutines = false))
    }
}
