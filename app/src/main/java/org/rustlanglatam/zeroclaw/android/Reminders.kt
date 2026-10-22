package org.rustlanglatam.zeroclaw.android

import org.json.JSONArray
import org.json.JSONObject

/**
 * Reminder model and the parsing of spoken requests. Each reminder has a [Level]: LOW posts a silent notice; NORMAL posts
 * a sounding notification and is spoken if the app is open; HIGH shows a full-screen incoming-call screen that rings,
 * vibrates and speaks the reminder (not a telephone call), repeating every [AGAIN_MS] until acknowledged.
 *
 * Health-related reminders (medication) are always raised to HIGH in code, regardless of the requested level. Pure Kotlin,
 * unit-tested on the JVM; [PhoneReminders] schedules and fires them.
 */
object Reminders {
    /**
     * Maps an explicit Spanish or English request ("remind me to take the pill at 10 at night", "remind me in 5
     * minutes…", "set an alarm at 7:30", and simple calendar, weather, open-app and alarm queries) to an owner tool name
     * and its arguments, so the app can run it without the model.
     *
     * Used when offline, where the local model has been observed to acknowledge a reminder without calling the tool.
     *
     * @return `null` when the words are not such a request or the time is ambiguous.
     */
    fun asked(words: String): Pair<String, org.json.JSONObject>? {
        val w = words.trim().lowercase().replace(Regex(Lexicon["reminders.address"]), "")
        val time = spoken(w)
        val inMinutes = Regex(Lexicon["reminders.in_minutes"]).find(w)
        val minutes = inMinutes?.let { m ->
            val n = when (m.groupValues[1]) { in Lexicon.words("reminders.number_one") -> 1.0; in Lexicon.words("reminders.number_half") -> 0.5; else -> m.groupValues[1].toDouble() }
            (n * if (m.groupValues[2].startsWith("h")) 60 else 1).toLong().takeIf { it > 0 }
        }
        // Calendar queries are answered by the app directly; the local model tended to echo the question.
        Regex(Lexicon["reminders.agenda_query"]).find(w)?.let { m ->
            val day = (m.groupValues[1] + m.groupValues[2]).let { when (it) { in Lexicon.words("reminders.day_tomorrow") -> 1; in Lexicon.words("reminders.day_after_tomorrow") -> 2; else -> 0 } }
            return "list_events" to org.json.JSONObject().put("days_from_today", day)
        }
        // Weather is answered from the last cached reading; the local model took minutes for it on low-end hardware.
        if (Regex(Lexicon["reminders.weather_query"]).containsMatchIn(w))
            return "weather" to org.json.JSONObject()
        Regex(Lexicon["reminders.open_app"]).find(w)?.let { m ->
            return "open_app" to org.json.JSONObject().put("app", m.groupValues[1].trim())
        }
        if (Regex(Lexicon["reminders.list_query"]).containsMatchIn(w))
            return "list_reminders" to org.json.JSONObject()
        // Next-alarm queries are read from the system; offline, the local model invented a time.
        if (Regex(Lexicon["reminders.alarm_any"]).containsMatchIn(w) && Regex(Lexicon["reminders.alarm_question"]).containsMatchIn(w) &&
            !Regex(Lexicon["reminders.alarm_command"]).containsMatchIn(w))
            return "next_alarm" to org.json.JSONObject()
        val alarm = Regex(Lexicon["reminders.alarm"]).containsMatchIn(w) && Regex(Lexicon["reminders.alarm_set"]).containsMatchIn(w)
        if (alarm) return time?.let { "create_alarm" to org.json.JSONObject().put("hour", it.hour).put("minute", it.minute) }
        val remind = Regex(Lexicon["reminders.remind"]).find(w) ?: return null
        if (time == null && minutes == null) return null
        // The task text is what remains after removing the time expressions.
        var task = remind.groupValues[1]
        task = task.replace(Regex(Lexicon["reminders.time_strip"]), " ")
        inMinutes?.let { task = task.replace(it.value, " ") }
        val tomorrow = Regex(Lexicon["reminders.tomorrow"]).containsMatchIn(w)
        task = task.replace(Regex(Lexicon["reminders.day_strip"]), " ")
            .replace(Regex("""\s+"""), " ").trim().trim(',', '.', ' ').let { Lexicon.words("reminders.task_prefixes").fold(it) { s, p -> s.removePrefix(p) } }.trim()
        if (task.isEmpty()) return null
        val args = org.json.JSONObject().put("text", task.replaceFirstChar { it.uppercase() })
        if (minutes != null && time == null) args.put("minutes_from_now", minutes)
        else args.put("time", "%02d:%02d".format(time!!.hour, time.minute)).also { if (tomorrow) args.put("days_from_today", 1) }
        return "remind" to args
    }

    /**
     * Parses an explicit time of day from spoken Spanish or English: "at 10 at night" (22:00), "at half past 7 in the
     * morning", "at a quarter to 9", "at 9 pm", "at 21:30"; returns `null` when there is none.
     *
     * When the model's tool call carries a different time, this parse takes precedence, since small models have
     * mis-converted times such as "10 at night" to 20:00.
     */
    fun spoken(words: String): java.time.LocalTime? {
        val w = words.lowercase()
        val m = Regex(Lexicon["reminders.time"]).find(w) ?: return null
        var h = m.groupValues[1].toInt()
        var min = m.groupValues[2].ifEmpty { "0" }.toInt()
        when (m.groupValues[3]) { in Lexicon.words("reminders.time_half") -> min = 30; in Lexicon.words("reminders.time_quarter") -> min = 15 }
        if (Lexicon.words("reminders.time_quarter_to").any { it in m.value }) { h -= 1; min = 45 }
        val part = m.groupValues[5]
        val ampm = m.groupValues[6].replace(".", "").replace(" ", "")
        when {
            part in Lexicon.words("reminders.part_afternoon") || ampm == "pm" -> if (h in 1..11) h += 12
            (part in Lexicon.words("reminders.part_morning") || ampm == "am") && h == 12 -> h = 0
            part in Lexicon.words("reminders.part_night") && h == 12 -> h = 0
        }
        return if (h in 0..23 && min in 0..59) java.time.LocalTime.of(h, min) else null
    }

    /**
     * Parses a time of day as a model writes it ("21:00", "9:30", "21", "9 pm"), tolerating trailing garbage such as
     * `20:00', `; returns `null` when there is none.
     */
    fun clock(text: String): java.time.LocalTime? {
        val m = Regex("""(\d{1,2})(?::(\d{2}))?\s*([ap])?\.?\s*m?\.?""", RegexOption.IGNORE_CASE).find(text) ?: return null
        var h = m.groupValues[1].toInt()
        val min = m.groupValues[2].ifEmpty { "0" }.toInt()
        when (m.groupValues[3].lowercase()) { "p" -> if (h < 12) h += 12; "a" -> if (h == 12) h = 0 }
        return if (h in 0..23 && min in 0..59) java.time.LocalTime.of(h, min) else null
    }


    enum class Level { LOW, NORMAL, HIGH }

    /** How a reminder repeats, besides [Reminder.daily]: every N hours, on some weekdays, or monthly on a day. */
    sealed interface Repeat {
        /** Every [hours] hours from the first time (1 to 24), regardless of the clock: for medicines. */
        data class Hours(val hours: Int) : Repeat
        /** On these days of the week, at the first time's hour and minute. */
        data class Weekdays(val days: Set<java.time.DayOfWeek>) : Repeat
        /** On this day of each month (1 to 31; the last day in a shorter month), at the first time's hour and minute. */
        data class Monthly(val day: Int) : Repeat

        /** Compact form kept in `reminders.json`: `h8`, `w1,3,5` (ISO days, Monday 1), `m5`. */
        fun code(): String = when (this) {
            is Hours -> "h$hours"
            is Weekdays -> "w" + days.map { it.value }.sorted().joinToString(",")
            is Monthly -> "m$day"
        }

        companion object {
            fun of(code: String?): Repeat? = runCatching {
                when (code?.firstOrNull()) {
                    'h' -> Hours(code.drop(1).toInt().also { require(it in 1..24) })
                    'w' -> Weekdays(code.drop(1).split(',').map { java.time.DayOfWeek.of(it.trim().toInt()) }.toSet().also { require(it.isNotEmpty()) })
                    'm' -> Monthly(code.drop(1).toInt().also { require(it in 1..31) })
                    else -> null
                }
            }.getOrNull()

            /** Parses weekdays as a model or the owner writes them: "mon,wed,fri", "lunes y viernes", "1,3,5", "weekdays". */
            fun weekdays(text: String): Set<java.time.DayOfWeek>? {
                val t = Commands.normalize(text)
                if (Regex(Lexicon["routines.weekdays"]).containsMatchIn(t)) return (1..5).map { java.time.DayOfWeek.of(it) }.toSet()
                if (Regex(Lexicon["routines.weekends"]).containsMatchIn(t)) return setOf(java.time.DayOfWeek.SATURDAY, java.time.DayOfWeek.SUNDAY)
                val days = mutableSetOf<java.time.DayOfWeek>()
                Regex("[1-7]").findAll(t).forEach { days += java.time.DayOfWeek.of(it.value.toInt()) }
                // routines.day.0 is Sunday … routines.day.6 Saturday, as in Routines.
                (0..6).forEach { i ->
                    if (Regex("\\b(" + Lexicon["routines.day.$i"] + ")\\b").containsMatchIn(t)) days += java.time.DayOfWeek.of(if (i == 0) 7 else i)
                }
                return days.takeIf { it.isNotEmpty() }
            }
        }
    }

    data class Reminder(
        val id: Int, val text: String, val at: Long, val level: Level,
        /** Whether the reminder repeats daily at the same time. */
        val daily: Boolean = false,
        /** Number of unanswered calls made so far for a HIGH reminder. */
        val tries: Int = 0,
        /** Any other repetition; `null` for a one-time (or [daily]) reminder. */
        val repeat: Repeat? = null,
        /** When the owner confirmed it (epoch millis), latest last, at most [DONE_KEPT]: "did I take the 2 o'clock one?". */
        val doneAt: List<Long> = emptyList(),
    ) {
        val repeats get() = daily || repeat != null
    }

    /** Number of confirmations kept per reminder. */
    const val DONE_KEPT = 20

    /** Interval between repeated calls of an unanswered HIGH reminder, up to [CALLS] calls. */
    const val AGAIN_MS = 5 * 60_000L
    const val CALLS = 6

    private val HEALTH = Regex(
        Lexicon["reminders.health"],
    )

    /** Returns the requested level (NORMAL by default), forced to HIGH when [text] mentions medication. */
    fun level(text: String, asked: Level?): Level =
        if (HEALTH.containsMatchIn(Commands.normalize(text))) Level.HIGH else asked ?: Level.NORMAL

    fun parseLevel(word: String?): Level? = when (word?.lowercase()?.trim()) {
        in Lexicon.words("reminders.level_low") -> Level.LOW
        in Lexicon.words("reminders.level_normal") -> Level.NORMAL
        in Lexicon.words("reminders.level_high") -> Level.HIGH
        else -> null
    }

    /**
     * Returns the next occurrence of a repeating reminder after [now], or `null` for a one-time reminder. Daily, weekday
     * and monthly reminders keep their hour and minute in [zone] across daylight-saving changes; every-N-hours reminders
     * keep a fixed interval. A monthly day past a short month's end falls on its last day.
     */
    fun next(r: Reminder, now: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): Long? {
        val first = java.time.Instant.ofEpochMilli(r.at).atZone(zone)
        val time = first.toLocalTime()
        val today = java.time.Instant.ofEpochMilli(maxOf(now, r.at)).atZone(zone).toLocalDate()
        fun at(date: java.time.LocalDate) = date.atTime(time).atZone(zone).toInstant().toEpochMilli()
        return when (val repeat = r.repeat ?: if (r.daily) Repeat.Weekdays(java.time.DayOfWeek.entries.toSet()) else null) {
            null -> null
            is Repeat.Hours -> {
                val step = repeat.hours * 3600_000L
                if (r.at > now) r.at else r.at + ((now - r.at) / step + 1) * step
            }
            is Repeat.Weekdays -> (0L..7L).map { today.plusDays(it) }.first { it.dayOfWeek in repeat.days && at(it) > now }.let(::at)
            is Repeat.Monthly -> (0L..2L).map { java.time.YearMonth.from(today).plusMonths(it) }
                .map { it.atDay(minOf(repeat.day, it.lengthOfMonth())) }.first { at(it) > now }.let(::at)
        }
    }

    /**
     * Returns the first time of [r]: its own time, moved for weekday and monthly repetitions to the first allowed day
     * at that time (the same day when it is allowed).
     */
    fun first(r: Reminder, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): Long =
        if (r.repeat is Repeat.Weekdays || r.repeat is Repeat.Monthly) next(r, r.at - 1, zone) ?: r.at else r.at

    /** The repetition in plain words for the agent: "every day", "every 8 h", "on MONDAY, FRIDAY", "monthly on day 5". */
    fun every(r: Reminder): String = when (val repeat = r.repeat) {
        null -> if (r.daily) "every day" else ""
        is Repeat.Hours -> "every ${repeat.hours} h"
        is Repeat.Weekdays -> "on " + repeat.days.sorted().joinToString(", ") { it.name.lowercase() }
        is Repeat.Monthly -> "monthly on day ${repeat.day}"
    }

    /** Returns when an unanswered HIGH reminder calls again, or `null` once [CALLS] is reached or for other levels. */
    fun again(r: Reminder, now: Long): Long? = if (r.level == Level.HIGH && r.tries + 1 < CALLS) now + AGAIN_MS else null

    fun encode(list: List<Reminder>): String = JSONArray(list.map {
        JSONObject().put("id", it.id).put("text", it.text).put("at", it.at).put("level", it.level.name).put("daily", it.daily).put("tries", it.tries)
            .put("every", it.repeat?.code()).put("done", JSONArray(it.doneAt))
    }).toString()

    fun decode(text: String): List<Reminder> = runCatching {
        val a = JSONArray(text)
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Reminder(
                o.getInt("id"), o.getString("text"), o.getLong("at"), Level.valueOf(o.getString("level")), o.optBoolean("daily"), o.optInt("tries"),
                Repeat.of(o.optString("every").takeIf { o.has("every") }), o.optJSONArray("done")?.let { d -> (0 until d.length()).map { d.getLong(it) } }.orEmpty(),
            )
        }
    }.getOrDefault(emptyList())

    /** Renders the reminders, sorted by time, for the owner's agent. */
    fun describe(list: List<Reminder>, format: (Long) -> String): String =
        if (list.isEmpty()) "No reminders."
        else list.sortedBy { it.at }.joinToString("\n") { r ->
            "#${r.id} ${format(r.at)}" + every(r).let { if (it.isEmpty()) "" else " $it" } + " [${r.level.name.lowercase()}] ${r.text}" +
                (if (r.doneAt.isEmpty()) "" else "; confirmed: " + r.doneAt.takeLast(5).joinToString(", ") { format(it) })
        }
}
