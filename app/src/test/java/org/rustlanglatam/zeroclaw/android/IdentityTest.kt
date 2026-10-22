package org.rustlanglatam.zeroclaw.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IdentityTest {

    @Test
    fun aSenderIsKnownByTheirNumberAddressOrContactLink() {
        assertEquals(Identity.Clue.Number("+34600000002"), Identity.clue("tel:+34600000002", "Ana"))
        assertEquals(Identity.Clue.Number("+34600000002"), Identity.clue("34600000002@s.whatsapp.net", "Ana"))
        assertEquals(Identity.Clue.Email("ana@example.com"), Identity.clue("mailto:ana@example.com", "Ana"))
        assertEquals(Identity.Clue.Contact("content://com.android.contacts/contacts/lookup/x/7"), Identity.clue("content://com.android.contacts/contacts/lookup/x/7", "Ana"))
        // An SMS from an unsaved number shows the number as the name.
        assertEquals(Identity.Clue.Number("+34 600 000 002"), Identity.clue(null, "+34 600 000 002"))
    }

    @Test
    fun aNameAloneIsNoIdentity() {
        assertNull(Identity.clue(null, "Ana"))
        assertNull(Identity.clue("telegram-user-81273", "Ana"))
        assertNull(Identity.clue(null, "Ana 2024"))
        assertEquals("Ana (sin verificar)", Identity.named("Ana", null))
        assertEquals("Ana", Identity.named("Anita", "Ana"))
    }

    @Test
    fun anUnverifiedSenderIsNobodysFamily() {
        Policy.groups = mapOf("Ana" to setOf("family"))
        val target = Policy.target(Identity.named("Ana", null))
        assertEquals(Policy.target("Pat").groups, target.groups)
    }
}
