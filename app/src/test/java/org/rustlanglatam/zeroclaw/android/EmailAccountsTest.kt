package org.rustlanglatam.zeroclaw.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmailAccountsTest {
    private val gmail = EmailAccounts.Account("gmail", EmailAccounts.Provider.GMAIL, "milton@gmail.com", "abcdefghijklmnop")
    private val work = EmailAccounts.Account("correo", EmailAccounts.Provider.OTHER, "milton@empresa.com", "se\"creta",
        imap = "imap.empresa.com", smtp = "smtp.empresa.com", smtpPort = 587)

    @Test
    fun eachProviderItsKindOfPassword() {
        val g = EmailAccounts.Provider.GMAIL
        assertEquals("abcdefghijklmnop", g.password(" abcd efgh ijkl mnop "))
        assertTrue(g.valid("milton@gmail.com", "abcd efgh ijkl mnop"))
        assertFalse(g.valid("milton@gmail.com", "short"))
        assertFalse(g.valid("milton", "abcdefghijklmnop"))
        // Apple app passwords: 16 letters grouped with dashes.
        assertTrue(EmailAccounts.Provider.ICLOUD.valid("milton@icloud.com", "abcd-efgh-ijkl-mnop"))
        // A generic server accepts any non-empty password.
        assertTrue(EmailAccounts.Provider.OTHER.valid("milton@empresa.com", "x"))
    }

    @Test
    fun severalAccountsEachWithItsAlias() {
        assertEquals("gmail2", EmailAccounts.alias(EmailAccounts.Provider.GMAIL, listOf(gmail)))
        assertEquals("yahoo", EmailAccounts.alias(EmailAccounts.Provider.YAHOO, listOf(gmail)))
        val both = listOf(gmail, work)
        assertEquals(work, EmailAccounts.find("milton@empresa.com", both))
        assertEquals(gmail, EmailAccounts.find("Gmail", both))
        assertNull(EmailAccounts.find("", both))
        assertEquals(gmail, EmailAccounts.find(null, listOf(gmail)))
        assertEquals(both, EmailAccounts.parse(EmailAccounts.json(both)))
    }

    @Test
    fun zeroClawListensToNobodyAndChangesNothing() {
        assertEquals("", EmailAccounts.config(emptyList()))
        val config = EmailAccounts.config(listOf(gmail, work))
        assertTrue("[channels.email.gmail]" in config && "[channels.email.correo]" in config)
        assertEquals(2, Regex("observer_mode = true").findAll(config).count())
        // No peer group and no agent binding, so the channel never replies.
        assertFalse("peer" in config || "agent" in config)
        assertTrue("imap_host = \"imap.empresa.com\"" in config && "smtp_port = 587" in config && "smtp_tls = false" in config)
        assertTrue(EmailAccounts.inConfig(config, listOf(gmail, work)))
        assertFalse(EmailAccounts.inConfig(config, listOf(gmail)))
        assertFalse(EmailAccounts.inConfig(config, listOf(gmail, work.copy(password = "nueva"))))
    }

    @Test
    fun eachProviderItsGuide() {
        EmailAccounts.Provider.entries.forEach { p ->
            val blocks = Cards.blocks(EmailAccounts.guide(p))
            assertTrue(p.name, blocks.count { it is Cards.Block.Step } >= 3)
            assertEquals(p.name, p.link, blocks.filterIsInstance<Cards.Block.Bullet>().firstNotNullOfOrNull { Cards.link(it.text) })
        }
    }

    @Test
    fun anAccountSeenBothWaysIsOnThePhone() {
        val seen = mutableListOf<String>()
        Mailbox.onPhone = { seen += it }
        val at = 5_000_000L
        Mailbox.receive("email", "imap:correo:7", "\"Laura\" <laura@example.com>", "Contrato", "", at, emptyMap(), uid = 7, account = "correo")
        assertTrue(seen.isEmpty())
        Mailbox.receive("com.android.email", "n7", "Laura", "Contrato", "", at + 1_000, emptyMap())
        assertEquals(listOf("correo"), seen)
        Mailbox.onPhone = null
    }
}
