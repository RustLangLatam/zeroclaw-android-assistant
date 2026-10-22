package org.rustlanglatam.zeroclaw.android

import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Messages received through messaging apps' notifications (docs/specs/access-model.md, "Notification channel").
 * Pure Kotlin: [MessageListener] turns a notification into [Raw] messages, and this object decides which are new
 * messages from other people for the `third_party` agent. It also keeps the owner's digest of received messages
 * and their status.
 */
object Inbox {

    /** Values of [Entry.status], stored in the persisted digest. */
    object Status {
        const val RECEIVED = "received"
        const val ANSWERED = "answered"
        const val WAITING = "waiting"
        const val QUEUED = "queued"
        const val PAUSED = "paused"
        const val RETRYING = "retrying"
        const val NO_ANSWER = "no_answer"
        const val COULD_NOT_ANSWER = "could_not_answer"
        const val UNDONE = "undone"
        const val NOT_APPROVED = "not_approved"
        const val HELD_BACK = "held_back"

        /** Status values written by earlier versions, mapped to the current ones when a saved digest is loaded. */
        private val legacy = mapOf(
            "recibido" to RECEIVED, "respondido" to ANSWERED, "esperando" to WAITING, "en cola" to QUEUED,
            "en pausa" to PAUSED, "reintentando" to RETRYING, "sin respuesta" to NO_ANSWER,
            "no se pudo responder" to COULD_NOT_ANSWER, "deshecho" to UNDONE, "no aprobado" to NOT_APPROVED,
            "retenido (en pausa)" to HELD_BACK,
        )

        fun migrate(status: String) = legacy[status] ?: status
    }

    /**
     * One message of a MessagingStyle notification; a `null` [sender] is the owner. [who] is the sender's reference in
     * the notification (`tel:`, a contacts URI, a messaging app's id), if any.
     */
    data class Raw(val sender: String?, val text: String, val time: Long, val who: String? = null)

    /** A new message from another person; [conversation] is the notification key. */
    data class Message(val app: String, val conversation: String, val sender: String, val text: String, val time: Long, val who: String? = null)

    /** Messaging app packages read by default: WhatsApp, WhatsApp Business, Telegram, Signal and common SMS apps. */
    val DEFAULT_APPS = setOf(
        "com.whatsapp",
        "com.whatsapp.w4b",
        "org.telegram.messenger",
        "org.thoughtcrime.securesms",
        "com.google.android.apps.messaging",
        "com.samsung.android.messaging",
    )

    /** Packages whose messages are read, set by the owner; empty until setup is saved. */
    @Volatile var apps: Set<String> = emptySet()

    /** Whether group chats are handled; off by default. */
    @Volatile var groups = false

    private val seen = LinkedHashSet<String>()
    private var seenFile: File? = null
    private var appended = 0

    /** Messages older than this time (when message reading was first set up) are history and never handled. */
    private var since = 0L

    /**
     * Loads the seen-message keys from [file] (and the introductions and digest next to it), so a restart does not
     * answer again a message whose notification is still shown: the notification is re-posted with its history on
     * the conversation's next message. The file's first line records its creation time, so on a new install the
     * history already on the phone is not answered.
     */
    @Synchronized
    fun load(file: File, now: Long = System.currentTimeMillis()) {
        seenFile = file
        // Conversations where the assistant already introduced itself; persisted so the intro is not repeated after a restart.
        introducedFile = File(file.parentFile, "introduced.txt").also { f -> if (f.exists()) runCatching { introduced.addAll(f.readSealedLines()) } }
        seen.clear()
        val lines = if (file.exists()) runCatching { file.readSealedLines() }.getOrDefault(emptyList()) else emptyList()
        since = lines.firstOrNull()?.takeIf { it.startsWith(SINCE) }?.removePrefix(SINCE)?.toLongOrNull() ?: now
        seen.addAll(lines.filterNot { it.startsWith(SINCE) }.takeLast(MAX_SEEN))
        rewrite()
        // The digest is persisted so the activity screen shows the same entries and read state after a restart.
        digestFile = File(file.parentFile, "digest.json").also { f ->
            digest.clear()
            val saved = if (f.exists()) runCatching { decode(f.readSealed()) }.getOrNull() else null
            saved?.let { (read, entries) -> digest.addAll(entries); readTo = read }
            ids.set(digest.maxOfOrNull { it.id } ?: 0)
        }
    }

    private var digestFile: File? = null
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor()

    /** Persists a snapshot of the digest on a single writer thread, so the newest state is written last (ADR-0021). */
    private fun saveDigest() {
        val target = digestFile ?: return
        val copy = digest.toList()
        val read = readTo
        writer.execute { runCatching { target.writeSealed(encode(copy, read)) } }
    }

    fun encode(list: List<Entry>, read: Int): String = org.json.JSONObject().put("read", read).put("entries", org.json.JSONArray(list.map {
        org.json.JSONObject().put("id", it.id).put("at", it.at).put("sender", it.sender).put("text", it.text).put("status", it.status)
            .put("app", it.app).put("time", it.time).put("conversation", it.conversation)
    })).toString()

    /** Parses a saved digest into the last read entry id and the entries. */
    fun decode(text: String): Pair<Int, List<Entry>> = org.json.JSONObject(text).let { o ->
        val a = o.getJSONArray("entries")
        o.optInt("read") to (0 until a.length()).map { a.getJSONObject(it) }.map {
            Entry(it.getInt("id"), it.getString("at"), it.getString("sender"), it.getString("text"), Status.migrate(it.getString("status")),
                it.optString("app"), it.optLong("time"), it.optString("conversation"))
        }
    }

    private fun rewrite() {
        seenFile?.writeSealedLines(listOf("$SINCE$since") + seen)
        appended = 0
    }

    /**
     * Returns the new messages of one notification: from an allowed app, not a group chat (unless enabled), not the
     * owner's own, not older than [since], and not seen before. A notification is re-posted with its whole history
     * and an edit keeps its timestamp, so `conversation + time` identifies a message.
     */
    @Synchronized
    fun accept(app: String, conversation: String, group: Boolean, messages: List<Raw>): List<Message> {
        if (app !in apps || (group && !groups)) return emptyList()
        val new = messages
            .filter { it.sender != null && it.text.isNotBlank() && it.time >= since }
            .filter { seen.add(key(conversation, it.time)) }
            .map { Message(app, conversation, it.sender!!, it.text, it.time, it.who) }
        if (new.isEmpty()) return new
        // Persisted before the turn runs: a crash then drops a message rather than answering it twice.
        seenFile?.let { f -> new.forEach { f.appendSealed(key(conversation, it.time)) } }
        appended += new.size
        while (seen.size > MAX_SEEN) seen.remove(seen.first())
        if (appended > MAX_SEEN) rewrite()
        return new
    }

    private fun key(conversation: String, time: Long) = "$conversation $time"

    private val introduced = mutableSetOf<String>()
    private var introducedFile: File? = null

    /** Clears the introduced conversations so the next reply in each includes the intro again (debug: reproducible end-to-end runs). */
    @Synchronized
    fun forgetIntroductions() {
        introduced.clear()
        introducedFile?.delete()
    }

    /**
     * Returns [text] for [conversation], prefixed with an introduction as the owner's assistant on the first reply
     * there, unless the answer already contains one. The introduction's language follows [english].
     */
    @Synchronized
    fun withIntro(conversation: String, text: String): String {
        val said = (listOf(Policy.ASSISTANT) + Lang.entries.map { t(it, R.string.inbox_x_s_assistant, Policy.owner) }).any { it in text }
        val first = introduced.add(conversation)
        if (first) runCatching { introducedFile?.appendSealed(conversation) }
        if (!first || said) return text
        return t(if (english(text)) Lang.EN else Lang.ES, R.string.inbox_intro, Policy.ASSISTANT, Policy.owner, text)
    }

    /** Returns whether [text] has more common English than Spanish words, to pick the introduction's language. */
    fun english(text: String): Boolean {
        val words = Regex(Lexicon["inbox.word"]).findAll(text.lowercase()).map { it.value }.toList()
        val en = words.count { it in EN }
        val es = words.count { it in ES }
        return en > es
    }

    private val EN = Lexicon.words("inbox.common_words.en").toSet()
    private val ES = Lexicon.words("inbox.common_words.es").toSet()

    /**
     * An entry of the owner's digest: a received message and its current [status], one of [Status]. [app] is the messaging app's
     * package, shown as the message's source.
     */
    data class Entry(val id: Int, val at: String, val sender: String, val text: String, @Volatile var status: String, val app: String = "", val time: Long = 0, val conversation: String = "")

    val digest = CopyOnWriteArrayList<Entry>()

    /** Id of the last entry the owner has seen in the activity screen; later entries are unread. */
    @Volatile private var readTo = 0
    var readUpTo: Int
        get() = readTo
        set(value) { if (value != readTo) { readTo = value; saveDigest() } }

    fun unread() = digest.filter { it.id > readUpTo }
    private val ids = AtomicInteger()

    /** Called after each change, to refresh the digest notification. */
    @Volatile var onChange: () -> Unit = {}

    fun received(message: Message): Entry =
        Entry(ids.incrementAndGet(), LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")), message.sender, message.text, Status.RECEIVED, message.app, message.time, message.conversation)
            .also { digest += it; while (digest.size > MAX_DIGEST) digest.removeAt(0); saveDigest(); onChange() }

    /** Returns entries since [since] that got no answer (no connection, provider unreachable), to be answered again. */
    fun missed(since: Long): List<Entry> = digest.filter { it.status == Status.NO_ANSWER && it.time >= since && it.conversation.isNotEmpty() }

    /** Marks the entry referenced by an undone reply ([Act.ref]) as undone. */
    fun undone(act: Act) = mark(act, Status.UNDONE)

    /** Marks the entry of a reply held back by the kill switch. */
    fun heldBack(act: Act) = mark(act, Status.HELD_BACK)

    private fun mark(act: Act, status: String) {
        val id = act.ref ?: return
        digest.firstOrNull { it.id == id }?.let { status(it, status) }
    }

    fun status(entry: Entry, status: String) {
        entry.status = status
        saveDigest()
        onChange()
    }

    private const val MAX_SEEN = 2000
    private const val SINCE = "since "
    private const val MAX_DIGEST = 200
}
