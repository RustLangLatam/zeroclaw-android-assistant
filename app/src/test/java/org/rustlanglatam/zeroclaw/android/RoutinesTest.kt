package org.rustlanglatam.zeroclaw.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoutinesTest {

    @Test
    fun daysAndTimeBecomeACronExpression() {
        assertEquals("0 9 * * 0", Routines.cron("9:00", "domingos"))
        assertEquals("30 7 * * 1-5", Routines.cron("07:30", "entre semana"))
        assertEquals("0 20 * * *", Routines.cron("20:00", "daily"))
        assertEquals("15 8 * * 1,3,5", Routines.cron("08:15", "lunes, miércoles, viernes"))
        assertEquals("0 10 * * 0,6", Routines.cron("10:00", "fines de semana"))
    }

    @Test
    fun whatIsNotUnderstoodIsNotGuessed() {
        assertNull(Routines.cron("25:00", "daily"))
        assertNull(Routines.cron("9", "daily"))
        assertNull(Routines.cron("09:00", "a veces"))
    }
}

class UsageTest {
    @org.junit.Test
    fun theMonthTodayAndEachAgentMostFirst() {
        val s = io.github.rustlanglatam.zeroclaw.CostSummary(0.0, 0.05, 1.234, 1000, 12, byAgent = mapOf(
            "scribe" to io.github.rustlanglatam.zeroclaw.AgentCostStats("scribe", 0.2, 0, 0, 0, 0, 3),
            "owner" to io.github.rustlanglatam.zeroclaw.AgentCostStats("owner", 1.0, 0, 0, 0, 0, 9),
        ))
        val d = Usage.describe(s)
        org.junit.Assert.assertTrue(d, d.startsWith("Model cost this month: US$ 1.23; today: US$ 0.05; 12 calls. By agent: Contigo: US$ 1.00 (9 calls); Resúmenes y avisos: US$ 0.20"))
        org.junit.Assert.assertTrue(Usage.describe(null).contains("not reachable"))
        val free = Usage.describe(s.copy(monthlyCostUsd = 0.0))
        org.junit.Assert.assertTrue(free, free.startsWith("No price is set for the model, so only its use is known: 1000 tokens"))
    }
}
