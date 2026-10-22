package org.rustlanglatam.zeroclaw.android

import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject

/**
 * Holds pending `ASK` decisions. A tool call blocks in [request] until the owner answers through [answer]
 * (notification or in-app prompt); no answer before [timeoutMs] counts as a denial (fail closed).
 */
class Approvals(private val timeoutMs: Long = 60_000) {

    /** A pending approval; when [strong], approving requires biometrics or the device credential, not just a tap. */
    data class Pending(val id: Int, val act: Act, val strong: Boolean = false) {
        /** The action as written in the activity log. */
        val text get() = act.log
    }

    private val ids = AtomicInteger()
    private val waiting = ConcurrentHashMap<Int, CompletableFuture<Boolean>>()
    private val asked = ConcurrentHashMap<Int, Pending>()

    /** Called with each new request so it can be shown to the owner. */
    @Volatile var onRequest: (Pending) -> Unit = {}

    fun request(act: Act, strong: Boolean = false): Boolean {
        val pending = Pending(ids.incrementAndGet(), act, strong)
        val answer = CompletableFuture<Boolean>()
        waiting[pending.id] = answer
        asked[pending.id] = pending
        return try {
            onRequest(pending)
            answer.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            false
        } finally {
            waiting.remove(pending.id)
            asked.remove(pending.id)
        }
    }

    /** Returns request [id] while it is pending; the approval prompt reads its content from here, never from an intent extra. */
    fun find(id: Int): Pending? = asked[id]

    /** Returns the number of requests awaiting the owner's answer. */
    fun count() = waiting.size

    fun answer(id: Int, approved: Boolean) {
        waiting[id]?.complete(approved)
    }
}

/** The activity log: each action, its principal, its outcome and the reason (rule) behind it, capped at [MAX] entries. */
class Journal {
    data class Entry(val at: String, val principal: Principal, val action: String, val outcome: String, val reason: String) {
        override fun toString() = "$at ${principal.name.lowercase()} · $action → $outcome ($reason)"
    }

    val entries = CopyOnWriteArrayList<Entry>()

    /** Called after each entry is added, to persist it (appended to `activity.jsonl`). */
    @Volatile var onAdd: (Entry) -> Unit = {}

    fun add(principal: Principal, action: String, outcome: String, reason: String): Entry =
        Entry(LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")), principal, action, outcome, reason)
            .also {
                entries += it
                while (entries.size > MAX) entries.removeAt(0)
                onAdd(it)
            }

    companion object {
        const val MAX = 500

        /** Serializes [entry] as one JSON line of `activity.jsonl`. */
        fun line(entry: Entry): String = JSONObject().put("at", entry.at).put("principal", entry.principal.name)
            .put("action", entry.action).put("outcome", entry.outcome).put("reason", entry.reason).toString()

        /** Parses a line of `activity.jsonl`; `null` for a truncated line (the app stopped mid-write) or an unknown format. */
        fun parse(line: String): Entry? = runCatching {
            val it = JSONObject(line)
            Entry(it.getString("at"), Principal.valueOf(it.getString("principal")), it.getString("action"), it.getString("outcome"), it.getString("reason"))
        }.getOrNull()

        /**
         * Returns the queued entries with no later sent, undone, held back or lost entry for the same action: the app
         * stopped during their undo window, and since [Outbox] is in memory they were lost. Used on startup to log them as lost.
         */
        fun unresolved(entries: List<Entry>): List<Entry> = entries.withIndex()
            .filter { (_, e) -> e.outcome.startsWith("queued") }
            .filter { (i, e) -> entries.drop(i + 1).none { it.action == e.action && listOf("sent", "undone", "held back", "lost").any(it.outcome::startsWith) } }
            .map { it.value }
    }
}

/**
 * Delays outgoing actions by [delayMs] so the owner can undo them. [undo] and the scheduled send race on a single
 * `remove`: whichever removes the item first wins, so an undone item is never sent. Items live in memory only.
 */
class Outbox(private val delayMs: () -> Long) {

    /** A queued action; [at] is the queue time, used for the countdown shown to the owner. */
    data class Item(val id: Int, val act: Act, val at: Long = System.currentTimeMillis()) {
        /** The action as written in the activity log. */
        val what get() = act.log
    }

    private val ids = AtomicInteger()
    private val pending = ConcurrentHashMap<Int, Pair<Item, ScheduledFuture<*>>>()
    private val timer = Executors.newSingleThreadScheduledExecutor()

    /** Queues [send] to run after the delay, unless undone first. */
    fun queue(act: Act, send: () -> Unit): Item {
        val item = Item(ids.incrementAndGet(), act)
        val task = timer.schedule({ if (pending.remove(item.id) != null) send() }, delayMs(), TimeUnit.MILLISECONDS)
        pending[item.id] = item to task
        return item
    }

    /** Cancels item [id]; returns `true` when it was still pending, in which case it is never sent. */
    fun undo(id: Int): Boolean = pending.remove(id)?.also { it.second.cancel(false) } != null

    fun waiting(): List<Item> = pending.values.map { it.first }.sortedBy { it.id }
}

/** Serializes the policy and settings state persisted across restarts: rules, kill switch, undo window and preferences. */
object Saved {
    data class State(
        val rules: List<Rule>,
        val stopped: Boolean,
        val undoSeconds: Int,
        /** The log embedded by format version 1, read once to migrate it to `activity.jsonl`; never written. */
        val log: List<Journal.Entry> = emptyList(),
        /** Messaging app packages to read; `null` in files predating the notification channel. */
        val apps: Set<String>? = null,
        val groups: Boolean = false,
        /** The owner's name; `null` until the first-run setup is done. */
        val owner: String? = null,
        /** Contact name to its groups; `null` means the emulator's built-in fake contacts. */
        val contacts: Map<String, Set<String>>? = null,
        /** Whether answers are spoken aloud. */
        val voice: Boolean = false,
        /** Whether the floating mark is shown over other apps while the app is in the background. */
        val bubble: Boolean = true,
        /** Resting position of the floating mark as `side:fraction`, e.g. `right:0.62`. */
        val bubblePlace: String? = null,
        /** The owner's color palette ([Palettes]); `null` means the default. */
        val palette: String? = null,
        /** How the owner is addressed ([Policy.address]). */
        val address: String? = null,
        /** The assistant's tone level ([Warmth]). */
        val warmth: String? = null,
        /** Whether email notifications are read ([Mailbox.reading]). */
        val mail: Boolean = true,
        /** Whether the microphone stays open while the app is in the foreground, for hands-free conversation. */
        val openMic: Boolean = false,
        /** The home screen widgets in order ([Widgets.encode]); `null` means [Widgets.DEFAULT]. */
        val widgets: List<String>? = null,
        /** Whether app mode (buttons and a menu) is used instead of assistant mode. */
        val appMode: Boolean = false,
        /** Today's daily-limit counters ([Policy.counters]). */
        val counters: Map<String, Int> = emptyMap(),
        /** The language chosen by the owner ([Language.chosen]); `null` when never chosen. */
        val language: String? = null,
        /** Whether offline answers through the local model are enabled ([Offline.wanted]). */
        val offline: Boolean = false,
        /** The selected voice ([Voices.chosen]); `null` means the best available one. */
        val voiceName: String? = null,
        /** Monthly US$ ceiling for the cloud voice, after which the platform voice is used ([Guard.voiceCeiling]); 0 means none. */
        val voiceCeiling: Double = Guard.DEFAULT_VOICE_CEILING,
    )

    /** File format version: 1 embedded the log; 2 moves it to `activity.jsonl` and adds the counters. */
    const val VERSION = 2

    fun encode(state: State): String = JSONObject()
        .put("rules", JSONArray(state.rules.map {
            JSONObject().put("principal", it.principal.name).put("capability", it.capability.name)
                .put("scope", it.scope.toString()).put("autonomy", it.autonomy.name).put("perDay", it.perDay ?: -1)
        }))
        .put("stopped", state.stopped)
        .put("undoSeconds", state.undoSeconds)
        .put("apps", state.apps?.let { JSONArray(it.sorted()) })
        .put("groups", state.groups)
        .put("owner", state.owner)
        .put("contacts", state.contacts?.let { c -> JSONObject().apply { c.forEach { (name, g) -> put(name, JSONArray(g.sorted())) } } })
        .put("voice", state.voice)
        .put("bubble", state.bubble)
        .put("bubblePlace", state.bubblePlace)
        .put("palette", state.palette)
        .put("address", state.address)
        .put("warmth", state.warmth)
        .put("mail", state.mail)
        .put("openMic", state.openMic)
        .put("widgets", state.widgets?.let { JSONArray(it) })
        .put("appMode", state.appMode)
        .put("language", state.language)
        .put("offline", state.offline)
        .put("voiceName", state.voiceName)
        .put("voiceCeiling", state.voiceCeiling)
        .put("counters", JSONObject().apply { state.counters.forEach { (k, v) -> put(k, v) } })
        .put("version", VERSION)
        .toString()

    fun decode(text: String): State {
        val json = JSONObject(text)
        fun <T> JSONArray.map(f: (JSONObject) -> T) = (0 until length()).map { f(getJSONObject(it)) }
        return State(
            // A rule this version cannot parse (written by a newer one) is skipped instead of failing the whole file.
            rules = json.getJSONArray("rules").map {
                runCatching {
                    Rule(
                        Principal.valueOf(it.getString("principal")),
                        Capability.valueOf(it.getString("capability")),
                        scope(it.getString("scope")),
                        Autonomy.valueOf(it.getString("autonomy")),
                        it.getInt("perDay").takeIf { n -> n >= 0 },
                    )
                }.getOrNull()
            }.filterNotNull(),
            stopped = json.getBoolean("stopped"),
            undoSeconds = json.getInt("undoSeconds"),
            apps = json.optJSONArray("apps")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() },
            groups = json.optBoolean("groups", false),
            owner = json.optString("owner").takeIf { json.has("owner") && it.isNotEmpty() },
            contacts = json.optJSONObject("contacts")?.let { c ->
                c.keys().asSequence().associateWith { name -> c.getJSONArray(name).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } }
            },
            voice = json.optBoolean("voice", false),
            bubble = json.optBoolean("bubble", true),
            bubblePlace = json.optString("bubblePlace").takeIf { json.has("bubblePlace") && it.isNotEmpty() },
            palette = json.optString("palette").takeIf { it.isNotEmpty() },
            address = json.optString("address").takeIf { json.has("address") && it.isNotEmpty() },
            warmth = json.optString("warmth").takeIf { json.has("warmth") && it.isNotEmpty() },
            mail = json.optBoolean("mail", true),
            openMic = json.optBoolean("openMic", false),
            widgets = json.optJSONArray("widgets")?.let { a -> (0 until a.length()).map { a.getString(it) } },
            appMode = json.optBoolean("appMode", false),
            language = json.optString("language").takeIf { json.has("language") && it.isNotEmpty() },
            offline = json.optBoolean("offline", false),
            voiceName = json.optString("voiceName").takeIf { json.has("voiceName") && it.isNotEmpty() },
            voiceCeiling = json.optDouble("voiceCeiling", Guard.DEFAULT_VOICE_CEILING),
            // Format version 1 stored the log here; it is read once and migrated to its own file.
            log = json.optJSONArray("log")?.map {
                Journal.Entry(it.getString("at"), Principal.valueOf(it.getString("principal")), it.getString("action"),
                    it.getString("outcome"), it.getString("reason"))
            }.orEmpty(),
            counters = json.optJSONObject("counters")?.let { c -> c.keys().asSequence().associateWith { c.getInt(it) } }.orEmpty(),
        )
    }

    private fun scope(text: String) = when {
        text == "all" -> Scope.ALL
        text.startsWith("group:") -> Scope.group(text.removePrefix("group:"))
        else -> Scope.contact(text.removePrefix("contact:"))
    }
}
