package org.rustlanglatam.zeroclaw.android

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.crypto.KeyGenerator

class VaultTest {
    private fun newKey() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @After fun noKey() { Vault.key = null }

    @Test fun sealedOpensOnlyWithThisPhonesKey() {
        Vault.key = newKey()
        val sealed = Vault.seal("Milton toma su pastilla a las 8")
        assertTrue(sealed.startsWith("ks1:"))
        assertTrue("pastilla" !in sealed)
        assertEquals("Milton toma su pastilla a las 8", Vault.unseal(sealed))
        assertNotEquals(Vault.seal("x"), Vault.seal("x"))
        Vault.key = newKey()
        assertTrue(runCatching { Vault.unseal(sealed) }.isFailure)
    }

    @Test fun plainFilesFromBeforeAreReadAndSealedOnWrite() {
        Vault.key = newKey()
        val f = File.createTempFile("vault", ".txt").apply { writeText("{\"a\":1}") }
        assertEquals("{\"a\":1}", f.readSealed())
        f.writeSealed("{\"a\":2}")
        assertTrue(f.readText().startsWith("ks1:"))
        assertEquals("{\"a\":2}", f.readSealed())
        f.writeText("vieja\n"); f.appendSealed("nueva")
        assertEquals(listOf("vieja", "nueva"), f.readSealedLines())
        assertTrue(f.readLines().all { it.startsWith("ks1:") })
        f.delete(); f.appendSealed("uno"); f.appendSealed("dos")
        assertEquals(listOf("uno", "dos"), f.readSealedLines())
        f.delete()
    }
}
