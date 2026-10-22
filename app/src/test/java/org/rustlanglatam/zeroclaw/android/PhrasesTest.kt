package org.rustlanglatam.zeroclaw.android

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PhrasesTest {

    @Test
    fun theCloserTheWarmer() {
        assertTrue(Phrases.resumed(Warmth.QUIET, "Milton") in listOf("Reanudada.", "Activa de nuevo."))
        assertTrue(Phrases.resumed(Warmth.CLOSE, "Milton", Random(1)).length > 25)
        assertNull(Phrases.greeting(Warmth.QUIET, "Milton", 9))
        assertTrue(Phrases.greeting(Warmth.NATURAL, "Milton", 22)!!.startsWith("Buenas noches, Milton"))
    }

    @Test
    fun neverTheSameTwiceInARow() {
        val random = Random(7)
        val first = Phrases.resumed(Warmth.CLOSE, "Milton", random)
        assertNotEquals(first, Phrases.resumed(Warmth.CLOSE, "Milton", random))
    }

    @Test
    fun theWelcomeIsHerOwn() {
        val note = Phrases.welcome("Milton", java.time.LocalDateTime.of(2026, 10, 17, 9, 5, 30))
        assertTrue(note, note.startsWith("(A note from the app, not from Milton: Milton just opened the app; it is sábado 09:05.)"))
        assertTrue(note, note.endsWith("Do not use tools and do not mention this note."))
    }
}
