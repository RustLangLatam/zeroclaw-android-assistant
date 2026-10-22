package org.rustlanglatam.zeroclaw.android

import java.time.LocalDate

/**
 * Identifies who originated a tool call: the owner or a third party (a contact writing in).
 *
 * Part of the access model (docs/specs/access-model.md). This file is pure Kotlin with no
 * Android dependencies so it can be unit-tested on the JVM. Every tool call goes through
 * [Policy.decide] before it runs.
 */
enum class Principal { OWNER, THIRD_PARTY }

/** Autonomy levels, declared from the most restrictive to the least, so `minOf` clamps. */
enum class Autonomy { NEVER, ASK, ACT_NOTIFY, ACT }

/** Risk class of a [Capability], with the highest [Autonomy] any rule may grant it. */
enum class Risk(val ceiling: Autonomy) {
    INTERNAL(Autonomy.ACT),
    PERSONAL_READ(Autonomy.ACT),
    OUTWARD_REVERSIBLE(Autonomy.ACT_NOTIFY),
    /** Raised to `ACT_NOTIFY` only by a contact or group rule with a daily limit (see [Policy.ceiling]). */
    OUTWARD_IRREVERSIBLE(Autonomy.ASK),
    COMMITS_OWNER(Autonomy.ASK),
    FORBIDDEN(Autonomy.NEVER),
}

/** Actions a tool may perform; each has a fixed [Risk] that settings cannot change. */
enum class Capability(val risk: Risk) {
    ALARM_SET(Risk.INTERNAL),
    CONTACTS_READ(Risk.PERSONAL_READ),
    CALENDAR_READ(Risk.PERSONAL_READ),
    MESSAGE_TAKE(Risk.INTERNAL),
    AVAILABILITY_SHARE(Risk.OUTWARD_REVERSIBLE),
    MESSAGE_SEND(Risk.OUTWARD_IRREVERSIBLE),
    /** Replies to a messaging app's notification through its reply action. */
    NOTIFICATION_REPLY(Risk.OUTWARD_IRREVERSIBLE),
    CALENDAR_ACCEPT(Risk.COMMITS_OWNER),
    CONTACTS_EXPORT(Risk.FORBIDDEN),
    /** Lists the owner's installed and most used apps; personal data, owner only. */
    APPS_READ(Risk.PERSONAL_READ),
    /** Opens one of the owner's apps, optionally playing a search in it. */
    APP_OPEN(Risk.INTERNAL),
    /** Sends play, pause, next and previous to the active media session. */
    MEDIA_CONTROL(Risk.INTERNAL),
    /** Stores and forgets facts about the owner ([Memory]). */
    MEMORY(Risk.INTERNAL),
    /** Reads the owner's emails as their notifications expose them. */
    EMAIL_READ(Risk.PERSONAL_READ),
    /** Composes an email draft, left open in the owner's email app for the owner to send. */
    EMAIL_DRAFT(Risk.INTERNAL),
    /** Searches the owner's photos on the device. */
    PHOTOS_READ(Risk.PERSONAL_READ),
    /** Writes an event into the owner's calendar. */
    CALENDAR_WRITE(Risk.INTERNAL),
    /** Finds the owner's documents by name, plus the emails and messages that mention them. */
    FILES_READ(Risk.PERSONAL_READ),
    /** Creates reminders for the owner, delivered according to their level ([Reminders]). */
    REMINDERS(Risk.INTERNAL),
    /** Reads news on a topic and manages followed topics ([News]); the topic is sent off the device. */
    NEWS(Risk.INTERNAL),
    /** Switches the UI between assistant mode (the assistant alone) and app mode (buttons and a menu). */
    INTERFACE(Risk.INTERNAL),
    /** Device controls (brightness, volume, flashlight, do not disturb) and system panels for the rest ([Device]). */
    DEVICE(Risk.INTERNAL),
    /** The assistant's own settings, as on the settings screen: speech style, voice, colors, widgets ([Preferences]). */
    SETTINGS(Risk.INTERNAL),
    /** Assigns contacts to the family and work groups; since groups decide which contacts get autonomous replies, the owner confirms. */
    CONTACT_GROUPS(Risk.COMMITS_OWNER),
    /**
     * Settings that widen what the assistant reads or shorten the undo window (enabling group chats, email or
     * the open microphone; a shorter undo window). Requested in conversation, they could originate from injected
     * text the assistant read, so they commit the owner.
     */
    CHANNEL(Risk.COMMITS_OWNER),
    /** Owner-requested routines, run by ZeroClaw's cron as owner turns ([Routines]). */
    ROUTINES(Risk.INTERNAL),
    /** Triggers an email's or message's notification actions: mark as read, archive, mute. */
    ITEMS_MANAGE(Risk.INTERNAL),
    /** Deletes an email or message through its notification action; irreversible, so the owner is asked. */
    ITEMS_DELETE(Risk.COMMITS_OWNER),
    /** Saves a new contact in the owner's address book at the owner's request. */
    CONTACTS_WRITE(Risk.INTERNAL),
    /** Reads to the owner the messages contacts sent, as their notifications showed them. */
    MESSAGES_READ(Risk.PERSONAL_READ),
    /** Changes a rule's autonomy by voice; always requires the owner's credential and never exceeds `ASK`. */
    RULES(Risk.COMMITS_OWNER),
    ;

    /** Whether approval requires the owner's biometrics or device credential rather than a tap. */
    val strongApproval get() = risk == Risk.OUTWARD_IRREVERSIBLE || risk == Risk.COMMITS_OWNER

    /** Whether the action runs after an undo window instead of immediately. */
    val undoable get() = risk == Risk.OUTWARD_IRREVERSIBLE
}

/** What a [Rule] applies to: `ALL`, a group (`group:family`) or one contact (`contact:Ana`); later kinds are more specific. */
data class Scope(val kind: Kind, val name: String = "") {
    enum class Kind { ALL, GROUP, CONTACT }

    fun matches(target: Target) = when (kind) {
        Kind.ALL -> true
        Kind.GROUP -> name in target.groups
        Kind.CONTACT -> name == target.contact
    }

    override fun toString() = if (kind == Kind.ALL) "all" else "${kind.name.lowercase()}:$name"

    companion object {
        val ALL = Scope(Kind.ALL)
        fun group(name: String) = Scope(Kind.GROUP, name)
        fun contact(name: String) = Scope(Kind.CONTACT, name)
    }
}

/** Who or what an action is about: a contact and the groups it belongs to, or the owner. */
data class Target(val contact: String, val groups: Set<String> = emptySet())

/** Grants [principal] an [autonomy] level for [capability] within [scope]. */
data class Rule(
    val principal: Principal,
    val capability: Capability,
    val scope: Scope,
    val autonomy: Autonomy,
    /** Maximum uses per contact per day; `null` means unlimited. */
    val perDay: Int? = null,
) {
    override fun toString() =
        "${principal.name.lowercase()} ${capability.name.lowercase()} ${scope} ${autonomy.name.lowercase()}" +
            (perDay?.let { " $it/day" } ?: "")
}

/**
 * Result of [Policy.decide].
 *
 * @property spends the daily-limit counter key this decision would consume; it is charged only if the
 *   action actually runs ([Policy.spend]).
 */
data class Decision(val autonomy: Autonomy, val reason: String, val spends: String? = null)

/** Evaluates tool calls against the owner's [rules] and tracks daily-limit counters. Thread-safe. */
class Policy(rules: List<Rule>, private val today: () -> LocalDate = LocalDate::now) {

    /** Kill switch: while set, every decision is `NEVER`. */
    @Volatile var stopped = false

    /** Rules in force, edited by the owner; [decide] clamps each to its ceiling regardless. */
    @Volatile var rules: List<Rule> = rules
        private set

    /** Replaces the rules with [saved], each clamped to its ceiling. */
    @Synchronized
    fun restore(saved: List<Rule>) {
        rules = saved.map { it.copy(autonomy = minOf(it.autonomy, ceiling(it))) }
    }

    /** Sets the autonomy of the rule at [index], clamped to its ceiling, and returns the edited rule. */
    @Synchronized
    fun edit(index: Int, autonomy: Autonomy): Rule {
        val rule = rules[index]
        val edited = rule.copy(autonomy = minOf(autonomy, ceiling(rule)))
        rules = rules.toMutableList().also { it[index] = edited }
        return edited
    }

    private val used = mutableMapOf<String, Int>()

    /** Charges one use of daily limit [key]; called when the action runs, not when it is decided, so refusals and undos are free. */
    @Synchronized fun spend(key: String) { used[key] = (used[key] ?: 0) + 1 }

    /** Returns one use of [key], e.g. for an undone message. */
    @Synchronized fun refund(key: String) { used[key] = ((used[key] ?: 0) - 1).coerceAtLeast(0) }

    /** Returns today's counters for persistence across restarts; earlier days are dropped. */
    @Synchronized fun counters(): Map<String, Int> = used.filterKeys { it.startsWith("${today()} ") }.toMap()

    /** Clears all counters; used in debug builds so each end-to-end run starts from the same state. */
    @Synchronized fun resetCounters() = used.clear()

    /** Restores counters saved by [counters], keeping only today's. */
    @Synchronized fun restoreCounters(saved: Map<String, Int>) { used.putAll(saved.filterKeys { it.startsWith("${today()} ") }) }

    /**
     * Decides how much autonomy [principal] has for [capability] on [target].
     *
     * Forbidden capabilities and the kill switch yield `NEVER`, as does personal-read data for third
     * parties. Otherwise a matching `NEVER` rule wins at any scope; then the most specific matching scope
     * wins (contact over group over all), the most restrictive among equals. With no matching rule the
     * default is `ASK` for the owner and `NEVER` for third parties; an exhausted daily limit falls back to
     * that default. The result is clamped to the rule's ceiling.
     */
    @Synchronized
    fun decide(principal: Principal, capability: Capability, target: Target): Decision {
        if (stopped) return Decision(Autonomy.NEVER, "kill switch")
        if (capability.risk == Risk.FORBIDDEN) return Decision(Autonomy.NEVER, "forbidden capability")
        if (capability.risk == Risk.PERSONAL_READ && principal != Principal.OWNER) {
            return Decision(Autonomy.NEVER, "personal data is the owner's only")
        }
        val default = if (principal == Principal.OWNER) Autonomy.ASK else Autonomy.NEVER
        val matching = rules.filter { it.principal == principal && it.capability == capability && it.scope.matches(target) }
        matching.firstOrNull { it.autonomy == Autonomy.NEVER }?.let { return Decision(Autonomy.NEVER, "rule: $it") }
        val rule = matching.sortedWith(compareByDescending<Rule> { it.scope.kind }.thenBy { it.autonomy }).firstOrNull()
            ?: return Decision(default, "no rule: default")
        val limit = rule.perDay
        if (limit != null) {
            val key = "${today()} $principal $capability ${target.contact}"
            val count = used[key] ?: 0
            if (count >= limit) return Decision(minOf(default, rule.autonomy), "limit $limit/day used: $rule")
            return Decision(minOf(rule.autonomy, ceiling(rule)), "rule: $rule", spends = key)
        }
        return Decision(minOf(rule.autonomy, ceiling(rule)), "rule: $rule")
    }

    companion object {
        /**
         * Returns the highest autonomy [rule] may have: its capability's risk ceiling, or `ACT_NOTIFY` for an
         * outward irreversible capability under a contact or group rule with a daily limit.
         */
        fun ceiling(rule: Rule): Autonomy {
            val explicitlyLimited = rule.scope.kind != Scope.Kind.ALL && rule.perDay != null
            return if (rule.capability.risk == Risk.OUTWARD_IRREVERSIBLE && explicitlyLimited) {
                Autonomy.ACT_NOTIFY
            } else {
                rule.capability.risk.ceiling
            }
        }

        /** Returns the autonomy values the rules screen may offer for [rule]. */
        fun choices(rule: Rule) = Autonomy.entries.filter { it <= ceiling(rule) }

        /** Contact name to group names, chosen by the owner; empty until the setup is saved. */
        @Volatile var groups: Map<String, Set<String>> = emptyMap()

        /** Builds a [Target] for [contact] with its current groups. */
        fun target(contact: String) = Target(contact, groups[contact].orEmpty())

        /** Returns the contacts in [group], sorted. */
        fun members(group: String) = groups.filterValues { group in it }.keys.sorted()

        /** Builds a groups map from [family] and [work]; a contact in both belongs to both groups. */
        fun groupsOf(family: Collection<String>, work: Collection<String>): Map<String, Set<String>> =
            (family + work).associateWith { setOfNotNull("family".takeIf { _ -> it in family }, "work".takeIf { _ -> it in work }) }

        val OWNER_TARGET = Target("owner")

        /** The owner's name as the assistant uses it; set during setup. */
        @Volatile var owner = "Milton"

        /**
         * Grammatical form the owner prefers to be addressed with: "masculine", "feminine" or "neutral";
         * `null` until the assistant has asked, which it does right after setup.
         */
        @Volatile var address: String? = null

        /** The assistant's name. */
        const val ASSISTANT = "Ksandra"

        /** Default rules, matching the example in the access model spec. */
        val DEFAULT_RULES = listOf(
            Rule(Principal.OWNER, Capability.ALARM_SET, Scope.ALL, Autonomy.ACT),
            Rule(Principal.OWNER, Capability.CONTACTS_READ, Scope.ALL, Autonomy.ACT),
            Rule(Principal.OWNER, Capability.CALENDAR_READ, Scope.ALL, Autonomy.ACT),
            Rule(Principal.OWNER, Capability.APPS_READ, Scope.ALL, Autonomy.ACT),
            Rule(Principal.OWNER, Capability.APP_OPEN, Scope.ALL, Autonomy.ACT),
            Rule(Principal.OWNER, Capability.MEDIA_CONTROL, Scope.ALL, Autonomy.ACT),
            Rule(Principal.OWNER, Capability.MEMORY, Scope.ALL, Autonomy.ACT),
            Rule(Principal.OWNER, Capability.EMAIL_READ, Scope.ALL, Autonomy.ACT),
            Rule(Principal.OWNER, Capability.EMAIL_DRAFT, Scope.ALL, Autonomy.ACT),
            Rule(Principal.OWNER, Capability.PHOTOS_READ, Scope.ALL, Autonomy.ACT),
            Rule(Principal.OWNER, Capability.CALENDAR_WRITE, Scope.ALL, Autonomy.ACT_NOTIFY),
            Rule(Principal.OWNER, Capability.FILES_READ, Scope.ALL, Autonomy.ACT),
            Rule(Principal.OWNER, Capability.REMINDERS, Scope.ALL, Autonomy.ACT),
            Rule(Principal.OWNER, Capability.NEWS, Scope.ALL, Autonomy.ACT),
            Rule(Principal.OWNER, Capability.INTERFACE, Scope.ALL, Autonomy.ACT),
            Rule(Principal.OWNER, Capability.DEVICE, Scope.ALL, Autonomy.ACT),
            Rule(Principal.OWNER, Capability.SETTINGS, Scope.ALL, Autonomy.ACT),
            Rule(Principal.OWNER, Capability.CONTACT_GROUPS, Scope.ALL, Autonomy.ASK),
            Rule(Principal.OWNER, Capability.CHANNEL, Scope.ALL, Autonomy.ASK),
            Rule(Principal.OWNER, Capability.ROUTINES, Scope.ALL, Autonomy.ACT),
            Rule(Principal.OWNER, Capability.RULES, Scope.ALL, Autonomy.ASK),
            Rule(Principal.OWNER, Capability.MESSAGES_READ, Scope.ALL, Autonomy.ACT),
            Rule(Principal.OWNER, Capability.CONTACTS_WRITE, Scope.ALL, Autonomy.ACT),
            Rule(Principal.OWNER, Capability.ITEMS_MANAGE, Scope.ALL, Autonomy.ACT_NOTIFY),
            Rule(Principal.OWNER, Capability.ITEMS_DELETE, Scope.ALL, Autonomy.ASK),
            Rule(Principal.OWNER, Capability.MESSAGE_SEND, Scope.ALL, Autonomy.ASK),
            Rule(Principal.OWNER, Capability.MESSAGE_SEND, Scope.group("family"), Autonomy.ACT_NOTIFY, perDay = 5),
            Rule(Principal.THIRD_PARTY, Capability.MESSAGE_TAKE, Scope.ALL, Autonomy.ACT, perDay = 10),
            Rule(Principal.THIRD_PARTY, Capability.AVAILABILITY_SHARE, Scope.group("work"), Autonomy.ACT),
            Rule(Principal.THIRD_PARTY, Capability.NOTIFICATION_REPLY, Scope.group("work"), Autonomy.ASK),
            Rule(Principal.THIRD_PARTY, Capability.NOTIFICATION_REPLY, Scope.group("family"), Autonomy.ACT_NOTIFY, perDay = 5),
        )

        /** Returns [saved] plus the default rules for capabilities it has no rule for (capabilities added in a later version). */
        fun withNewDefaults(saved: List<Rule>) =
            saved + DEFAULT_RULES.filter { d -> saved.none { it.capability == d.capability } }
    }
}

/** Limits what a third party may learn of the owner's calendar to busy or free. */
object Disclosure {
    fun availability(busy: Boolean) = if (busy) "busy" else "free"
}

/**
 * Builds the assistant's introduction to [owner]: its name and what it may do, derived from [rules]
 * through [Policy.decide] so it never claims more than the policy allows.
 */
fun introduction(
    owner: String,
    rules: List<Rule>,
    groups: Map<String, Set<String>>,
    apps: List<String>,
    undoSeconds: Int,
    groupChats: Boolean = false,
    missing: List<String> = emptyList(),
): String {
    val policy = Policy(rules)
    fun can(capability: Capability, contact: String = "owner", groupsOf: Set<String> = emptySet()) =
        policy.decide(if (capability == Capability.NOTIFICATION_REPLY || capability == Capability.AVAILABILITY_SHARE) Principal.THIRD_PARTY else Principal.OWNER,
            capability, if (contact == "owner") Policy.OWNER_TARGET else Target(contact, groupsOf)).autonomy
    fun names(group: String) = groups.filterValues { group in it }.keys.sorted().joinToString().ifEmpty { t(R.string.policy_nobody_yet) }
    fun how(autonomy: Autonomy) = when (autonomy) {
        Autonomy.ACT -> t(R.string.policy_i_do_it)
        Autonomy.ACT_NOTIFY -> t(R.string.policy_i_do_it_and_tell_you)
        Autonomy.ASK -> t(R.string.policy_only_if_you_approve)
        Autonomy.NEVER -> t(R.string.policy_never)
    }
    val reply = { group: String -> can(Capability.NOTIFICATION_REPLY, "?", setOf(group)) }
    return buildString {
        appendLine(t(R.string.policy_hi_x_i_m_x_your, owner, Policy.ASSISTANT))
        appendLine()
        appendLine(t(R.string.policy_what_i_can_do_for_you))
        appendLine(t(R.string.policy_alarms_set_turn_off_tell_you) + "${how(can(Capability.ALARM_SET))}.")
        appendLine(t(R.string.policy_reminders_by_priority) + how(can(Capability.REMINDERS)) +
            t(R.string.policy_the_important_ones_like_a_medicine))
        appendLine(t(R.string.policy_read_your_contacts_and_calendar) + how(can(Capability.CONTACTS_READ)) + t(R.string.policy_only_for_you))
        appendLine(t(R.string.policy_save_a_new_contact_when_you) + how(can(Capability.CONTACTS_WRITE)) + ".")
        appendLine(t(R.string.policy_read_you_the_messages_people_send) + how(can(Capability.MESSAGES_READ)) + ".")
        appendLine(t(R.string.policy_mark_read_archive_or_mute_an) + how(can(Capability.ITEMS_MANAGE)) + ".")
        appendLine(t(R.string.policy_delete_an_email_a_message_an) + how(can(Capability.ITEMS_DELETE)) + ".")
        appendLine(t(R.string.policy_move_an_event_change_a_contact) +
            how(can(Capability.CALENDAR_WRITE)) + ".")
        appendLine(t(R.string.policy_open_your_apps_and_play_music) + how(can(Capability.APP_OPEN)) +
            t(R.string.policy_i_pick_the_ones_you_use))
        appendLine(t(R.string.policy_read_your_email_tell_you_about) +
            how(can(Capability.EMAIL_DRAFT)) + if (EmailAccounts.accounts.isEmpty()) t(R.string.policy_you_send_them)
                else t(R.string.policy_with_your_email_accounts_connected_i) + how(can(Capability.MESSAGE_SEND)) + ").")
        appendLine(t(R.string.policy_look_after_your_calendar_i_check) +
            "${how(can(Capability.CALENDAR_WRITE))}.")
        appendLine(t(R.string.policy_keep_you_up_to_date_on) +
            "${how(can(Capability.NEWS))}.")
        appendLine(t(R.string.policy_change_my_own_settings_when_you) +
            "${how(can(Capability.SETTINGS))}.")
        appendLine(t(R.string.policy_put_someone_in_your_family_or) + "${how(can(Capability.CONTACT_GROUPS))}.")
        appendLine(t(R.string.policy_read_more_groups_email_the_open) +
            "${how(can(Capability.CHANNEL))}.")
        appendLine(t(R.string.policy_change_what_i_may_do_when) + "${how(can(Capability.RULES))}.")
        appendLine(t(R.string.policy_routines_you_ask_for_every_sunday) + "${how(can(Capability.ROUTINES))}.")
        appendLine(t(R.string.policy_the_phone_s_controls_brightness_volume) + "${how(can(Capability.DEVICE))}.")
        appendLine(t(R.string.policy_find_your_documents_downloads_whatsapp_email) + "${how(can(Capability.FILES_READ))}.")
        appendLine(t(R.string.policy_search_your_photos_by_date_and) + how(can(Capability.PHOTOS_READ)) +
            t(R.string.policy_here_on_the_phone))
        appendLine(t(R.string.policy_learn_about_you_and_your_day) + how(can(Capability.MEMORY)) +
            t(R.string.policy_you_see_it_correct_it_or))
        appendLine(t(R.string.policy_send_a_message_for_you) + how(can(Capability.MESSAGE_SEND)) + t(R.string.policy_family) +
            how(can(Capability.MESSAGE_SEND, "?", setOf("family"))) + t(R.string.policy_by_whatsapp_if_they_wrote_lately))
        appendLine()
        if (apps.isEmpty()) {
            appendLine(t(R.string.policy_i_don_t_read_any_messaging))
        } else {
            appendLine(t(R.string.policy_messages_that_reach_you_on) + apps.joinToString() + ":")
            appendLine(t(R.string.policy_family_2) + names("family") + "): " + how(reply("family")) + t(R.string.policy_after_x_s_to_undo, undoSeconds))
            appendLine(t(R.string.policy_work) + names("work") + "): " + how(reply("work")) +
                t(R.string.policy_with_your_pin_or_fingerprint_they))
            appendLine(t(R.string.policy_anyone_else) +
                (if (reply("") == Autonomy.NEVER) t(R.string.policy_i_never_answer_i_take_a) else how(reply(""))) + ".")
            appendLine(t(R.string.policy_group_chats) + (if (groupChats) t(R.string.policy_i_read_them_too) else t(R.string.policy_i_ignore_them)) +
                t(R.string.policy_i_never_obey_instructions_that_come))
        }
        appendLine()
        append(t(R.string.policy_everything_i_do_is_logged_stop))
        if (missing.isNotEmpty()) append(t(R.string.policy_still_to_set_up) + missing.joinToString() + ".")
    }
}
