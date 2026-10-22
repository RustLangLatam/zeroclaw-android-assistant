package org.rustlanglatam.zeroclaw.android

/**
 * Builds the rotating status line shown under the greeting on the home screen: the assistant's state
 * first when relevant, then incoming messages and mail, upcoming events and reminders, news, and a
 * question about the owner. Pure Kotlin, unit-tested on the JVM; the home screen animates the rotation.
 */
object Panorama {

    enum class Kind(@androidx.annotation.StringRes private val labelId: Int) {
        STATE(R.string.panorama_state), BACK(R.string.panorama_back), MESSAGE(R.string.panorama_message), MAIL(R.string.panorama_mail),
        AGENDA(R.string.panorama_agenda), REMINDER(R.string.panorama_reminder), NEWS(R.string.panorama_news), CURIOUS(R.string.panorama_curious),
        ;

        val label get() = t(labelId)
    }

    /** One line. Tapping it opens [place], or sends [ask] to the agent, or otherwise speaks [text] aloud. */
    data class Item(val kind: Kind, val text: String, val place: Int? = null, val ask: String? = null)

    /** A snapshot of the current state, already read from the phone, used to build the lines. */
    data class Now(
        val stopped: Boolean = false,
        val offline: Boolean = false,
        val arranging: Boolean = false,
        val attention: Boolean = false,
        /** Approvals that expired without an answer and still wait for the owner. */
        val waiting: Int = 0,
        val back: String? = null,
        val unread: List<Pair<String, String>> = emptyList(),
        /** Sender, subject, and the prompt to send to the agent about it. */
        val importantMail: Triple<String, String, String>? = null,
        val mails: Int = 0,
        val next: Pair<String, String>? = null,
        val reminder: Pair<String, String>? = null,
        val briefing: Pair<String, String>? = null,
        val curious: String? = null,
    )

    fun items(n: Now): List<Item> = buildList {
        when {
            n.arranging -> return listOf(Item(Kind.STATE, t(R.string.panorama_hold_and_drag_to_arrange)))
            n.stopped -> add(Item(Kind.STATE, t(R.string.panorama_i_m_paused_i_ll_do)))
            n.offline -> add(Item(Kind.STATE, t(R.string.panorama_no_internet_i_read_what_comes)))
            n.attention -> add(Item(Kind.STATE, t(R.string.panorama_there_s_something_i_want_to)))
        }
        if (n.waiting > 0) add(Item(Kind.STATE, tn(R.plurals.waiting_for_your_decision, n.waiting, n.waiting), Place.ACTIVITY))
        n.back?.let { add(Item(Kind.BACK, it)) }
        n.importantMail?.let { (from, subject, ask) -> add(Item(Kind.MAIL, t(R.string.panorama_x_wrote_something_important_x, from, subject), ask = ask)) }
        when (n.unread.size) {
            0 -> {}
            1 -> n.unread[0].let { (from, text) -> add(Item(Kind.MESSAGE, "$from: «$text»", Place.ACTIVITY)) }
            else -> add(Item(Kind.MESSAGE, t(R.string.panorama_x_new_messages_from, n.unread.size) + n.unread.map { it.first }.distinct().joinToString(", "), Place.ACTIVITY))
        }
        n.reminder?.let { (time, text) -> add(Item(Kind.REMINDER, t(R.string.panorama_at_x_x, time, text))) }
        n.next?.let { (time, title) -> add(Item(Kind.AGENDA, t(R.string.panorama_at_x_x, time, title), ask = t(R.string.panorama_what_do_i_have_today_and))) }
        n.briefing?.let { (topic, text) -> add(Item(Kind.NEWS, "$topic · $text", Place.NEWS)) }
        if (n.mails > 0 && n.importantMail == null) add(Item(Kind.MAIL, if (n.mails == 1) t(R.string.panorama_one_unread_email) else t(R.string.panorama_x_unread_emails, n.mails)))
        n.curious?.let { add(Item(Kind.CURIOUS, it)) }
        if (isEmpty()) add(Item(Kind.STATE, t(R.string.panorama_all_calm_tap_the_circle_if)))
    }

    /** Returns a question about the first memory area with no known facts, skipping [Memory.Area.HEALTH]; `null` when all are known. */
    fun curious(known: (Memory.Area) -> Memory.Depth): String? =
        Memory.Area.KNOWN.firstOrNull { it != Memory.Area.HEALTH && known(it) == Memory.Depth.NONE }
            ?.let { area ->
                when (area) {
                    Memory.Area.PERSONAL -> t(R.string.panorama_i_still_know_little_about_you)
                    Memory.Area.PEOPLE -> t(R.string.panorama_i_don_t_know_your_people)
                    Memory.Area.WORK -> t(R.string.panorama_i_don_t_know_what_you)
                    Memory.Area.TASTES -> t(R.string.panorama_i_don_t_know_what_music)
                    Memory.Area.ROUTINES -> t(R.string.panorama_i_don_t_know_what_your)
                    Memory.Area.INTERESTS -> t(R.string.panorama_i_don_t_know_which_topics)
                    Memory.Area.GOALS -> t(R.string.panorama_i_don_t_know_your_plans)
                    else -> t(R.string.panorama_i_don_t_know_your_places)
                }
            }
}
