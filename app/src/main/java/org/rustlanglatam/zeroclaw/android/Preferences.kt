package org.rustlanglatam.zeroclaw.android

/**
 * Parses the assistant's own settings from spoken or written requests. The settings are the
 * same ones exposed on the settings screen; the owner's `set_preference` tool applies them.
 * Pure Kotlin, unit-tested on the JVM.
 */
object Preferences {

    /** A setting the owner can change by voice, with its Spanish and English labels. */
    enum class Setting(@androidx.annotation.StringRes private val labelId: Int) {
        WARMTH(R.string.setting_warmth),
        VOICE(R.string.setting_voice),
        OPEN_MIC(R.string.setting_open_mic),
        PALETTE(R.string.setting_palette),
        LEARNING(R.string.setting_learning),
        READ_MAIL(R.string.setting_read_mail),
        FLOATING_MARK(R.string.setting_floating_mark),
        FLOATING_PLACE(R.string.setting_floating_place),
        UNDO_SECONDS(R.string.setting_undo_seconds),
        GROUP_CHATS(R.string.setting_group_chats),
        WIDGET_ADD(R.string.setting_widget_add),
        WIDGET_REMOVE(R.string.setting_widget_remove),
        LANGUAGE(R.string.setting_language),
        OFFLINE(R.string.setting_offline),
        NAME(R.string.setting_name),
        HER_VOICE(R.string.setting_her_voice),
        VOICE_CEILING(R.string.setting_voice_ceiling),
        MODEL_BUDGET(R.string.setting_model_budget),
        UNANSWERED_HOURS(R.string.setting_unanswered_hours),
        MESSAGING_APPS(R.string.setting_messaging_apps),
        ;

        /** The setting's label in the current language. */
        val label get() = t(labelId)

        companion object {
            /** Resolves [name] (Spanish or English, any case, spaces and underscores ignored) by prefix; `null` if unknown. */
            fun named(name: String): Setting? {
                val n = Commands.normalize(name).replace(" ", "").replace("_", "")
                return when {
                    n.isEmpty() -> null
                    Lexicon.words("preferences.setting.warmth").any(n::startsWith) -> WARMTH
                    Lexicon.words("preferences.setting.model_budget").any(n::startsWith) -> MODEL_BUDGET
                    Lexicon.words("preferences.setting.unanswered_hours").any(n::startsWith) -> UNANSWERED_HOURS
                    Lexicon.words("preferences.setting.voice_ceiling").any(n::startsWith) -> VOICE_CEILING
                    Lexicon.words("preferences.setting.voice").any(n::startsWith) -> VOICE
                    Lexicon.words("preferences.setting.open_mic").any(n::startsWith) -> OPEN_MIC
                    Lexicon.words("preferences.setting.palette").any(n::startsWith) -> PALETTE
                    Lexicon.words("preferences.setting.learning").any(n::startsWith) -> LEARNING
                    Lexicon.words("preferences.setting.read_mail").any(n::startsWith) -> READ_MAIL
                    Lexicon.words("preferences.setting.floating_place").any(n::startsWith) -> FLOATING_PLACE
                    Lexicon.words("preferences.setting.floating_mark").any(n::startsWith) -> FLOATING_MARK
                    Lexicon.words("preferences.setting.undo_seconds").any(n::startsWith) -> UNDO_SECONDS
                    Lexicon.words("preferences.setting.group_chats").any(n::startsWith) -> GROUP_CHATS
                    Lexicon.words("preferences.setting.widget_add").any(n::startsWith) -> WIDGET_ADD
                    Lexicon.words("preferences.setting.widget_remove").any(n::startsWith) -> WIDGET_REMOVE
                    Lexicon.words("preferences.setting.name").any(n::startsWith) -> NAME
                    Lexicon.words("preferences.setting.her_voice").any(n::startsWith) || n in Lexicon.words("preferences.setting.her_voice_exact") -> HER_VOICE
                    Lexicon.words("preferences.setting.messaging_apps").any(n::startsWith) -> MESSAGING_APPS
                    Lexicon.words("preferences.setting.offline").any(n::startsWith) -> OFFLINE
                    Lexicon.words("preferences.setting.language").any(n::startsWith) -> LANGUAGE
                    else -> null
                }
            }
        }
    }

    /**
     * Parses the floating mark's position from [value]: a side (`true` = right) and/or a height as a
     * fraction of the screen (0.18 top, 0.45 middle, 0.72 bottom). Either part may be `null`, meaning it
     * keeps its current value. Returns `null` when neither is present.
     */
    fun floatingPlace(value: String): Pair<Boolean?, Float?>? {
        val v = Commands.normalize(value)
        val side = when {
            Regex(Lexicon["preferences.place.left"]).containsMatchIn(v) -> false
            Regex(Lexicon["preferences.place.right"]).containsMatchIn(v) -> true
            else -> null
        }
        val height = when {
            Regex(Lexicon["preferences.place.top"]).containsMatchIn(v) -> 0.18f
            Regex(Lexicon["preferences.place.middle"]).containsMatchIn(v) -> 0.45f
            Regex(Lexicon["preferences.place.bottom"]).containsMatchIn(v) -> 0.72f
            else -> null
        }
        return if (side == null && height == null) null else side to height
    }

    /**
     * Parses a monthly ceiling in US$ from [value], accepting a comma or a dot as decimal separator.
     * A negation ("no ceiling") yields 0. Returns `null` when no amount in 0..1000 is found.
     */
    fun ceiling(value: String): Double? {
        val v = Commands.normalize(value)
        if (Regex(Lexicon["preferences.ceiling.none"]).containsMatchIn(v)) return 0.0
        return Regex("\\d+(?:[.,]\\d+)?").find(value)?.value?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it in 0.0..1000.0 }
    }

    /** Returns whether [value] asks for the cloud (natural) voice rather than the on-device one. */
    fun natural(value: String) = Regex(Lexicon["preferences.voice.natural"]).containsMatchIn(Commands.normalize(value))

    /** Parses a [Warmth] level from [value] by Spanish or English prefix; `null` if none matches. */
    fun warmth(value: String): Warmth? {
        val v = Commands.normalize(value)
        return when {
            Lexicon.words("preferences.warmth.quiet").any(v::startsWith) -> Warmth.QUIET
            Lexicon.words("preferences.warmth.natural").any(v::startsWith) -> Warmth.NATURAL
            Lexicon.words("preferences.warmth.close").any(v::startsWith) -> Warmth.CLOSE
            else -> null
        }
    }

    /** Returns the key of the palette in [names] matched by key or by the first word of its label; `null` if none. */
    fun palette(value: String, names: List<Pair<String, String>>): String? {
        val v = Commands.normalize(value)
        return names.firstOrNull { (key, label) -> v == Commands.normalize(key) || v.startsWith(Commands.normalize(label).substringBefore(' ')) }?.first
    }

    /** Returns the [Widget] whose label or name shares its first four letters with [value]; `null` if none. */
    fun widget(value: String): Widget? {
        val v = Commands.normalize(value)
        return Widget.entries.firstOrNull { w -> v.startsWith(Commands.normalize(w.label).take(4)) || v.startsWith(w.name.lowercase().take(4)) }
            ?: if (Lexicon.words("preferences.widget.mail").any(v::startsWith)) Widget.MAIL else null
    }
}
