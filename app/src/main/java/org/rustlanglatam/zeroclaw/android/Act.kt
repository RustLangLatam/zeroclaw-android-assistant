package org.rustlanglatam.zeroclaw.android

/**
 * Describes an action subject to the policy: [log] is the activity-log line (technical English), [title] and [detail]
 * are the localized text shown in approvals, undo countdowns and notices, and [ref] is the activity entry it answers.
 * The log text is never parsed back, so rewording it does not change behavior.
 */
data class Act(val log: String, val title: String, val detail: String? = null, val ref: Int? = null) {
    override fun toString() = log

    companion object {
        fun reply(entry: Int, to: String, app: String?, text: String) =
            Act("reply #$entry to $to" + (app?.let { " via $it" } ?: "") + ": $text", t(R.string.act_reply_to_x, to) + (app?.let { t(R.string.act_on_x, it) } ?: ""), text, ref = entry)
        fun alarm(hour: Int, minute: Int) = "%02d:%02d".format(hour, minute).let { Act("alarm $it", t(R.string.act_set_an_alarm_for_x, it)) }
        fun message(to: String, text: String) = Act("message to $to: $text", t(R.string.act_send_a_message_to_x, to), text)
        fun listApps(kind: String?) = Act("list apps" + (kind?.let { " ($it)" } ?: ""), t(R.string.act_see_your_apps) + (kind?.let { t(R.string.act_for_x, it) } ?: ""))
        fun openApp(app: String) = Act("open app $app", t(R.string.act_open_x, app))
        fun play(query: String, app: String) = Act("play \"$query\" in $app", t(R.string.act_play_x_in_x, query, app))
        fun media(action: String) = Act("media $action", t(R.string.act_music_x, action))
        fun listEvents(day: Any) = Act("list events of $day", t(R.string.act_see_your_calendar_for_x, day))
        fun addEvent(title: String, day: Any, start: Any) = Act("add event \"$title\" $day $start", t(R.string.act_put_x_in_your_calendar, title), "$day $start")
        fun remind(level: Reminders.Level, text: String, at: String) =
            Act("remind [${level.name.lowercase()}] $text at $at", t(R.string.act_reminder) + " (${mapOf("low" to t(R.string.act_low), "normal" to "normal", "high" to t(R.string.act_high)).getValue(level.name.lowercase())}) · $at", text)
        fun follow(topic: String) = Act("follow \"$topic\"", t(R.string.act_follow_x_for_you, topic))
        fun unfollow(topic: String) = Act("unfollow $topic", t(R.string.act_stop_following_x, topic))
        fun listTopics() = Act("list topics", t(R.string.act_see_the_topics_i_follow_for))
        fun openSection(section: String) = Act("open section $section", t(R.string.act_open_x_for_you, section))
        fun connectEmail(provider: String?) = Act("connect email ${provider.orEmpty()}".trim(), t(R.string.act_connect_an_email_account) + (provider?.let { " · $it" }.orEmpty()))
        fun emailMark(n: Int, read: Boolean) = Act("mark $n email(s) ${if (read) "read" else "unread"}", if (read) t(R.string.act_mark_x_email_s_read, n) else t(R.string.act_mark_x_email_s_unread, n))
        fun emailArchive(n: Int) = Act("archive $n email(s)", t(R.string.act_archive_x_email_s, n))
        fun emailDelete(n: Int) = Act("delete $n email(s)", t(R.string.act_move_x_email_s_to_the, n))
        fun sendEmail(to: String, subject: String, body: String, from: String) = Act("email from $from to $to: $subject", t(R.string.act_send_an_email_to_x_from, to, from), if (subject.isBlank()) body else "$subject\n\n$body")
        fun findVideos(query: String) = Act("find videos \"$query\"", t(R.string.act_look_for_videos_of_x, query))
        fun news(topic: String) = Act("news \"$topic\"", t(R.string.act_read_the_news_about_x, topic))
        fun listReminders() = Act("list reminders", t(R.string.act_see_your_reminders))
        fun cancelReminder(id: Int) = Act("cancel reminder #$id", t(R.string.act_remove_a_reminder))
        fun findFiles(query: String) = Act("find files $query", t(R.string.act_search_for_x, query))
        fun findPhotos(period: String, what: String) = Act("find photos $period $what".trim(), t(R.string.act_search_photos), what.ifBlank { null })
        fun listEmails() = Act("list emails", t(R.string.act_see_your_email))
        fun readEmail(id: Int, from: String) = Act("read email #$id from $from", t(R.string.act_read_the_email_from_x, from))
        fun email(to: String, subject: String) = Act("email to $to: $subject", t(R.string.act_write_an_email_to_x, to), subject)
        fun remember(fact: String) = Act("remember: $fact", t(R.string.act_remember), fact)
        fun forget(what: String) = Act("forget: $what", t(R.string.act_forget), what)
        fun device(setting: Device.Setting, value: String) = Act("device: ${setting.name.lowercase()} $value", t(R.string.act_change_x, setting.label), value.ifBlank { null })
        fun setting(setting: Preferences.Setting, value: String) = Act("setting: ${setting.name.lowercase()} $value", t(R.string.act_change_x, setting.label), value.ifBlank { null })
        fun mySettings() = Act("my settings", t(R.string.act_see_my_settings))
        fun group(contact: String, group: String) = Act(
            "group: $contact → $group",
            when (group) { "family" -> t(R.string.act_put_x_in_your_family, contact); "work" -> t(R.string.act_put_x_in_your_work_group, contact); else -> t(R.string.act_take_x_out_of_your_groups, contact) },
        )
        fun deviceStatus() = Act("device status", t(R.string.act_see_the_phone_s_status))
        fun weather() = Act("weather", t(R.string.act_see_the_weather))
        fun moveEvent(title: String, to: java.time.LocalDateTime) = Act("move event \"$title\" to $to", t(R.string.act_move_x, title), "$to")
        fun deleteEvent(title: String) = Act("delete event \"$title\"", t(R.string.act_delete_x_from_your_calendar, title))
        fun nextAlarm() = Act("next alarm", t(R.string.act_see_your_next_alarm))
        fun cancelAlarm(hour: Int, minute: Int) = Act("cancel alarm %02d:%02d".format(hour, minute), t(R.string.act_turn_off_the_02d_02d_alarm).format(hour, minute))
        fun editContact(name: String) = Act("edit contact $name", t(R.string.act_change_the_contact_x, name))
        fun deleteContact(name: String) = Act("delete contact $name", t(R.string.act_delete_the_contact_x, name))
        fun press(what: String, button: String) = Act("press $button on $what", t(R.string.act_x_x, button, what))
        fun addContact(name: String, phone: String) = Act("add contact $name $phone", t(R.string.act_save_a_contact), "$name · $phone")
        fun listMessages() = Act("list messages", t(R.string.act_read_you_your_messages))
        fun listRules() = Act("list rules", t(R.string.act_see_what_i_may_do))
        fun rule(title: String, autonomy: Autonomy) = Act("rule $title -> $autonomy", t(R.string.act_change_a_rule), "$title → ${Labels.autonomy(autonomy)}")
        fun mode(app: Boolean) = Act("mode: ${if (app) "app" else "assistant"}", if (app) t(R.string.act_switch_to_app_mode) else t(R.string.act_back_to_assistant_mode))
        fun address(form: String) = Act(
            "address: $form",
            t(R.string.act_how_to_address_you) + (mapOf("masculine" to t(R.string.act_in_the_masculine), "feminine" to t(R.string.act_in_the_feminine), "neutral" to t(R.string.act_without_gender))[form] ?: form),
        )
        fun recall() = Act("recall", t(R.string.act_see_what_i_know_about_you))
        fun routine(what: String, days: String, time: String) = Act("routine \"$what\" $days $time", t(R.string.act_routine_x, what), "$days · $time")
        fun listRoutines() = Act("list routines", t(R.string.act_see_your_routines))
        fun usage() = Act("usage", t(R.string.act_see_what_i_cost))
        fun showOnHome(title: String, value: String) = Act("show on home: $title · $value", t(R.string.act_keep_in_sight_x, title), value)
        fun show(title: String) = Act("show: $title", t(R.string.act_show_you_x, title))
        fun removeFromHome(title: String) = Act("remove from home: $title", t(R.string.act_remove_from_your_home_x, title))
        fun removeRoutine(id: String) = Act("remove routine #$id", t(R.string.act_remove_a_routine))
        fun searchContacts(query: String) = Act("search contacts \"$query\"", t(R.string.act_search_x_in_your_contacts, query))
        fun messageFrom(from: String, text: String) = Act("message from $from: $text", t(R.string.act_message_from_x, from), text)
        fun availability(hour: Int, from: String) = Act("availability at $hour:00 for $from", t(R.string.act_tell_x_whether_you_re_free, from, hour))
    }
}
