package org.rustlanglatam.zeroclaw.android

/** Localized display labels for policy capabilities, rules, message statuses and tool calls. Pure Kotlin, unit-tested on the JVM. */
object Labels {

    fun capability(capability: Capability) = when (capability) {
        Capability.ALARM_SET -> t(R.string.labels_set_alarms)
        Capability.CONTACTS_READ -> t(R.string.labels_read_your_contacts)
        Capability.CALENDAR_READ -> t(R.string.labels_read_your_calendar)
        Capability.MESSAGE_TAKE -> t(R.string.labels_take_messages)
        Capability.AVAILABILITY_SHARE -> t(R.string.labels_say_whether_you_re_free)
        Capability.MESSAGE_SEND -> t(R.string.labels_send_messages)
        Capability.NOTIFICATION_REPLY -> t(R.string.labels_answer_messages)
        Capability.CALENDAR_ACCEPT -> t(R.string.labels_accept_invitations)
        Capability.CONTACTS_EXPORT -> t(R.string.labels_export_contacts)
        Capability.APPS_READ -> t(R.string.labels_know_which_apps_you_have)
        Capability.APP_OPEN -> t(R.string.labels_open_apps_and_play_music)
        Capability.MEDIA_CONTROL -> t(R.string.labels_control_what_s_playing)
        Capability.MEMORY -> t(R.string.labels_remember_things_about_you)
        Capability.EMAIL_READ -> t(R.string.labels_read_your_email)
        Capability.EMAIL_DRAFT -> t(R.string.labels_write_emails_for_you)
        Capability.PHOTOS_READ -> t(R.string.labels_search_your_photos)
        Capability.CALENDAR_WRITE -> t(R.string.labels_write_in_your_calendar)
        Capability.FILES_READ -> t(R.string.labels_find_your_documents)
        Capability.REMINDERS -> t(R.string.labels_remind_you_of_things)
        Capability.NEWS -> t(R.string.labels_keep_you_up_to_date)
        Capability.INTERFACE -> t(R.string.labels_change_how_you_see_me)
        Capability.DEVICE -> t(R.string.labels_the_phone_s_controls)
        Capability.SETTINGS -> t(R.string.labels_my_own_settings)
        Capability.CONTACT_GROUPS -> t(R.string.labels_your_family_and_your_work)
        Capability.CHANNEL -> t(R.string.labels_read_more_or_give_you_less)
        Capability.ROUTINES -> t(R.string.labels_your_routines)
        Capability.RULES -> t(R.string.labels_change_what_i_may_do)
        Capability.MESSAGES_READ -> t(R.string.labels_read_you_your_messages)
        Capability.CONTACTS_WRITE -> t(R.string.labels_save_contacts)
        Capability.ITEMS_MANAGE -> t(R.string.labels_manage_emails_and_messages)
        Capability.ITEMS_DELETE -> t(R.string.labels_delete_emails_and_messages)
    }

    fun group(name: String) = when (name) {
        "family" -> t(R.string.labels_family)
        "work" -> t(R.string.labels_work)
        else -> name
    }

    /** Returns the localized label for a message status; [Inbox.Entry.status] stores one of [Inbox.Status]. */
    fun status(status: String) = when (status) {
        Inbox.Status.RECEIVED -> t(R.string.labels_received)
        Inbox.Status.ANSWERED -> t(R.string.labels_answered)
        Inbox.Status.WAITING -> t(R.string.labels_waiting)
        Inbox.Status.QUEUED -> t(R.string.labels_queued)
        Inbox.Status.PAUSED -> t(R.string.labels_paused)
        Inbox.Status.RETRYING -> t(R.string.labels_retrying)
        Inbox.Status.NO_ANSWER -> t(R.string.labels_no_answer)
        Inbox.Status.COULD_NOT_ANSWER -> t(R.string.labels_could_not_answer)
        Inbox.Status.UNDONE -> t(R.string.labels_undone)
        Inbox.Status.NOT_APPROVED -> t(R.string.labels_not_approved)
        Inbox.Status.HELD_BACK -> t(R.string.labels_held_back_paused)
        else -> status
    }

    fun scope(scope: Scope) = when (scope.kind) {
        Scope.Kind.ALL -> t(R.string.labels_everyone)
        Scope.Kind.GROUP -> group(scope.name)
        Scope.Kind.CONTACT -> scope.name
    }

    fun autonomy(autonomy: Autonomy) = when (autonomy) {
        Autonomy.NEVER -> t(R.string.labels_never)
        Autonomy.ASK -> t(R.string.labels_ask_you_first)
        Autonomy.ACT_NOTIFY -> t(R.string.labels_do_it_and_tell_you)
        Autonomy.ACT -> t(R.string.labels_do_it)
    }

    /** Returns a rule as a title (capability and scope) and a detail (autonomy and optional daily limit). */
    fun rule(rule: Rule) =
        "${capability(rule.capability)} · ${scope(rule.scope)}" to
            autonomy(rule.autonomy) + (rule.perDay?.let { t(R.string.labels_max_x_a_day, it) } ?: "")

    
    /** Returns the short label the chat shows for a running tool call, e.g. "Alarm · 07:00"; [name] may carry an MCP server prefix. */
    fun tool(name: String, args: org.json.JSONObject?): String {
        fun arg(key: String) = args?.optString(key).orEmpty()
        return when (name.substringAfterLast("__")) {
            "list_rules" -> t(R.string.labels_what_i_may_do)
            "list_messages" -> t(R.string.labels_messages)
            "act_on" -> arg("action").ifEmpty { t(R.string.labels_options) }.replaceFirstChar { it.uppercase() }
            "move_event" -> t(R.string.labels_move_event)
            "delete_event" -> t(R.string.labels_delete_event)
            "next_alarm" -> t(R.string.labels_next_alarm)
            "connect_email" -> t(R.string.labels_connect_an_email)
            "open_section" -> t(R.string.labels_open_x, arg("section"))
            "email_search" -> t(R.string.labels_email_search)
            "email_read" -> t(R.string.labels_email_read)
            "email_mark_read" -> t(R.string.labels_email_mark_read)
            "email_archive" -> t(R.string.labels_email_archive)
            "email_delete" -> t(R.string.labels_email_to_the_bin)
            "send_email" -> t(R.string.labels_email_x, arg("to"))
            "find_videos" -> t(R.string.labels_videos_x, arg("query"))
            "show" -> t(R.string.labels_showed_you_x, arg("title"))
            "cancel_alarm" -> t(R.string.labels_turn_off_alarm)
            "move_reminder" -> t(R.string.labels_move_reminder)
            "edit_contact" -> t(R.string.labels_change_contact_x, arg("name"))
            "delete_contact" -> t(R.string.labels_delete_contact_x, arg("name"))
            "add_contact" -> t(R.string.labels_new_contact_x, arg("name"))
            "set_rule" -> t(R.string.labels_change_a_rule)
            "create_alarm" -> t(R.string.labels_alarm_02d_02d).format(args?.optInt("hour") ?: 0, args?.optInt("minute") ?: 0)
            "search_contacts" -> t(R.string.labels_contacts_x, arg("query"))
            "list_events" -> t(R.string.labels_calendar)
            "send_message" -> t(R.string.labels_message_x, arg("to"))
            "list_apps" -> "Apps" + arg("kind").takeIf(String::isNotEmpty)?.let { " · $it" }.orEmpty()
            "open_app" -> t(R.string.labels_open_x, arg("app"))
            "play_media" -> t(R.string.labels_play_x, arg("query"))
            "media_control" -> t(R.string.labels_music_x, arg("action"))
            "remember" -> t(R.string.labels_remember) + arg("text").take(40)
            "forget" -> t(R.string.labels_forget) + arg("text").take(40)
            "recall" -> t(R.string.labels_memory)
            "how_well_i_know_you" -> t(R.string.labels_what_i_know_about_you)
            "list_emails" -> t(R.string.labels_email)
            "free_time" -> t(R.string.labels_agenda) + (arg("date").ifBlank { listOf(t(R.string.labels_today), t(R.string.labels_tomorrow))[args?.optInt("days_from_today")?.coerceIn(0, 1) ?: 0] })
            "add_event" -> t(R.string.labels_agenda) + arg("title")
            "remind" -> t(R.string.labels_reminder) + arg("text").take(40)
            "list_reminders" -> t(R.string.labels_reminders)
            "follow_topic" -> t(R.string.labels_follow) + arg("topic")
            "unfollow_topic" -> t(R.string.labels_stop_following) + arg("topic")
            "list_topics" -> t(R.string.labels_topics_i_follow)
            "news" -> t(R.string.labels_news) + arg("topic")
            "cancel_reminder" -> t(R.string.labels_reminder_remove)
            "find_files" -> t(R.string.labels_files) + arg("query")
            "find_photos" -> t(R.string.labels_photos) + arg("what").takeIf(String::isNotEmpty)?.let { " · $it" }.orEmpty()
            "read_email" -> t(R.string.labels_email_read)
            "draft_email" -> t(R.string.labels_email_2) + arg("to").ifBlank { t(R.string.labels_reply) }
            "device_setting" -> (Device.Setting.named(arg("setting"))?.label?.replaceFirstChar { it.uppercase() } ?: t(R.string.labels_setting)) + " · " + arg("value")
            "device_status" -> t(R.string.labels_phone_status)
            "weather" -> t(R.string.labels_weather)
            "set_preference" -> t(R.string.labels_setting_2) + (Preferences.Setting.named(arg("setting"))?.label ?: arg("setting")) + " · " + arg("value")
            "my_settings" -> t(R.string.labels_my_settings)
            "set_contact_group" -> arg("contact") + " · " + (mapOf("family" to t(R.string.labels_family), "work" to t(R.string.labels_work), "none" to t(R.string.labels_no_group))[arg("group")] ?: arg("group"))
            "set_mode" -> if (arg("mode") == "app") t(R.string.labels_app_mode) else t(R.string.labels_assistant_mode)
            "set_address" -> t(R.string.labels_address) + (mapOf("masculine" to t(R.string.labels_masculine), "feminine" to t(R.string.labels_feminine), "neutral" to t(R.string.labels_neutral))[arg("form")] ?: arg("form"))
            else -> name.substringAfterLast("__")
        }
    }
}
