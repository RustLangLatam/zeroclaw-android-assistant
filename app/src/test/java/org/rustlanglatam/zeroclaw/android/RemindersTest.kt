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

    private val madrid = java.time.ZoneId.of("Europe/Madrid")
    private fun at(text: String) = java.time.LocalDateTime.parse(text).atZone(madrid).toInstant().toEpochMilli()
    private fun local(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(madrid).toLocalDateTime().toString()

    @Test
    fun everyEightHoursKeepsTheInterval() {
        val r = Reminders.Reminder(1, "Pastilla", at("2026-10-20T06:00"), Level.HIGH, repeat = Reminders.Repeat.Hours(8))
        assertEquals("2026-10-20T14:00", local(Reminders.next(r, at("2026-10-20T06:00"))!!))
        assertEquals("2026-10-21T06:00", local(Reminders.next(r, at("2026-10-20T22:30"), madrid)!!))
    }

    @Test
    fun weekdaysKeepTheHourAcrossTheClockChange() {
        // Mondays, Wednesdays and Fridays at 7; Spain leaves summer time on Sunday 2026-10-25.
        val days = setOf(java.time.DayOfWeek.MONDAY, java.time.DayOfWeek.WEDNESDAY, java.time.DayOfWeek.FRIDAY)
        val r = Reminders.Reminder(1, "Gimnasio", at("2026-10-21T07:00"), Level.NORMAL, repeat = Reminders.Repeat.Weekdays(days))
        assertEquals("2026-10-23T07:00", local(Reminders.next(r, at("2026-10-21T07:00"), madrid)!!))
        assertEquals("2026-10-26T07:00", local(Reminders.next(r, at("2026-10-23T07:00"), madrid)!!))
    }

    @Test
    fun aMonthlyDayPastTheMonthEndFallsOnItsLastDay() {
        val r = Reminders.Reminder(1, "Alquiler", at("2027-01-31T09:00"), Level.NORMAL, repeat = Reminders.Repeat.Monthly(31))
        assertEquals("2027-02-28T09:00", local(Reminders.next(r, at("2027-01-31T09:00"), madrid)!!))
        assertEquals("2027-03-31T09:00", local(Reminders.next(r, at("2027-02-28T09:00"), madrid)!!))
    }

    @Test
    fun repetitionsAndConfirmationsAreSavedAndDescribed() {
        val list = listOf(
            Reminders.Reminder(3, "Pastilla", 5, Level.HIGH, repeat = Reminders.Repeat.Hours(8), doneAt = listOf(7, 9)),
            Reminders.Reminder(4, "Factura", 6, Level.NORMAL, repeat = Reminders.Repeat.Monthly(5)),
        )
        assertEquals(list, Reminders.decode(Reminders.encode(list)))
        assertEquals(
            "#3 t every 8 h [high] Pastilla; confirmed: t, t\n#4 t monthly on day 5 [normal] Factura",
            Reminders.describe(list) { "t" },
        )
    }

    @Test
    fun weekdaysAreReadInBothLanguages() {
        val mwf = setOf(java.time.DayOfWeek.MONDAY, java.time.DayOfWeek.WEDNESDAY, java.time.DayOfWeek.FRIDAY)
        assertEquals(mwf, Reminders.Repeat.weekdays("lunes, miércoles y viernes"))
        assertEquals(mwf, Reminders.Repeat.weekdays("mon,wed,fri"))
        assertEquals(mwf, Reminders.Repeat.weekdays("1,3,5"))
        assertEquals(5, Reminders.Repeat.weekdays("weekdays")?.size)
        assertNull(Reminders.Repeat.weekdays("whenever"))
    }

    @Test
    fun theFirstTimeFallsOnAnAllowedDay() {
        val bill = Reminders.Reminder(1, "Luz", at("2026-10-23T09:00"), Level.NORMAL, repeat = Reminders.Repeat.Monthly(5))
        assertEquals("2026-11-05T09:00", local(Reminders.first(bill, madrid)))
        val gym = Reminders.Reminder(2, "Gimnasio", at("2026-10-23T07:00"), Level.NORMAL, repeat = Reminders.Repeat.Weekdays(setOf(java.time.DayOfWeek.FRIDAY)))
        assertEquals("2026-10-23T07:00", local(Reminders.first(gym, madrid)))
        val pill = Reminders.Reminder(3, "Pastilla", at("2026-10-23T22:00"), Level.HIGH, repeat = Reminders.Repeat.Hours(8))
        assertEquals("2026-10-23T22:00", local(Reminders.first(pill, madrid)))
    }
}
