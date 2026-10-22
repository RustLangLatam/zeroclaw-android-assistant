package org.rustlanglatam.zeroclaw.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.concurrent.thread

class PolicyTest {

    private var day = LocalDate.of(2026, 10, 16)
    private val policy = Policy(Policy.DEFAULT_RULES) { day }
    // Explicit groups: the global ones start empty and other tests set their own.
    private val ana = Target("Ana", setOf("family"))
    private val laura = Target("Laura", setOf("work"))
    private val stranger = Target("Pat")

    private fun owner(capability: Capability, target: Target) = policy.decide(Principal.OWNER, capability, target).autonomy
    private fun third(capability: Capability, target: Target) = policy.decide(Principal.THIRD_PARTY, capability, target).autonomy

    @Test
    fun internalActionsRunAlone() {
        assertEquals(Autonomy.ACT, owner(Capability.ALARM_SET, Policy.OWNER_TARGET))
    }

    @Test
    fun theMostSpecificScopeWins() {
        assertEquals(Autonomy.ASK, owner(Capability.MESSAGE_SEND, laura))
        assertEquals(Autonomy.ACT_NOTIFY, owner(Capability.MESSAGE_SEND, ana))
    }

    @Test
    fun aNeverRuleWinsOverAMoreSpecificOne() {
        val p = Policy(
            listOf(
                Rule(Principal.OWNER, Capability.MESSAGE_SEND, Scope.ALL, Autonomy.NEVER),
                Rule(Principal.OWNER, Capability.MESSAGE_SEND, Scope.contact("Ana"), Autonomy.ASK),
            ),
        )
        assertEquals(Autonomy.NEVER, p.decide(Principal.OWNER, Capability.MESSAGE_SEND, ana).autonomy)
    }

    @Test
    fun theMostRestrictiveWinsAmongEquallySpecificRules() {
        val p = Policy(
            listOf(
                Rule(Principal.OWNER, Capability.ALARM_SET, Scope.ALL, Autonomy.ACT),
                Rule(Principal.OWNER, Capability.ALARM_SET, Scope.ALL, Autonomy.ASK),
            ),
        )
        assertEquals(Autonomy.ASK, p.decide(Principal.OWNER, Capability.ALARM_SET, Policy.OWNER_TARGET).autonomy)
    }

    @Test
    fun noRuleAsksTheOwnerAndRefusesThirdParties() {
        assertEquals(Autonomy.ASK, owner(Capability.CALENDAR_ACCEPT, laura))
        assertEquals(Autonomy.NEVER, third(Capability.MESSAGE_SEND, laura))
        assertEquals(Autonomy.NEVER, third(Capability.AVAILABILITY_SHARE, stranger))
    }

    @Test
    fun rulesAreClampedToTheirCeiling() {
        val p = Policy(
            listOf(
                Rule(Principal.OWNER, Capability.MESSAGE_SEND, Scope.ALL, Autonomy.ACT),
                Rule(Principal.OWNER, Capability.MESSAGE_SEND, Scope.group("family"), Autonomy.ACT),
                Rule(Principal.OWNER, Capability.CALENDAR_ACCEPT, Scope.contact("Ana"), Autonomy.ACT, perDay = 1),
                Rule(Principal.THIRD_PARTY, Capability.AVAILABILITY_SHARE, Scope.ALL, Autonomy.ACT),
            ),
        )
        // Outgoing, irreversible actions ask unless a group or contact rule with a daily limit allows them.
        assertEquals(Autonomy.ASK, p.decide(Principal.OWNER, Capability.MESSAGE_SEND, laura).autonomy)
        assertEquals(Autonomy.ASK, p.decide(Principal.OWNER, Capability.MESSAGE_SEND, ana).autonomy)
        // Commitments always ask.
        assertEquals(Autonomy.ASK, p.decide(Principal.OWNER, Capability.CALENDAR_ACCEPT, ana).autonomy)
        assertEquals(Autonomy.ACT_NOTIFY, p.decide(Principal.THIRD_PARTY, Capability.AVAILABILITY_SHARE, stranger).autonomy)
        assertEquals(Autonomy.ACT_NOTIFY, third(Capability.AVAILABILITY_SHARE, laura))
    }

    @Test
    fun forbiddenCapabilitiesAreNeverAllowed() {
        val p = Policy(listOf(Rule(Principal.OWNER, Capability.CONTACTS_EXPORT, Scope.ALL, Autonomy.ACT)))
        assertEquals(Autonomy.NEVER, p.decide(Principal.OWNER, Capability.CONTACTS_EXPORT, ana).autonomy)
    }

    @Test
    fun aUsedUpDailyLimitFallsBackToTheDefault() {
        // A use is spent when the action runs (Guard.gated); deciding alone spends nothing.
        fun done(principal: Principal, capability: Capability, target: Target) =
            policy.decide(principal, capability, target).also { d -> d.spends?.let(policy::spend) }.autonomy
        repeat(5) { assertEquals(Autonomy.ACT_NOTIFY, done(Principal.OWNER, Capability.MESSAGE_SEND, ana)) }
        assertEquals(Autonomy.ASK, owner(Capability.MESSAGE_SEND, ana))
        repeat(10) { assertEquals(Autonomy.ACT, done(Principal.THIRD_PARTY, Capability.MESSAGE_TAKE, stranger)) }
        assertEquals(Autonomy.NEVER, third(Capability.MESSAGE_TAKE, stranger))
        assertEquals(Autonomy.ACT, third(Capability.MESSAGE_TAKE, laura)) // per contact
        day = day.plusDays(1)
        assertEquals(Autonomy.ACT_NOTIFY, owner(Capability.MESSAGE_SEND, ana))
    }

    @Test
    fun theKillSwitchStopsEverything() {
        policy.stopped = true
        assertEquals(Autonomy.NEVER, owner(Capability.ALARM_SET, Policy.OWNER_TARGET))
        policy.stopped = false
        assertEquals(Autonomy.ACT, owner(Capability.ALARM_SET, Policy.OWNER_TARGET))
    }

    @Test
    fun anUnansweredRequestExpires() {
        assertEquals(Approvals.Answer.EXPIRED, Approvals(timeoutMs = 50).request(Act("send", "Enviar")))
    }

    @Test
    fun anAnsweredRequestReturnsTheAnswer() {
        val approvals = Approvals(timeoutMs = 5_000)
        approvals.onRequest = { pending -> thread { approvals.answer(pending.id, true) } }
        assertEquals(Approvals.Answer.APPROVED, approvals.request(Act("send", "Enviar")))
        approvals.onRequest = { pending -> thread { approvals.answer(pending.id, false) } }
        assertEquals(Approvals.Answer.DECLINED, approvals.request(Act("send", "Enviar")))
    }

    @Test
    fun editsNeverGoAboveTheCeiling() {
        val sendAll = Policy.DEFAULT_RULES.indexOfFirst { it.capability == Capability.MESSAGE_SEND && it.scope == Scope.ALL }
        assertEquals(listOf(Autonomy.NEVER, Autonomy.ASK), Policy.choices(policy.rules[sendAll]))
        assertEquals(Autonomy.ASK, policy.edit(sendAll, Autonomy.ACT).autonomy)
        assertEquals(Autonomy.ASK, owner(Capability.MESSAGE_SEND, laura))
        assertEquals(Autonomy.NEVER, policy.edit(sendAll, Autonomy.NEVER).autonomy)
        assertEquals(Autonomy.NEVER, owner(Capability.MESSAGE_SEND, ana)) // a never rule wins anywhere
        // A tampered saved file is clamped on load too.
        val p = Policy(emptyList())
        p.restore(listOf(Rule(Principal.OWNER, Capability.CALENDAR_ACCEPT, Scope.ALL, Autonomy.ACT)))
        assertEquals(Autonomy.ASK, p.rules.single().autonomy)
    }

    @Test
    fun personalDataIsTheOwnersOnly() {
        assertEquals(Autonomy.ACT, owner(Capability.CALENDAR_READ, Policy.OWNER_TARGET))
        val p = Policy(listOf(Rule(Principal.THIRD_PARTY, Capability.CONTACTS_READ, Scope.ALL, Autonomy.ACT)))
        assertEquals(Autonomy.NEVER, p.decide(Principal.THIRD_PARTY, Capability.CONTACTS_READ, laura).autonomy)
    }

    @Test
    fun strongApprovalForOutwardAndCommittingActions() {
        assertTrue(Capability.MESSAGE_SEND.strongApproval)
        assertTrue(Capability.CALENDAR_ACCEPT.strongApproval)
        assertFalse(Capability.ALARM_SET.strongApproval)
        assertTrue(Capability.MESSAGE_SEND.undoable)
        assertFalse(Capability.CALENDAR_ACCEPT.undoable)
    }

    @Test
    fun anUndoneItemIsNeverSent() {
        val sent = java.util.concurrent.CopyOnWriteArrayList<String>()
        val outbox = Outbox { 200 }
        val a = outbox.queue(Act("to Ana", "A Ana")) { sent += "Ana" }
        outbox.queue(Act("to Laura", "A Laura")) { sent += "Laura" }
        assertEquals(2, outbox.waiting().size)
        assertTrue(outbox.undo(a.id))
        assertFalse(outbox.undo(a.id))
        Thread.sleep(500)
        assertEquals(listOf("Laura"), sent)
        assertTrue(outbox.waiting().isEmpty())
        assertFalse(outbox.undo(a.id + 1)) // already sent
    }

    @Test
    fun theSavedStateRoundTrips() {
        val state = Saved.State(
            rules = Policy.DEFAULT_RULES,
            stopped = true,
            undoSeconds = 60,
            counters = mapOf("2026-10-16 THIRD_PARTY NOTIFICATION_REPLY Ana" to 2),
        )
        assertEquals(state, Saved.decode(Saved.encode(state)))
        assertTrue("\"version\":${Saved.VERSION}" in Saved.encode(state))
    }

    @Test
    fun thirdPartiesLearnOnlyBusyOrFree() {
        assertEquals("busy", Disclosure.availability(true))
        assertEquals("free", Disclosure.availability(false))
    }
}
