package org.rustlanglatam.zeroclaw.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotesTest {

    @Test
    fun aNoteGoesToZeroClawAndComesBackWhole() {
        val note = Notes.Note("Vuelo a Lima", "18:45", until = 1_800_000_000_000)
        assertEquals(note, Notes.fromStore(note.key, Notes.content(note)))
        assertEquals("widget-vuelo-a-lima", note.key)
        val forever = Notes.Note("Cumpleaños de Ana", "mañana")
        assertEquals(forever, Notes.fromStore(forever.key, Notes.content(forever)))
    }

    @Test
    fun theSameTitleReplacesAndAnEndedNoteIsGone() {
        Notes.replace(emptyList())
        Notes.put(Notes.Note("Plazo", "viernes"))
        Notes.put(Notes.Note("plazo", "jueves"))
        assertEquals(listOf("jueves"), Notes.current().map { it.value })
        Notes.put(Notes.Note("Taxi", "en 10 min", until = 1_000))
        assertEquals(listOf("jueves"), Notes.current(now = 2_000).map { it.value })
        assertTrue(Notes.remove("PLAZO"))
        assertFalse(Notes.remove("plazo"))
    }
}
