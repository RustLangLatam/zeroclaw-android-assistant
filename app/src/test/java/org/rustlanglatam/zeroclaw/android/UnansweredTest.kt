package org.rustlanglatam.zeroclaw.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UnansweredTest {

    private val h = 3600_000L
    private val now = 100 * h
    private val groups = mapOf("Laura" to setOf("work"), "Ana" to setOf("family"))

    private fun entry(id: Int, who: String, status: String, hoursAgo: Long, chat: String = "c-$who") =
        Inbox.Entry(id, "10:00", who, "text $id", status, "app", now - hoursAgo * h, chat)

    @Test
    fun aFamilyOrWorkChatStillShownAndUnansweredWaits() {
        val entries = listOf(entry(1, "Laura", Inbox.Status.NOT_APPROVED, 5), entry(2, "Laura", Inbox.Status.EXPIRED, 4))
        val waiting = Unanswered.messages(entries, groups, setOf("c-Laura"), now, 4)
        assertEquals(listOf(1, 2), waiting["Laura"]?.map { it.id })
    }

    @Test
    fun answeredOpenedRecentOrUnknownChatsDoNot() {
        val shown = setOf("c-Laura", "c-Ana", "c-Pat")
        // Answered by the assistant.
        assertTrue(Unanswered.messages(listOf(entry(1, "Ana", Inbox.Status.ANSWERED, 6)), groups, shown, now, 4).isEmpty())
        // The owner opened the chat: its notification is gone.
        assertTrue(Unanswered.messages(listOf(entry(1, "Laura", Inbox.Status.RECEIVED, 6)), groups, emptySet(), now, 4).isEmpty())
        // Too recent.
        assertTrue(Unanswered.messages(listOf(entry(1, "Laura", Inbox.Status.RECEIVED, 1)), groups, shown, now, 4).isEmpty())
        // Not family or work.
        assertTrue(Unanswered.messages(listOf(entry(1, "Pat", Inbox.Status.RECEIVED, 9)), groups, shown, now, 4).isEmpty())
    }

    @Test
    fun onlyTheMessagesAfterTheLastAnswerCount() {
        val entries = listOf(entry(1, "Ana", Inbox.Status.RECEIVED, 9), entry(2, "Ana", Inbox.Status.ANSWERED, 8), entry(3, "Ana", Inbox.Status.HELD_BACK, 5))
        assertEquals(listOf(3), Unanswered.messages(entries, groups, setOf("c-Ana"), now, 4)["Ana"]?.map { it.id })
    }

    @Test
    fun importantUnopenedEmailsWait() {
        val mail = Mailbox.Mail(7, "app", "Bank", null, "Your statement", "", now - 5 * h, important = true, why = null)
        assertEquals(listOf(7), Unanswered.mails(listOf(mail, mail.copy(id = 8, important = false)), emptySet(), now, 4).map { it.id })
        assertTrue(Unanswered.mails(listOf(mail), setOf(7), now, 4).isEmpty())
    }
}
