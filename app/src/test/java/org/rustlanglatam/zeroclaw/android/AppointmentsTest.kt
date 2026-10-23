package org.rustlanglatam.zeroclaw.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppointmentsTest {

    private val m = 60_000L
    private val now = 1_000 * m
    private val dentist = Appointments.Event(1, "Dentista", now + 20 * m, "Calle Colón 12, Valencia")
    private val call = Appointments.Event(2, "Llamada", now + 20 * m, "")

    @Test
    fun timeToLeaveOnlyForEventsWithAnAddress() {
        assertEquals(listOf(dentist), Appointments.leaving(listOf(dentist, call), now, 30))
        assertTrue(Appointments.leaving(listOf(dentist), now, 15).isEmpty())
        assertTrue(Appointments.leaving(listOf(dentist.copy(begin = now - m)), now, 30).isEmpty())
    }

    @Test
    fun lowBatteryOnlyWhenLowAndNotCharging() {
        assertEquals(listOf(dentist, call), Appointments.lowBattery(listOf(dentist, call), now, 18, charging = false))
        assertTrue(Appointments.lowBattery(listOf(dentist), now, 18, charging = true).isEmpty())
        assertTrue(Appointments.lowBattery(listOf(dentist), now, 45, charging = false).isEmpty())
        assertTrue(Appointments.lowBattery(listOf(dentist.copy(begin = now + 90 * m)), now, 10, charging = false).isEmpty())
    }
}
