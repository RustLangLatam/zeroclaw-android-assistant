package org.rustlanglatam.zeroclaw.android

import java.text.Normalizer

/** Interface actions the owner can trigger by voice or text instead of a menu. */
enum class Command { MENU, CLOSE, HOME, TALK, ACTIVITY, CONTROL, RULES, MEMORY, PHOTOS, FILES, PAUSE, RESUME, WIDGETS, NEWS, APP_MODE, ASSISTANT_MODE }

/**
 * Recognizes short spoken or written commands that the app handles itself (show the menu, open
 * settings, pause), in Spanish or English; any other text goes to the agent. Patterns are anchored
 * on purpose so that a question merely mentioning a keyword is not treated as a command. Pure Kotlin,
 * unit-tested on the JVM.
 */
object Commands {

    // Both languages are always matched, regardless of the assistant's current language.
    private val VERB = Lexicon["commands.verb"]
    private val ARTICLE = Lexicon["commands.article"]

    private val nouns = listOf(
        Command.MENU to Lexicon["commands.noun.menu"],
        Command.ACTIVITY to Lexicon["commands.noun.activity"],
        Command.CONTROL to Lexicon["commands.noun.control"],
        Command.RULES to Lexicon["commands.noun.rules"],
        Command.MEMORY to Lexicon["commands.noun.memory"],
        Command.TALK to Lexicon["commands.noun.talk"],
        Command.PHOTOS to Lexicon["commands.noun.photos"],
        Command.FILES to Lexicon["commands.noun.files"],
        Command.NEWS to Lexicon["commands.noun.news"],
        Command.HOME to Lexicon["commands.noun.home"],
    ).map { (command, words) -> command to Regex("^($VERB )?$ARTICLE($words)$") }

    private val close = Regex(Lexicon["commands.close"])
    private val pause = Regex(Lexicon["commands.pause"])
    private val resume = Regex(Lexicon["commands.resume"])
    // Optional Spanish switch verb and preposition before "mode <name>" (Spanish puts the noun first).
    private val SWITCH = Lexicon["commands.mode_switch"]
    private val appMode = Regex("^$SWITCH" + Lexicon["commands.mode_app"] + "$")
    private val assistantMode = Regex("^$SWITCH" + Lexicon["commands.mode_assistant"] + "$")
    // English places the mode name before "mode", so it needs separate patterns.
    private val SWITCH_EN = Lexicon["commands.mode_switch_en"]
    private val appModeEn = Regex("^$SWITCH_EN" + Lexicon["commands.mode_app_en"] + "$")
    private val assistantModeEn = Regex("^$SWITCH_EN" + Lexicon["commands.mode_assistant_en"] + "$")
    private val widgets = Regex(Lexicon["commands.widgets"])

    fun of(text: String): Command? {
        val t = normalize(text)
        return when {
            t.isEmpty() -> null
            close.matches(t) -> Command.CLOSE
            pause.matches(t) -> Command.PAUSE
            widgets.matches(t) -> Command.WIDGETS
            appMode.matches(t) || appModeEn.matches(t) -> Command.APP_MODE
            assistantMode.matches(t) || assistantModeEn.matches(t) -> Command.ASSISTANT_MODE
            resume.matches(t) -> Command.RESUME
            else -> nouns.firstOrNull { it.second.matches(t) }?.first
        }
    }

    /**
     * Returns whether speech captured by an open microphone is addressed to the assistant: it starts with
     * the assistant's name or a Spanish/English attention word, is an app command, or arrives within
     * [FOLLOW_UP_MS] of the last exchange. Other speech (TV, nearby conversation) is ignored so it does not
     * trigger a model call.
     */
    fun addressed(heard: String, sinceLastExchange: Long): Boolean {
        val raw = heard.trim().lowercase()
        return raw.startsWith(Policy.ASSISTANT.lowercase()) || Lexicon.words("commands.attention").any(raw::startsWith) || of(heard) != null || sinceLastExchange <= FOLLOW_UP_MS
    }

    const val FOLLOW_UP_MS = 20_000L

    /**
     * Returns whether [heard], captured while the assistant was speaking [saying], is an interruption
     * rather than an echo of its own speech: it starts with the assistant's name, an attention word or a
     * stop word, or has at least two words of which at least 60% are absent from [saying].
     */
    fun interrupts(heard: String, saying: String): Boolean {
        // normalize() strips the assistant's name and attention words, so they are checked on the raw text.
        val raw = heard.trim().lowercase()
        if (raw.startsWith(Policy.ASSISTANT.lowercase()) || Lexicon.words("commands.attention").any(raw::startsWith)) return true
        val words = normalize(heard).split(" ").filter { it.isNotEmpty() }
        if (words.isEmpty()) return false
        if (words.first() in STOP) return true
        if (words.size < 2) return false
        val hers = normalize(saying).split(" ").toSet()
        return words.count { it !in hers } * 10 >= words.size * 6
    }

    private val STOP = Lexicon.words("commands.stop").toSet()

    /** Extracts a name from a self-introduction in Spanish or English (or a bare name) by dropping leading filler words; returns it capitalized. */
    fun nameFrom(heard: String): String {
        val words = heard.trim().trimEnd('.', '!', '?').split(Regex("\\s+"))
        val lead = Lexicon.words("commands.name_lead").toSet()
        val name = words.dropWhile { normalize(it) in lead }.joinToString(" ")
        return name.split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
    }

    /** Lowercases, strips diacritics and punctuation, and removes the assistant's name, attention words and "please" forms. */
    fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\b(${Policy.ASSISTANT.lowercase()}|${Lexicon["commands.filler"]})\\b"), " ")
            .replace(Regex("\\s+"), " ").trim()
}
