package org.rustlanglatam.zeroclaw.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JournalTest {

    private fun entry(action: String, outcome: String) = Journal.Entry("10:00:00", Principal.THIRD_PARTY, action, outcome, "rule: x")

    @Test
    fun anEntryIsOneLineAndALineCutShortIsSkipped() {
        val e = entry("reply to Ana: \"hi\"\nbye", "queued (act_notify)")
        val line = Journal.line(e)
        assertTrue('\n' !in line)
        assertEquals(e, Journal.parse(line))
        assertNull(Journal.parse(line.dropLast(5)))
    }

    @Test
    fun whatWasQueuedAndNeverResolvedWasLost() {
        val log = listOf(
            entry("reply to Ana: yes", "queued (act_notify)"),
            entry("reply to Ana: yes", "sent: Replied to Ana."),
            entry("reply to Laura: at 10", "queued (ask)"),
            entry("reply to Ana: wine", "queued (act_notify)"),
            entry("reply to Ana: wine", "undone"),
        )
        assertEquals(listOf(log[2]), Journal.unresolved(log))
        assertEquals(emptyList<Journal.Entry>(), Journal.unresolved(log + entry("reply to Laura: at 10", "lost")))
    }

    @Test
    fun aVersionOneFileStillLoadsItsLog() {
        val old = """{"rules":[],"stopped":false,"undoSeconds":30,"log":[{"at":"09:00:00","principal":"OWNER","action":"alarm","outcome":"act","reason":"rule"}]}"""
        val state = Saved.decode(old)
        assertEquals(listOf(Journal.Entry("09:00:00", Principal.OWNER, "alarm", "act", "rule")), state.log)
        assertEquals(emptyMap<String, Int>(), state.counters)
    }
}
