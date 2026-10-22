package org.rustlanglatam.zeroclaw.android

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Checks a new commitment against the owner's calendar: existing events, clashes, and free slots to offer instead.
 * Pure Kotlin, unit-tested on the JVM; the calendar is read in [PhoneMcp].
 */
object Agenda {

    data class Busy(val title: String, val start: LocalDateTime, val end: LocalDateTime)

    private val hm = DateTimeFormatter.ofPattern("HH:mm")

    /** Returns the events overlapping the interval from [start] to [end]. */
    fun clashes(busy: List<Busy>, start: LocalDateTime, end: LocalDateTime) = busy.filter { it.start < end && it.end > start }

    /** Returns the free intervals of [day] between [from] and [to] that are at least [minutes] long. */
    fun free(busy: List<Busy>, day: LocalDate, from: LocalTime = LocalTime.of(8, 0), to: LocalTime = LocalTime.of(23, 0), minutes: Long = 30): List<Pair<LocalTime, LocalTime>> {
        var cursor = day.atTime(from)
        val end = day.atTime(to)
        val slots = mutableListOf<Pair<LocalTime, LocalTime>>()
        busy.sortedBy { it.start }.forEach { b ->
            if (b.start > cursor && java.time.Duration.between(cursor, minOf(b.start, end)).toMinutes() >= minutes) slots += cursor.toLocalTime() to minOf(b.start, end).toLocalTime()
            if (b.end > cursor) cursor = b.end
        }
        if (cursor < end && java.time.Duration.between(cursor, end).toMinutes() >= minutes) slots += cursor.toLocalTime() to to
        return slots
    }

    /** Renders a day's events and free slots for the owner's agent. */
    fun describe(day: LocalDate, busy: List<Busy>): String {
        val events = busy.sortedBy { it.start }.joinToString("\n") { "- ${it.start.format(hm)}-${it.end.format(hm)} ${it.title}" }.ifEmpty { "- nothing" }
        val gaps = free(busy, day).joinToString(", ") { (a, b) -> "${a.format(hm)}-${b.format(hm)}" }.ifEmpty { "none between 08:00 and 23:00" }
        return "The owner's calendar on $day (${day.dayOfWeek.name.lowercase()}):\n$events\nFree: $gaps"
    }

    /** Renders a clash for the agent, instructing it to ask the owner rather than decide. */
    fun conflict(title: String, start: LocalDateTime, clashes: List<Busy>): String =
        "\"$title\" at ${start.format(hm)} on ${start.toLocalDate()} clashes with " +
            clashes.joinToString(" and ") { "\"${it.title}\" (${it.start.format(hm)}-${it.end.format(hm)})" } +
            ". Do not decide: tell the owner, and ask whether to move or cancel that, or pick another time (offer their free time)."
}
