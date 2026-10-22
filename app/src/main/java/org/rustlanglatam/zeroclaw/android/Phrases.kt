package org.rustlanglatam.zeroclaw.android

import kotlin.random.Random

/** The assistant's conversational register, chosen by the owner, from minimal to familiar. */
enum class Warmth(@androidx.annotation.StringRes private val labelId: Int, @androidx.annotation.StringRes private val detailId: Int) {
    QUIET(R.string.warmth_quiet_label, R.string.warmth_quiet_detail),
    NATURAL(R.string.warmth_natural_label, R.string.warmth_natural_detail),
    CLOSE(R.string.warmth_close_label, R.string.warmth_close_detail),
    ;

    /** The level's name in the current language. */
    val label get() = t(labelId)
    /** A one-line description of the level in the current language. */
    val detail get() = t(detailId)
}

/**
 * Canned phrases for app-side events (resume, pause, app opened, pending approval, important
 * email), selected by [Warmth] without a model round trip. [pick] never returns the same phrase
 * twice in a row when an alternative exists. Also builds the system notes sent to the owner's agent.
 * Pure Kotlin, unit-tested on the JVM.
 */
object Phrases {

    private var last = ""

    /** Picks a random option, excluding the previously returned one when possible. */
    private fun pick(options: List<String>, random: Random): String {
        val fresh = options.filter { it != last }.ifEmpty { options }
        return fresh[random.nextInt(fresh.size)].also { last = it }
    }

    /** Returns the phrase spoken when the assistant resumes. */
    fun resumed(w: Warmth, name: String, random: Random = Random) = pick(
        when (w) {
            Warmth.QUIET -> listOf(t(R.string.phrases_resumed), t(R.string.phrases_active_again))
            Warmth.NATURAL -> listOf(t(R.string.phrases_i_m_back_x, name), t(R.string.phrases_here_i_am_again_shall_we), t(R.string.phrases_ready_again_tell_me_what_you))
            Warmth.CLOSE -> listOf(
                t(R.string.phrases_here_i_am_again_x_shall, name),
                t(R.string.phrases_i_m_back_what_shall_we),
                t(R.string.phrases_i_m_awake_tell_me_how),
            )
        },
        random,
    )

    /** Returns the phrase spoken when the assistant is paused. */
    fun paused(w: Warmth, name: String, random: Random = Random) = pick(
        when (w) {
            Warmth.QUIET -> listOf(t(R.string.phrases_paused))
            Warmth.NATURAL -> listOf(t(R.string.phrases_i_ll_stay_paused_let_me), t(R.string.phrases_all_right_paused))
            Warmth.CLOSE -> listOf(
                t(R.string.phrases_okay_i_ll_take_a_breather, name),
                t(R.string.phrases_done_i_ll_keep_still_call),
            )
        },
        random,
    )

    /** Returns a time-of-day greeting for when the owner opens the app, or `null` at [Warmth.QUIET]. */
    fun greeting(w: Warmth, name: String, hour: Int, random: Random = Random): String? {
        if (w == Warmth.QUIET) return null
        val part = when (hour) {
            in 6..11 -> t(R.string.phrases_good_morning)
            in 12..19 -> t(R.string.phrases_good_afternoon)
            else -> t(R.string.phrases_good_evening)
        }
        return pick(
            when (w) {
                Warmth.NATURAL -> listOf("$part, $name.", t(R.string.phrases_x_x_i_m_here, part, name))
                else -> when (hour) {
                    in 6..11 -> listOf(t(R.string.phrases_x_x_how_did_you_wake, part, name), t(R.string.phrases_x_shall_we_start_the_day, part))
                    in 12..19 -> listOf(t(R.string.phrases_hi_x_how_s_the_afternoon, name), t(R.string.phrases_x_x_how_s_everything, part, name))
                    else -> listOf(t(R.string.phrases_x_x_how_has_the_day, part, name), t(R.string.phrases_hi_x_it_s_late_if, name))
                }
            },
            random,
        )
    }

    /** Returns the prompt spoken for a pending approval while the app is open, or `null` at [Warmth.QUIET]. */
    fun approval(w: Warmth, what: String, random: Random = Random): String? = when (w) {
        Warmth.QUIET -> null
        Warmth.NATURAL -> t(R.string.phrases_x_do_you_approve, what)
        Warmth.CLOSE -> pick(listOf(t(R.string.phrases_hey_x_is_that_okay, what), t(R.string.phrases_one_little_thing_x_do_i, what)), random)
    }

    /** Returns the phrase spoken for an important email while the app is open, or `null` at [Warmth.QUIET]. */
    fun mail(w: Warmth, from: String, random: Random = Random): String? = when (w) {
        Warmth.QUIET -> null
        Warmth.NATURAL -> t(R.string.phrases_an_email_from_x_came_in, from)
        Warmth.CLOSE -> pick(listOf(t(R.string.phrases_hey_x_wrote_to_you_and, from), t(R.string.phrases_heads_up_an_email_from_x, from)), random)
    }

    /** Returns the instruction given to the owner's agent describing the selected register. */
    fun persona(w: Warmth) = when (w) {
        Warmth.QUIET -> "The owner chose the discreet register: what matters in one short sentence, no small talk."
        Warmth.NATURAL -> "The owner chose the natural register: warm and natural, like a good friend who is also very capable. One short " +
            "sentence for a task, two at most; as long as the conversation asks for when you are just talking."
        Warmth.CLOSE -> "The owner chose the close register: speak like a close friend: notice how they are, celebrate small wins, tease " +
            "gently when it fits. Still brief for a task. Never pushy or clingy."
    }

    /** Context about the owner's day included in the [welcome] note: followed topics, next commitment, reminders left. */
    data class Day(val topics: List<String> = emptyList(), val next: String? = null, val reminders: Int = 0)

    /** Prefix of every app-generated message to the agent, so it is never displayed as written by the owner. */
    const val NOTE = "(A note from the app"

    /** Returns [text] prefixed with [NOTE] unless it already starts with it. */
    fun fromApp(text: String) = if (text.startsWith(NOTE)) text else "$NOTE.) $text"

    /**
     * Builds the note sent to the owner's agent when the owner opens the app, asking it to greet
     * them in its own words using [day] and its memory, without tools.
     */
    fun welcome(owner: String, now: java.time.LocalDateTime, day: Day = Day()) =
        "(A note from the app, not from $owner: $owner just opened the app; it is " +
            now.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.forLanguageTag("es")) +
            " ${now.toLocalTime().withSecond(0).withNano(0)}." +
            (if (day.topics.isNotEmpty()) " They follow: ${day.topics.joinToString()}." else "") +
            (day.next?.let { " Their next commitment today is at $it." } ?: "") +
            (if (day.reminders > 0) " ${day.reminders} reminder(s) left today." else "") +
            ") Greet $owner in one or two warm, natural sentences, as a friend would after a few hours: pick up one thing " +
            "you know (from this note or what you remember of them: their day, a topic they follow, something pending) " +
            "and perhaps ask one light question. Never remark on how often or how late they open the app. Do not use " +
            "tools and do not mention this note."
}
