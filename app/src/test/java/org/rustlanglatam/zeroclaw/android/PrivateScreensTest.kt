package org.rustlanglatam.zeroclaw.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateScreensTest {
    @Test
    fun whatOthersWroteWhatSheKnowsAndTheAccountsAreKeptFromScreenshots() {
        listOf(Place.TALK, Place.ACTIVITY, Place.CONTROL, Place.RULES, Place.MEMORY, Place.CONNECTORS)
            .forEach { assertTrue("$it", Place.private(it, approving = false, connector = null)) }
        assertFalse(Place.private(Place.HOME, approving = false, connector = null))
        // An approval or an account form makes any screen private.
        assertTrue(Place.private(Place.HOME, approving = true, connector = null))
        assertTrue(Place.private(Place.HOME, approving = false, connector = "gmail"))
    }
}
