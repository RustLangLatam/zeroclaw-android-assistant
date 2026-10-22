package org.rustlanglatam.zeroclaw.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class AbsenceTest {

    private val zone = ZoneOffset.UTC
    private fun ms(t: String) = LocalDateTime.parse(t).toInstant(zone).toEpochMilli()

    @Test
    fun whySheWasAway() {
        val now = ms("2026-10-17T10:00")
        assertNull("a restart is not an absence", Absence.after(now - 60_000, 0, now))
        assertNull("the first start", Absence.after(0, 0, now))
        val closed = Absence.after(ms("2026-10-17T08:00"), ms("2026-10-01T00:00"), now)!!
        assertEquals(Absence.Kind.CLOSED, closed.kind)
        assertEquals(Absence.Kind.PHONE_OFF, Absence.after(ms("2026-10-17T08:00"), ms("2026-10-17T09:58"), now)!!.kind)
        assertNull("a short drop", Absence.offline(now - 60_000, now))
        assertEquals(Absence.Kind.OFFLINE, Absence.offline(ms("2026-10-17T09:30"), now)!!.kind)
    }

    @Test
    fun whatSheSaysAtOnce() {
        val now = ms("2026-10-17T10:00")
        assertEquals(
            "Ya estoy de vuelta, Milton. El teléfono estuvo apagado desde ayer a las 22:05; déjame ver qué pasó mientras no estaba.",
            Absence.back(Absence.Away(Absence.Kind.PHONE_OFF, ms("2026-10-16T22:05"), now), "Milton", zone),
        )
        assertTrue(Absence.back(Absence.Away(Absence.Kind.OFFLINE, ms("2026-10-17T09:30"), now), "Milton", zone).contains("sin internet desde las 9:30"))
        assertEquals("el martes a las 8:00", Absence.since(Absence.Away(Absence.Kind.CLOSED, ms("2026-10-13T08:00"), now), zone))
        Language.chosen = "en"
        try {
            assertEquals("Tuesday at 8:00", Absence.since(Absence.Away(Absence.Kind.CLOSED, ms("2026-10-13T08:00"), now), zone))
            assertEquals("yesterday at 22:05", Absence.since(Absence.Away(Absence.Kind.CLOSED, ms("2026-10-16T22:05"), now), zone))
        } finally { Language.chosen = null }
    }

    @Test
    fun byPriority() {
        val events = listOf(
            Absence.Event(Absence.NOTED, "Email from Pat"),
            Absence.Event(Absence.URGENT, "Reminder: pastilla"),
            Absence.Event(Absence.WAITING, "Message from Ana"),
        )
        assertEquals("Mientras no estaba: Reminder: pastilla; Message from Ana; Email from Pat.", Absence.plain(events))
        val ask = Absence.ask("Milton", Absence.Away(Absence.Kind.OFFLINE, ms("2026-10-17T09:30"), ms("2026-10-17T10:00")), events, zone)
        assertTrue(ask, ask.indexOf("pastilla") < ask.indexOf("Ana") && ask.indexOf("Ana") < ask.indexOf("Pat"))
        assertTrue(ask, "no internet" in ask && "Do not use tools" in ask)
        assertEquals("No pasó nada importante mientras no estaba.", Absence.plain(emptyList()))
    }
}
