package org.rustlanglatam.zeroclaw.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.rustlanglatam.zeroclaw.android.Reminders.Level

class RemindersTest {

    @Test
    fun aMedicineIsAlwaysHigh() {
        assertEquals(Level.HIGH, Reminders.level("Tomar la pastilla de la presión", Level.LOW))
        assertEquals(Level.HIGH, Reminders.level("Medicamento de Ana", null))
        assertEquals(Level.LOW, Reminders.level("Regar las plantas", Level.LOW))
        assertEquals(Level.NORMAL, Reminders.level("Llamar al banco", null))
        assertEquals(Level.HIGH, Reminders.parseLevel("alta"))
        assertNull(Reminders.parseLevel("urgentísimo"))
    }

    @Test
    fun aHighOneCallsAgainUntilAnswered() {
        val r = Reminders.Reminder(1, "Medicina", 1_000, Level.HIGH)
        assertEquals(1_000 + Reminders.AGAIN_MS, Reminders.again(r, 1_000))
        assertNull(Reminders.again(r.copy(tries = Reminders.CALLS - 1), 1_000))
        assertNull(Reminders.again(r.copy(level = Level.NORMAL), 1_000))
    }

    @Test
    fun dailyComesBackTomorrow() {
        val day = 24 * 3600_000L
        val r = Reminders.Reminder(1, "Medicina", 8 * 3600_000L, Level.HIGH, daily = true)
        assertEquals(8 * 3600_000L + day, Reminders.next(r, 9 * 3600_000L))
        assertNull(Reminders.next(r.copy(daily = false), 9 * 3600_000L))
    }

    @Test
    fun savedAndDescribed() {
        val list = listOf(Reminders.Reminder(2, "Medicina", 5, Level.HIGH, daily = true, tries = 1))
        assertEquals(list, Reminders.decode(Reminders.encode(list)))
        assertEquals("#2 08:00 every day [high] Medicina", Reminders.describe(list) { "08:00" })
    }
}
