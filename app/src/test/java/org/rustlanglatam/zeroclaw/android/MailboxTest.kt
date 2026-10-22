package org.rustlanglatam.zeroclaw.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MailboxTest {

    private val groups = mapOf("Laura" to setOf("work"), "Ana" to setOf("family"))

    @Test
    fun theSenderFromTheNotification() {
        assertEquals("Laura Gómez" to "laura@example.com", Mailbox.sender("Laura Gómez <laura@example.com>"))
        assertEquals("Ana" to null, Mailbox.sender("Ana"))
        assertEquals("pat@example.com" to "pat@example.com", Mailbox.sender("pat@example.com"))
    }

    @Test
    fun whatLooksImportantAndWhy() {
        // The contact is resolved from the sender's address (by the phone), never from the display name.
        val (important, why) = Mailbox.importance("Laura", "Contrato", "Hay que firmar el contrato antes del viernes", groups)
        assertTrue(important)
        assertEquals("es de Laura, de tu trabajo, y habla de «contrato»", why)
        assertTrue(Mailbox.importance(null, "Tu factura vence hoy", "", groups).first)
        assertFalse(Mailbox.importance(null, "Rebajas: 50% de descuento", "Oferta urgente", groups).first)
        assertFalse(Mailbox.importance("Pat", "Hola", "¿Qué tal?", groups).first)
        // A display name matching a contact ("Laura") with an unknown address gets no group.
        assertFalse(Mailbox.importance(null, "Hola", "¿Qué tal?", groups).first)
    }

    @Test
    fun theSameNotificationAgainIsTheSameEmail() {
        val first = Mailbox.receive("app", "k1", "Ana <ana@example.com>", "Cena", "¿Vienes?", 1L, groups)
        assertTrue(first != null)
        assertNull(Mailbox.receive("app", "k1", "Ana <ana@example.com>", "Cena", "¿Vienes?", 1L, groups))
        assertTrue(Mailbox.read(first!!.id).startsWith("Email #${first.id} from Ana <ana@example.com>"))
    }

    @Test
    fun theInboxAndTheNotificationAreOneEmail() {
        val at = 1_000_000L
        // The notification arrives first (name, no address), then the IMAP copy: both merge into one email that gains the UID.
        val shown = Mailbox.receive("com.google.android.gm", "n1", "Laura Gómez", "Factura de octubre", "Adjunta", at, groups)!!
        assertNull(Mailbox.receive("com.google.android.gm", "imap:42", "\"Laura Gómez\" <laura@example.com>", "Factura de octubre", "Adjunta", at + 30_000, groups, uid = 42, account = "gmail"))
        assertEquals(42L, Mailbox.mails.first { it.id == shown.id }.uid)
        assertTrue(Mailbox.describe(false).contains("#${shown.id} (uid 42 · gmail) from Laura Gómez <laura@example.com>"))
        // A different subject is a different email.
        assertTrue(Mailbox.receive("com.google.android.gm", "imap:43", "\"Laura Gómez\" <laura@example.com>", "Otra cosa", "", at, groups, uid = 43) != null)
    }
}
