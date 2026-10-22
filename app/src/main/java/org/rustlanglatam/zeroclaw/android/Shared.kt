package org.rustlanglatam.zeroclaw.android

import java.time.LocalDate
import java.time.LocalTime

/**
 * Reads what the owner shares with the assistant (a photo of a bill, a doctor's appointment, a school circular): the
 * text is recognized on the phone ([ShareActivity]); this object finds in it, in code, a due date, an amount, a
 * time and whether it looks like a bill or an appointment, so the assistant can propose a reminder or a calendar
 * entry for the owner to confirm. Pure Kotlin, unit-tested on the JVM.
 */
object Shared {
    enum class Kind { BILL, APPOINTMENT, PARCEL, OTHER }

    /** What was found: the kind, the relevant date (the due date, or the appointment's), its time, and an amount as written. */
    data class Found(val kind: Kind, val date: LocalDate?, val time: LocalTime?, val amount: String?)

    private val numeric = Regex("""\b(\d{1,2})[/.\-](\d{1,2})[/.\-](\d{2,4})\b""")
    private val amountRe = Regex("""(?:(?:€|\$|eur|usd)\s?(\d{1,3}(?:[.,\s]\d{3})*(?:[.,]\d{2})?))|(?:(\d{1,3}(?:[.\s]\d{3})*(?:,\d{2})|\d+(?:\.\d{2})?)\s?(?:€|eur\b|euros\b|\$|usd\b|dolares\b|dollars\b))""", RegexOption.IGNORE_CASE)
    private val timeRe = Regex("""\b([01]?\d|2[0-3])[:.h]([0-5]\d)\b""")

    /** Returns every date in [text] (day first; month first only for English text when the day would be over 12), in order. */
    fun dates(text: String, today: LocalDate, english: Boolean = false): List<Pair<Int, LocalDate>> {
        val found = mutableListOf<Pair<Int, LocalDate>>()
        numeric.findAll(text).forEach { m ->
            var (a, b) = m.groupValues[1].toInt() to m.groupValues[2].toInt()
            if (english && a <= 12 && b > 12) a = b.also { b = a }
            val y = m.groupValues[3].toInt().let { if (it < 100) 2000 + it else it }
            runCatching { LocalDate.of(y, b, a) }.getOrNull()?.let { found += m.range.first to it }
        }
        val norm = Commands.normalize(text)
        (1..12).forEach { month ->
            val name = "(" + Lexicon["shared.month.$month"] + ")"
            // "28 de octubre de 2026", "28 octubre", "october 28, 2026", "oct 28"
            Regex("""\b(\d{1,2})\s+(?:de\s+)?$name\b(?:\s+(?:de\s+)?(\d{4}))?""").findAll(norm).forEach { m ->
                date(m.groupValues[1].toInt(), month, m.groupValues.last(), today)?.let { found += position(text, norm, m.range.first) to it }
            }
            Regex("""\b$name\s+(\d{1,2})(?:\s+(\d{4}))?\b""").findAll(norm).forEach { m ->
                date(m.groupValues[2].toInt(), month, m.groupValues[3], today)?.let { found += position(text, norm, m.range.first) to it }
            }
        }
        // "llega el jueves", "arrives on Thursday": the next such day (today included), only when no explicit date is
        // given; next to one ("jueves 30 de octubre") the day name is decoration.
        if (found.isNotEmpty()) return found.sortedBy { it.first }.distinctBy { it.second }
        (0..6).forEach { i ->
            Regex("\\b(" + Lexicon["routines.day.$i"] + ")\\b").findAll(norm).forEach { m ->
                val dow = java.time.DayOfWeek.of(if (i == 0) 7 else i)
                found += position(text, norm, m.range.first) to today.with(java.time.temporal.TemporalAdjusters.nextOrSame(dow))
            }
        }
        if (Regex("\\b(" + Lexicon["reminders.day_tomorrow"] + ")\\b").containsMatchIn(norm)) found += position(text, norm, 0) to today.plusDays(1)
        return found.sortedBy { it.first }.distinctBy { it.second }
    }

    // Normalizing keeps word positions roughly in place; good enough to order dates against the due words.
    private fun position(text: String, norm: String, at: Int) = (at.toLong() * text.length / norm.length.coerceAtLeast(1)).toInt()

    /** A date without a year is its next occurrence from [today]. */
    private fun date(day: Int, month: Int, year: String, today: LocalDate): LocalDate? = runCatching {
        if (year.isNotEmpty()) LocalDate.of(year.toInt(), month, day)
        else LocalDate.of(today.year, month, day).let { if (it.isBefore(today)) it.plusYears(1) else it }
    }.getOrNull()

    /** Returns the amount to pay as written ("45,20 €"): the one after a total word if any, else the largest. */
    fun amount(text: String): String? {
        val all = amountRe.findAll(text).map { it.value.trim() to it.range.first }.toList()
        if (all.isEmpty()) return null
        val total = Regex("(" + Lexicon["shared.bill"] + ")", RegexOption.IGNORE_CASE).findAll(Commands.normalize(text)).lastOrNull()?.range?.first
        fun value(s: String) = s.filter { it.isDigit() || it == ',' || it == '.' }.let { v ->
            val dec = v.lastIndexOfAny(charArrayOf(',', '.')).takeIf { it >= 0 && v.length - it == 3 }
            (if (dec == null) v.filter(Char::isDigit) else v.substring(0, dec).filter(Char::isDigit) + "." + v.substring(dec + 1)).toDoubleOrNull() ?: 0.0
        }
        return (total?.let { t -> all.firstOrNull { it.second >= t * text.length / Commands.normalize(text).length.coerceAtLeast(1) } } ?: all.maxBy { value(it.first) }).first
    }

    /** Reads [text]: the kind, the date that matters (the one after a due word, else the first upcoming), a time and an amount. */
    fun read(text: String, today: LocalDate, english: Boolean = false): Found {
        val norm = Commands.normalize(text)
        val amount = amount(text)
        val appointment = Regex("\\b(" + Lexicon["shared.appointment"] + ")").containsMatchIn(norm)
        val bill = amount != null && Regex("\\b(" + Lexicon["shared.bill"] + "|" + Lexicon["shared.due"] + ")").containsMatchIn(norm)
        val time = timeRe.find(text)?.let { LocalTime.of(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
        val dates = dates(text, today, english)
        val due = Regex("(" + Lexicon["shared.due"] + ")").find(norm)?.range?.first?.let { position(text, norm, it) }
        val date = (due?.let { d -> dates.firstOrNull { it.first >= d } } ?: dates.firstOrNull { !it.second.isBefore(today) } ?: dates.lastOrNull())?.second
        val parcel = Regex("\\b(" + Lexicon["shared.parcel"] + ")").containsMatchIn(norm) && Regex("\\b(" + Lexicon["shared.arrives"] + ")").containsMatchIn(norm)
        val kind = when {
            parcel && !bill -> Kind.PARCEL
            bill && !(appointment && time != null) -> Kind.BILL
            appointment -> Kind.APPOINTMENT
            else -> Kind.OTHER
        }
        return Found(kind, date, time, amount)
    }
}
