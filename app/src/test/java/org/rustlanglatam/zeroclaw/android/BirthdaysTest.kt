package org.rustlanglatam.zeroclaw.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.MonthDay

class BirthdaysTest {

    @Test
    fun contactDatesWithAndWithoutYear() {
        assertEquals(MonthDay.of(3, 14), Birthdays.parse("Ana", "1990-03-14")?.day)
        assertEquals(MonthDay.of(3, 14), Birthdays.parse("Ana", "--03-14")?.day)
        assertNull(Birthdays.parse("Ana", "soon"))
    }

    @Test
    fun upcomingSoonestFirstAcrossTheYearEnd() {
        val all = listOf(Birthdays.Birthday("Ana", MonthDay.of(1, 2)), Birthdays.Birthday("Laura", MonthDay.of(12, 30)), Birthdays.Birthday("Pat", MonthDay.of(6, 1)))
        val got = Birthdays.upcoming(all, LocalDate.of(2026, 12, 29), 7)
        assertEquals(listOf("Laura" to LocalDate.of(2026, 12, 30), "Ana" to LocalDate.of(2027, 1, 2)), got.map { it.first.name to it.second })
    }

    @Test
    fun a29FebruaryFallsOnThe28thInOtherYears() {
        val got = Birthdays.upcoming(listOf(Birthdays.Birthday("Ana", MonthDay.of(2, 29))), LocalDate.of(2027, 2, 27), 2)
        assertEquals(LocalDate.of(2027, 2, 28), got.single().second)
    }
}
