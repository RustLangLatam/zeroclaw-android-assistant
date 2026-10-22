package org.rustlanglatam.zeroclaw.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class BackOnlineTest {

    @Test
    fun theTopicsWhoseTimePassedOfflineAreCheckedWhenBack() {
        val morning = News.Topic(1, "inflación", time = "08:00")
        val evening = News.Topic(2, "fútbol", time = "20:00")
        val often = News.Topic(3, "lluvia", time = null, everyHours = 3)
        val since = LocalDateTime.of(2026, 10, 17, 7, 30)
        assertEquals(listOf(morning, often), News.missed(listOf(morning, evening, often), since, LocalDateTime.of(2026, 10, 17, 9, 0)))
        // An outage spanning midnight also covers the next morning's scheduled time.
        assertEquals(listOf(morning, evening, often), News.missed(listOf(morning, evening, often), LocalDateTime.of(2026, 10, 17, 19, 0), LocalDateTime.of(2026, 10, 18, 8, 30)))
    }

    @Test
    fun theUnansweredMessagesSinceTheCutAreAnsweredAgain() {
        val before = Inbox.received(Inbox.Message("app", "c1", "Ana", "¿Vienes?", 1_000)).also { Inbox.status(it, Inbox.Status.NO_ANSWER) }
        val during = Inbox.received(Inbox.Message("app", "c2", "Laura", "¿A las 10?", 5_000)).also { Inbox.status(it, Inbox.Status.NO_ANSWER) }
        val answered = Inbox.received(Inbox.Message("app", "c3", "Ana", "Vale", 6_000)).also { Inbox.status(it, Inbox.Status.ANSWERED) }
        val missed = Inbox.missed(since = 2_000)
        assertTrue(during in missed)
        assertTrue(before !in missed && answered !in missed)
        assertEquals("c2", during.conversation)
    }

    @Test
    fun theGreetingKnowsTheOwnersDayButNoEventTitle() {
        val note = Phrases.welcome("Milton", LocalDateTime.of(2026, 10, 17, 9, 0), Phrases.Day(listOf("inflación en Chile"), "11:30", 2))
        assertTrue("They follow: inflación en Chile." in note)
        assertTrue("next commitment today is at 11:30" in note)
        assertTrue("2 reminder(s) left today" in note)
    }
}
