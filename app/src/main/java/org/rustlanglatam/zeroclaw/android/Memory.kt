package org.rustlanglatam.zeroclaw.android

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The assistant's knowledge about the owner: preferences, routines and facts worth keeping. Each item records whether
 * the owner stated it or it was inferred, and when; the owner can view, delete or pause it from the memory screen.
 * Only the `owner` agent reads or writes it. The source of truth is the `owner` agent's memory in ZeroClaw, next to
 * entries the agent stores itself in conversation ([Item.conversation]); [PhoneMemory] syncs it, and a sealed local
 * copy makes it available immediately and offline.
 */
object Memory {

    /**
     * One memory. [key] is its key in ZeroClaw; [conversation] is `true` when the agent stored it itself during a
     * conversation (`memory_store`), which is treated as inferred.
     */
    data class Item(
        val id: Long, val text: String, val confirmed: Boolean, val at: Long, val area: Area = Area.of(text),
        val key: String = "profile-$id", val conversation: Boolean = false,
        /** The date it refers to, if any: `MM-DD` for a yearly date (a birthday) or `YYYY-MM-DD` for a single one. */
        val on: String? = null,
    )

    /** Returns the ZeroClaw category of a profile memory: `profile:<area>:<told|inferred>[:<date>]`. */
    fun category(item: Item) = "profile:${item.area.name.lowercase()}:${if (item.confirmed) "told" else "inferred"}" + (item.on?.let { ":$it" } ?: "")

    /** Converts a ZeroClaw memory entry to an [Item]: profile entries are decoded from their category, any other is a conversation memory. */
    fun fromStore(key: String, content: String, category: String, at: Long): Item {
        val parts = category.split(':')
        val id = key.removePrefix("profile-").toLongOrNull() ?: (key.hashCode().toLong() and 0xffffffffL)
        return if (parts.size in 3..4 && parts[0] == "profile") Item(id, content, parts[2] == "told", at, Area.named(parts[1]) ?: Area.of(content), key, on = parts.getOrNull(3))
        else Item(id, content, false, at, Area.of(content), key, conversation = true)
    }

    /** Life areas a memory can belong to, classified by keyword; knowledge depth is computed per area. [ask] hints, in English for the agent, what to ask about. */
    enum class Area(@androidx.annotation.StringRes private val labelId: Int, val ask: String, words: String, val known: Boolean = true) {
        PERSONAL(R.string.area_personal, "where they are from, their age, what they are like", Lexicon["memory.area.personal"]),
        PEOPLE(R.string.area_people, "their family, partner, friends", Lexicon["memory.area.people"]),
        WORK(R.string.area_work, "what they do, their projects, their colleagues", Lexicon["memory.area.work"]),
        HEALTH(R.string.area_health, "only if they bring it up", Lexicon["memory.area.health"]),
        TASTES(R.string.area_tastes, "music, food, films, what they enjoy", Lexicon["memory.area.tastes"]),
        ROUTINES(R.string.area_routines, "when they get up, when they work, their habits", Lexicon["memory.area.routines"]),
        INTERESTS(R.string.area_interests, "the topics they follow: politics, sports, science", Lexicon["memory.area.interests"]),
        GOALS(R.string.area_goals, "what they want to achieve, their plans", Lexicon["memory.area.goals"]),
        PLACES(R.string.area_places, "their home, neighbourhood, the places they go", Lexicon["memory.area.places"]),
        /**
         * The owner's recent mood (tired, worried, happy). Transient: it expires after [MOOD_DAYS] and is excluded
         * from knowledge depth.
         */
        MOOD(R.string.area_mood, "", Lexicon["memory.area.mood"], known = false),
        ;

        private val pattern = Regex("\\b($words)\\b")

        /** The area's display name in the current language. */
        val label: String get() = t(labelId)

        companion object {
            /** Classifies [text] by keyword matches (earlier areas win ties); [PERSONAL] when nothing matches. */
            fun of(text: String): Area {
                val t = Commands.normalize(text)
                return entries.maxBy { a -> a.pattern.findAll(t).count() * 10 - a.ordinal }.takeIf { it.pattern.containsMatchIn(t) } ?: PERSONAL
            }

            fun named(name: String?): Area? = entries.firstOrNull { it.name.equals(name?.trim(), ignoreCase = true) }
                ?: if (Commands.normalize(name.orEmpty()) in Lexicon.words("memory.area_name.mood")) MOOD else null

            /** The areas that count toward knowledge depth (all but [MOOD]). */
            val KNOWN get() = entries.filter { it.known }
        }
    }

    /** Knowledge depth of an area, from a score where a stated fact counts 2 and an inference 1 (see [depth]). */
    enum class Depth(@androidx.annotation.StringRes private val labelId: Int) {
        NONE(R.string.depth_none), LITTLE(R.string.depth_little), SOME(R.string.depth_some), WELL(R.string.depth_well);

        val label get() = t(labelId)
    }

    fun depth(area: Area, list: List<Item> = items): Depth {
        val score = list.filter { it.area == area }.sumOf { if (it.confirmed) 2 else 1 }
        return when {
            score == 0 -> Depth.NONE
            score < 5 -> Depth.LITTLE
            score < 12 -> Depth.SOME
            else -> Depth.WELL
        }
    }

    val items = CopyOnWriteArrayList<Item>()

    /** Whether new memories may be stored; `false` pauses learning, existing memories stay until deleted. */
    @Volatile var learning = true

    private var file: File? = null

    /** Called after each change, so the owner agent's persona is regenerated from the memories. */
    @Volatile var onChange: () -> Unit = {}

    /** Hooks to store or remove a memory in ZeroClaw ([PhoneMemory]); no-ops in JVM unit tests. */
    @Volatile var onPut: (Item) -> Unit = {}
    @Volatile var onDrop: (Item) -> Unit = {}

    /** Whether memories previously kept only on the phone have been migrated to ZeroClaw. */
    @Volatile var migrated = false

    /** Replaces the local copy with the list currently held by ZeroClaw. */
    @Synchronized
    fun replace(list: List<Item>) {
        items.clear()
        items += list.sortedBy { it.at }
        save()
    }

    @Synchronized
    fun load(file: File) {
        this.file = file
        items.clear()
        if (!file.exists()) return
        runCatching {
            val json = JSONObject(file.readSealed())
            learning = json.optBoolean("learning", true)
            migrated = json.optBoolean("migrated", false)
            val list = json.getJSONArray("items")
            for (i in 0 until list.length()) list.getJSONObject(i).let {
                val text = it.getString("text")
                val id = it.getLong("id")
                items += Item(
                    id, text, it.getBoolean("confirmed"), it.getLong("at"), Area.named(it.optString("area")) ?: Area.of(text),
                    it.optString("key").ifEmpty { "profile-$id" }, it.optBoolean("conversation", false), it.optString("on").ifEmpty { null },
                )
            }
        }
    }

    @Synchronized
    private fun save() {
        file?.writeSealed(
            JSONObject().put("learning", learning).put("migrated", migrated).put("items", JSONArray(items.map {
                JSONObject().put("id", it.id).put("text", it.text).put("confirmed", it.confirmed).put("at", it.at).put("area", it.area.name)
                    .put("key", it.key).put("conversation", it.conversation).put("on", it.on ?: "")
            })).toString(),
        )
        onChange()
    }

    /** Stores [text]; a duplicate (after normalization) updates the existing item, and confirming it turns an inference into a fact. */
    @Synchronized
    fun remember(text: String, confirmed: Boolean, now: Long = System.currentTimeMillis(), area: Area? = null, on: String? = null): String {
        val clean = text.trim().removeSuffix(".")
        if (clean.isEmpty()) return "Nothing to remember."
        if (!learning) return "The owner paused learning: nothing was saved."
        val same = items.firstOrNull { Commands.normalize(it.text) == Commands.normalize(clean) }
        val kept = if (same != null) {
            // A conversation memory stored again is promoted to the profile, keeping its key.
            same.copy(confirmed = same.confirmed || confirmed, at = now, area = area ?: same.area, conversation = false, on = on ?: same.on)
                .also { items[items.indexOf(same)] = it }
        } else {
            Item(now, clean, confirmed, now, area ?: Area.of(clean), on = on).also { items += it }
        }
        // Only the local copy is capped at [MAX]; ZeroClaw keeps everything, including the agent's own entries.
        while (items.size > MAX) items.removeAt(0)
        save()
        onPut(kept)
        return if (confirmed) "Remembered: $clean." else "Remembered, as something you inferred: $clean."
    }

    /**
     * Deletes the memories matching [text]: an exact match, or else those containing it when [text] has at least
     * [MIN_FORGET] characters and matches at most [MAX_FORGET] items. Returns the number deleted (0 when too vague).
     */
    @Synchronized
    fun forget(text: String): Int {
        val n = Commands.normalize(text)
        val exact = items.filter { Commands.normalize(it.text) == n }
        val gone = exact.ifEmpty {
            items.filter { n.length >= MIN_FORGET && n in Commands.normalize(it.text) }.takeIf { it.size <= MAX_FORGET }.orEmpty()
        }
        items.removeAll(gone.toSet())
        if (gone.isNotEmpty()) save()
        gone.forEach(onDrop)
        return gone.size
    }

    @Synchronized
    fun forget(id: Long) {
        val gone = items.firstOrNull { it.id == id } ?: return
        items.remove(gone)
        save()
        onDrop(gone)
    }

    @Synchronized
    fun pause(paused: Boolean) {
        learning = !paused
        save()
    }

    /** Builds the memory section of the owner agent's prompt: facts, inferences, stale items and recent moods, grouped by area. */
    fun describe(now: Long = System.currentTimeMillis()): String {
        // Conversation memories are excluded: ZeroClaw's own recall returns them.
        // Moods within [MOOD_DAYS] are listed separately with their dates; older ones are omitted.
        val moods = items.filter { it.area == Area.MOOD && now - it.at < MOOD_DAYS * 86_400_000L }.sortedByDescending { it.at }
        val profile = items.filterNot { it.conversation || it.area == Area.MOOD }
        if (profile.isEmpty() && moods.isEmpty()) return "You do not know anything about the owner yet."
        // Items not refreshed within [FADE_DAYS] may be outdated and are marked as such; dated items (a birthday) never fade.
        val (faded, fresh) = profile.partition { it.on == null && now - it.at > FADE_DAYS * 86_400_000L }
        val (facts, guesses) = fresh.partition { it.confirmed }
        fun list(of: List<Item>) = of.groupBy { it.area }.toSortedMap().entries.joinToString("\n") { (area, them) ->
            "${area.label}:\n" + them.joinToString("\n") { "- ${it.text}" }
        }
        return buildString {
            if (facts.isNotEmpty()) appendLine("What the owner told you:\n" + list(facts))
            if (guesses.isNotEmpty()) appendLine("What you inferred (not confirmed; ask before relying on it):\n" + list(guesses))
            if (faded.isNotEmpty()) appendLine("Unsure now (learned long ago and not heard since; it may have changed: " +
                "confirm it with the owner, once and lightly, before relying on it, and remember it again when they do):\n" + list(faded))
            if (moods.isNotEmpty()) appendLine(
                "How they have been lately (most recent first; let it shape your tone: gentler when tired or worried, " +
                    "lighter when happy; ask about it once, never insist):\n" +
                    moods.joinToString("\n") { "- " + java.time.Instant.ofEpochMilli(it.at).atZone(java.time.ZoneId.systemDefault()).toLocalDate() + ": " + it.text },
            )
        }.trim()
    }

    /**
     * Builds the agent's answer material for "how well do you know me": depth per area and the areas with little
     * knowledge where a question would help (health excluded, never asked about unprompted).
     */
    fun profile(list: List<Item> = items): String {
        val lines = Area.KNOWN.joinToString("\n") { a ->
            val mine = list.filter { it.area == a }
            val facts = mine.count { it.confirmed }
            "- ${a.label}: ${depth(a, list).label} ($facts told, ${mine.size - facts} inferred)"
        }
        val gaps = Area.KNOWN.filter { it != Area.HEALTH && depth(it, list) <= Depth.LITTLE }
        return "How well you know the owner, by area:\n$lines" +
            (if (gaps.isEmpty()) "" else "\nYou know little about: " + gaps.joinToString("; ") { "${it.label} (${it.ask})" } + ".") +
            "\nTell it warmly and honestly, in a few sentences, not as a list; offer to learn more where you know little."
    }

    private const val MAX = 200

    /** Days a mood stays in the agent's prompt. */
    const val MOOD_DAYS = 14

    /** Days without a refresh after which a memory is presented as uncertain. */
    const val FADE_DAYS = 180

    /** Minimum query length for a partial-match forget; shorter queries only delete exact matches. */
    const val MIN_FORGET = 4

    /** Maximum number of memories a partial-match forget may delete; broader matches delete nothing. */
    const val MAX_FORGET = 3
}
