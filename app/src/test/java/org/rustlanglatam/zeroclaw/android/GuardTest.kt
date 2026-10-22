package org.rustlanglatam.zeroclaw.android

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardTest {

    @After
    fun reset() {
        Guard.policy.stopped = false
        Guard.approvals.onRequest = {}
        Guard.outbox.waiting().forEach { Guard.outbox.undo(it.id) }
        Policy.groups = emptyMap()
        Guard.secure = { true }
        Guard.approvals.timeoutMs = 60_000
    }

    @Test
    fun anApprovalNotAnsweredInTimeIsRefusedAndKeptForLater() {
        Guard.approvals.timeoutMs = 50
        var ran = false
        val outcome = Guard.attempt(Principal.OWNER, Capability.CONTACT_GROUPS, Policy.OWNER_TARGET, Act.group("Ana", "family")) { ran = true; "done" }
        assertTrue(outcome is Guard.Outcome.Refused && outcome.notApproved && outcome.expired)
        assertFalse(ran)
        assertEquals("no answer in time", Guard.journal.entries.last().outcome)
    }

    @Test
    fun aDeclineIsNotAnExpiry() {
        Guard.approvals.onRequest = { Guard.approvals.answer(it.id, false) }
        val outcome = Guard.attempt(Principal.OWNER, Capability.CONTACT_GROUPS, Policy.OWNER_TARGET, Act.group("Ana", "family")) { "done" }
        assertTrue(outcome is Guard.Outcome.Refused && !outcome.expired)
    }

    @Test
    fun anAllowedActionRunsAndIsDone() {
        var ran = false
        val outcome = Guard.attempt(Principal.OWNER, Capability.ALARM_SET, Policy.OWNER_TARGET, Act.alarm(7, 0)) { ran = true; "Alarm set." }
        assertEquals(Guard.Outcome.Done("Alarm set."), outcome)
        assertTrue(ran)
        assertEquals("act", Guard.journal.entries.last().outcome)
    }

    @Test
    fun theKillSwitchRefusesWithoutRunningIt() {
        Guard.policy.stopped = true
        var ran = false
        val outcome = Guard.attempt(Principal.OWNER, Capability.ALARM_SET, Policy.OWNER_TARGET, Act.alarm(7, 0)) { ran = true; "Alarm set." }
        assertTrue(outcome is Guard.Outcome.Refused && !outcome.notApproved)
        assertFalse(ran)
    }

    @Test
    fun whatTheOwnerDoesNotApproveIsRefusedAsSuch() {
        Guard.approvals.onRequest = { Guard.approvals.answer(it.id, false) }
        val outcome = Guard.attempt(Principal.OWNER, Capability.CONTACT_GROUPS, Policy.OWNER_TARGET, Act.group("Ana", "family")) { "done" }
        assertTrue(outcome is Guard.Outcome.Refused && outcome.notApproved)
        assertEquals("not approved", Guard.journal.entries.last().outcome)
    }

    @Test
    fun aFamilyReplyIsQueuedAndAnUndoGivesItsUseBack() {
        Policy.groups = mapOf("Ana" to setOf("family"))
        val before = Guard.policy.counters().values.sum()
        val outcome = Guard.attempt(Principal.THIRD_PARTY, Capability.NOTIFICATION_REPLY, Policy.target("Ana"), Act.reply(1, "Ana", null, "yes")) { "Replied to Ana." }
        assertTrue(outcome is Guard.Outcome.Queued)
        assertEquals(before + 1, Guard.policy.counters().values.sum())
        assertTrue(Guard.undo(Guard.outbox.waiting().single().id))
        assertEquals(before, Guard.policy.counters().values.sum())
    }

    @Test
    fun aStrongApprovalWithoutAScreenLockIsRefusedWithoutAsking() {
        Guard.secure = { false }
        var asked = false
        Guard.approvals.onRequest = { asked = true; Guard.approvals.answer(it.id, true) }
        var ran = false
        val outcome = Guard.attempt(Principal.OWNER, Capability.MESSAGE_SEND, Policy.OWNER_TARGET, Act.message("Laura", "hi")) { ran = true; "sent" }
        assertTrue(outcome is Guard.Outcome.Refused && outcome.notApproved)
        assertFalse(asked)
        assertFalse(ran)
    }
}
