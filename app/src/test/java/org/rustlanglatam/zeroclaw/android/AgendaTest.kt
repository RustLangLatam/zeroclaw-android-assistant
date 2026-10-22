package org.rustlanglatam.zeroclaw.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class AgendaTest {

    private val day = LocalDate.of(2026, 10, 18)
    private val meeting = Agenda.Busy("Reunión con Laura", day.atTime(20, 30), day.atTime(21, 30))
    private val dentist = Agenda.Busy("Dentista", day.atTime(9, 0), day.atTime(10, 0))

    @Test
    fun dinnerAtNineClashesWithTheMeeting() {
        assertEquals(listOf(meeting), Agenda.clashes(listOf(dentist, meeting), day.atTime(21, 0), day.atTime(22, 30)))
        assertTrue(Agenda.clashes(listOf(dentist, meeting), day.atTime(19, 0), day.atTime(20, 30)).isEmpty())
        val why = Agenda.conflict("Cena con Ana", day.atTime(21, 0), listOf(meeting))
        assertTrue(why, why.startsWith("\"Cena con Ana\" at 21:00 on 2026-10-18 clashes with \"Reunión con Laura\" (20:30-21:30)"))
    }

    @Test
    fun theFreeTimeAroundWhatIsThere() {
        assertEquals(
            listOf(LocalTime.of(8, 0) to LocalTime.of(9, 0), LocalTime.of(10, 0) to LocalTime.of(20, 30), LocalTime.of(21, 30) to LocalTime.of(23, 0)),
            Agenda.free(listOf(meeting, dentist), day),
        )
        assertTrue(Agenda.describe(day, listOf(meeting)).endsWith("Free: 08:00-20:30, 21:30-23:00"))
    }
}
