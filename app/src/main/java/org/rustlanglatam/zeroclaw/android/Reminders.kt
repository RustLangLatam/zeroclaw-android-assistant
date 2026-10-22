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

    data class Reminder(
        val id: Int, val text: String, val at: Long, val level: Level,
        /** Whether the reminder repeats daily at the same time. */
        val daily: Boolean = false,
        /** Number of unanswered calls made so far for a HIGH reminder. */
        val tries: Int = 0,
    )

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

    /** Returns the next occurrence of a daily reminder after [now], or `null` for a one-time reminder. */
    fun next(r: Reminder, now: Long): Long? {
        if (!r.daily) return null
        var at = r.at
        while (at <= now) at += 24 * 3600_000L
        return at
    }

    /** Returns when an unanswered HIGH reminder calls again, or `null` once [CALLS] is reached or for other levels. */
    fun again(r: Reminder, now: Long): Long? = if (r.level == Level.HIGH && r.tries + 1 < CALLS) now + AGAIN_MS else null

    fun encode(list: List<Reminder>): String = JSONArray(list.map {
        JSONObject().put("id", it.id).put("text", it.text).put("at", it.at).put("level", it.level.name).put("daily", it.daily).put("tries", it.tries)
    }).toString()

    fun decode(text: String): List<Reminder> = runCatching {
        val a = JSONArray(text)
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Reminder(o.getInt("id"), o.getString("text"), o.getLong("at"), Level.valueOf(o.getString("level")), o.optBoolean("daily"), o.optInt("tries"))
        }
    }.getOrDefault(emptyList())

    /** Renders the reminders, sorted by time, for the owner's agent. */
    fun describe(list: List<Reminder>, format: (Long) -> String): String =
        if (list.isEmpty()) "No reminders."
        else list.sortedBy { it.at }.joinToString("\n") { "#${it.id} ${format(it.at)}${if (it.daily) " every day" else ""} [${it.level.name.lowercase()}] ${it.text}" }
}
