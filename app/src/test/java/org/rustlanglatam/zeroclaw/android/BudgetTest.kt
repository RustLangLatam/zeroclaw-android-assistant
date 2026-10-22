package org.rustlanglatam.zeroclaw.android

import org.junit.Assert.assertEquals
import org.junit.Test

class BudgetTest {

    @Test
    fun theOwnerIsToldAt80AndAtTheBudget() {
        assertEquals(0, Budget.stage(3.9, 5.0))
        assertEquals(80, Budget.stage(4.0, 5.0))
        assertEquals(80, Budget.stage(4.99, 5.0))
        assertEquals(100, Budget.stage(5.0, 5.0))
        assertEquals(100, Budget.stage(7.0, 5.0))
    }

    @Test
    fun noBudgetNeverStopsAnything() {
        assertEquals(0, Budget.stage(1000.0, 0.0))
    }

    @Test
    fun theBudgetIsNamedInBothLanguages() {
        assertEquals(Preferences.Setting.MODEL_BUDGET, Preferences.Setting.named("model_budget"))
        assertEquals(Preferences.Setting.MODEL_BUDGET, Preferences.Setting.named("presupuesto"))
        assertEquals(Preferences.Setting.VOICE_CEILING, Preferences.Setting.named("voice_ceiling"))
    }
}
