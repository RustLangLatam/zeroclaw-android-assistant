package org.rustlanglatam.zeroclaw.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BusyTest {

    private val policy = Policy(Policy.DEFAULT_RULES)

    @Test
    fun eachContactIsToldOncePerPeriod() {
        val p = Busy.Period(until = 1_000, reason = Busy.Reason.BUSY)
        assertTrue(p.active(999))
        assertFalse(p.active(1_000))
        assertTrue(p.owes("Laura"))
        assertFalse(p.telling("Laura").owes("Laura"))
        assertTrue(p.telling("Laura").owes("Ana"))
    }

    @Test
    fun familyAndWorkAreToldStrangersNever() {
        assertEquals(Autonomy.ACT_NOTIFY, policy.decide(Principal.THIRD_PARTY, Capability.BUSY_REPLY, Target("Ana", setOf("family"))).autonomy)
        assertEquals(Autonomy.ACT_NOTIFY, policy.decide(Principal.THIRD_PARTY, Capability.BUSY_REPLY, Target("Laura", setOf("work"))).autonomy)
        assertEquals(Autonomy.NEVER, policy.decide(Principal.THIRD_PARTY, Capability.BUSY_REPLY, Target("Pat")).autonomy)
    }

    @Test
    fun theReplyIsFixedAndSaysOnlyThatTheOwnerCannotAnswer() {
        assertEquals("Milton can't answer until 11:00 and will write to you later. This is Ksandra, Milton's assistant.",
            Busy.reply(Lang.EN, Busy.Reason.BUSY, "Milton", "11:00"))
        assertEquals("Milton va al volante y te escribe en cuanto pueda. Soy Ksandra, su asistente.",
            Busy.reply(Lang.ES, Busy.Reason.DRIVING, "Milton", "11:00"))
    }
}
