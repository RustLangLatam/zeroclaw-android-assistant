package org.rustlanglatam.zeroclaw.android

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import kotlin.concurrent.thread

/**
 * Accumulates context about the owner over time: preferred communication style ([Style]), significant
 * dates ([Moments]) and a weekly summary of new memories ([Reflection]). This only enriches what the
 * agent is given; it does not change the model. Pure Kotlin except for [run].
 */
object Empathy {

    /**
     * Learns the owner's preferred style by counting cues (requests for shorter or longer answers, laughter,
     * requests for directness) in the owner's messages. Counts are stored sealed on the device and rendered
     * into the agent's persona (`SOUL.md`).
     */
    object Style {
        /** A style cue and the pattern (Spanish or English, normalized text) that detects it. */
        enum class Cue(val words: Regex) {
            SHORTER(Regex(Lexicon["empathy.cue.shorter"])),
            LONGER(Regex(Lexicon["empathy.cue.longer"])),
            HUMOR(Regex(Lexicon["empathy.cue.humor"])),
            PLAIN(Regex(Lexicon["empathy.cue.plain"])),
        }

        private val counts = java.util.concurrent.ConcurrentHashMap<Cue, Int>()
        private var file: File? = null

        /** Invoked after a cue is recorded, so the persona can be rewritten. */
        @Volatile var onChange: () -> Unit = {}

        /** Records the cues found in the owner's [text] and returns them. */
        fun learn(text: String): List<Cue> {
            val t = Commands.normalize(text)
            val cues = Cue.entries.filter { it.words.containsMatchIn(t) }
            if (cues.isEmpty()) return cues
            cues.forEach { counts.merge(it, 1, Int::plus) }
            save()
            onChange()
            return cues
        }

        /**
         * Returns the persona section describing learned preferences, or an empty string. A cue is included only
         * after [SEEN] occurrences, to distinguish a stable preference from a one-off remark.
         */
        fun describe(): String {
            fun seen(c: Cue) = (counts[c] ?: 0) >= SEEN
            val lines = buildList {
                if (seen(Cue.SHORTER) && (counts[Cue.SHORTER] ?: 0) > (counts[Cue.LONGER] ?: 0)) add("They like short answers: one or two sentences unless they ask for more.")
                if (seen(Cue.LONGER) && (counts[Cue.LONGER] ?: 0) > (counts[Cue.SHORTER] ?: 0)) add("They like detail: explain more fully, with an example.")
                if (seen(Cue.HUMOR)) add("They enjoy a light joke now and then.")
                if (seen(Cue.PLAIN)) add("They like it straight to the point: no preamble.")
            }
            return if (lines.isEmpty()) "" else "\n# How the owner likes to be talked to (learned from them)\n" + lines.joinToString("\n") { "- $it" }
        }

        /** Sets the backing [file] and loads stored counts from it. */
        fun load(file: File) {
            this.file = file
            runCatching { JSONObject(file.readSealed()).let { j -> Cue.entries.forEach { c -> j.optInt(c.name, 0).takeIf { it > 0 }?.let { counts[c] = it } } } }
        }

        /** Clears all counts (debug and tests). */
        fun reset() { counts.clear(); save() }

        private fun save() {
            val target = file ?: return
            runCatching { target.writeSealed(JSONObject().apply { counts.forEach { (c, n) -> put(c.name, n) } }.toString()) }
        }

        /** Occurrences needed before a cue is treated as a preference. */
        const val SEEN = 3
    }

    /** Significant dates for the owner (birthdays, anniversaries, difficult days), taken from dated memories. */
    object Moments {
        /** Returns dated memories falling on [today] or tomorrow, each paired with a localized "today"/"tomorrow" label. */
        fun upcoming(items: List<Memory.Item>, today: LocalDate): List<Pair<Memory.Item, String>> = items.mapNotNull { item ->
            val day = item.on ?: return@mapNotNull null
            fun on(d: LocalDate) = if (day.length == 5) day == "%02d-%02d".format(d.monthValue, d.dayOfMonth) else day == d.toString()
            when {
                on(today) -> item to t(R.string.empathy_today)
                on(today.plusDays(1)) -> item to t(R.string.empathy_tomorrow)
                else -> null
            }
        }

        /** Validates a date in the form `remember` accepts: `MM-DD` (yearly) or `YYYY-MM-DD` (once); `null` otherwise. */
        fun day(text: String?): String? = text?.trim()?.takeIf { Regex("""^(\d{4}-)?\d{2}-\d{2}$""").matches(it) }?.takeIf {
            runCatching { LocalDate.parse(if (it.length == 5) "2024-$it" else it) }.isSuccess
        }
    }

    /** Weekly reflection: the agent, without tools, condenses the week's memories into at most three conclusions. */
    object Reflection {
        /** Returns whether at least seven days have passed since [last]. */
        fun due(last: Long, now: Long) = now - last >= 7 * 86_400_000L

        /** Builds the reflection prompt listing [week]'s memories. */
        fun prompt(owner: String, week: List<Memory.Item>) =
            "(A note from the app: once a week you reflect on what you learned about $owner.) These are this week's memories:\n" +
                week.joinToString("\n") { "- ${it.text}" } +
                "\nWrite at most three short conclusions about $owner that these suggest (a habit, what weighs on them, what " +
                "they enjoy), one per line, each starting with \"- \", in ${Language.current.english}, in the third person. Only what the " +
                "memories support; nothing if they support nothing. No other text."

        /** Extracts up to three conclusions from [answer]: lines starting with "- ", 8 to 160 characters long. */
        fun conclusions(answer: String): List<String> =
            answer.lines().map { it.trim() }.filter { it.startsWith("- ") }.map { it.removePrefix("- ").trim() }.filter { it.length in 8..160 }.take(3)
    }

    private val running = java.util.concurrent.atomic.AtomicBoolean()

    /**
     * Starts, at most once per process, a daemon thread that every six hours posts today's and tomorrow's
     * moments as notes on the home screen and runs the weekly reflection when due. Skips work while paused.
     */
    fun run(context: Context) {
        if (running.compareAndSet(false, true)) thread(name = "empathy", isDaemon = true) { loop(context.applicationContext) }
    }

    private fun loop(context: Context) {
        val prefs = context.getSharedPreferences("empathy", Context.MODE_PRIVATE)
        while (true) {
            if (!Guard.policy.stopped) runCatching {
                val today = LocalDate.now()
                val endOfDay = today.plusDays(1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                Moments.upcoming(Memory.items, today).forEach { (item, `when`) -> Notes.put(Notes.Note(item.text.take(24), `when`, endOfDay)) }
                val now = System.currentTimeMillis()
                if (Reflection.due(prefs.getLong("reflected", 0), now)) {
                    val week = Memory.items.filter { now - it.at < 7 * 86_400_000L && !it.conversation }
                    prefs.edit().putLong("reflected", now).apply()
                    if (week.size >= 3) Turns.scribe(context, Reflection.prompt(Policy.owner, week)) { answer ->
                        Reflection.conclusions(answer.orEmpty()).forEach { Memory.remember(it, confirmed = false) }
                    }
                }
            }
            Thread.sleep(6 * 3_600_000L)
        }
    }
}
