package org.rustlanglatam.zeroclaw.android

import android.content.Context
import android.util.Log
import io.github.rustlanglatam.zeroclaw.ChatSession
import io.github.rustlanglatam.zeroclaw.FrameType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * The owner's chat with the `owner` agent over ZeroClaw's `/ws/chat`, scoped to the process rather than an
 * activity: one socket, reconnected when it drops, with its lines in [Ui.chat]. When it was tied to the activity, a
 * rotation or moving to the background closed it mid-turn, losing the answer and leaving the UI in the thinking state.
 * The visible activity receives each answer through [onAnswer] to speak it. On start, the session's last lines are
 * restored from ZeroClaw. While offline, turns go to the local model's agent ([Offline.AGENT]).
 */
object OwnerChat {
    /** The socket while connected; `null` while disconnected. */
    @Volatile var socket: ChatSession? = null
        private set

    /** Receives each answer while the app is in the foreground, to speak it. */
    @Volatile var onAnswer: ((String) -> Unit)? = null

    /** Called when a turn fails while the app is in the foreground (e.g. to resume listening on an open microphone). */
    @Volatile var onError: (() -> Unit)? = null

    private val started = AtomicBoolean()

    /** Connects once per process; the connection then reconnects by itself. */
    fun start(context: Context) {
        app = context.applicationContext
        if (started.compareAndSet(false, true)) {
            thread(name = "owner-chat") { connect(app, "owner") }
            thread(name = "owner-chat-watch", isDaemon = true) { watch() }
        }
    }

    /** Time the current cloud turn was sent; 0 when no cloud turn is in progress. */
    @Volatile private var turnAt = 0L

    /** Time of the last frame (or approval wait) in the current cloud turn. */
    @Volatile private var lastFrame = 0L

    /** Whether the current cloud turn has already said it is slow. */
    @Volatile private var saidSlow = false

    /**
     * Keeps a cloud turn from staying silent: with no frame for [SLOW_MS] it says it is still working, and with none for
     * [SILENT_MS] it gives up, says so, and drops the socket so the next turn starts on a new connection. Time spent
     * waiting for the owner's approval does not count.
     */
    private fun watch() {
        while (true) {
            Thread.sleep(1_000)
            if (turnAt == 0L) continue
            val now = System.currentTimeMillis()
            if (Ui.pending != null) { lastFrame = now; continue }
            val idle = now - lastFrame
            if (idle > SLOW_MS && !saidSlow) { saidSlow = true; Ui.add(Line(Line.Who.NOTE, t(R.string.ownerchat_still_working))) }
            if (idle > SILENT_MS) {
                Log.w(AssistantService.TAG, "chat: no answer in ${idle / 1000} s; turn dropped")
                turnAt = 0L
                Ui.thinking = false
                Ui.add(Line(Line.Who.NOTE, t(R.string.ownerchat_no_answer_try_again)))
                onError?.invoke()
                socket?.let { runCatching { it.close() } }
            }
        }
    }

    /** Number of tool calls in the current turn. */
    @Volatile private var tools = 0

    /** The owner's last message; a time stated there takes precedence over the model's ([Reminders.spoken]). */
    @Volatile var lastWords = ""

    /** The owner's message whose answer is still to be checked by [Promises]; checked at most once per message. */
    @Volatile private var asked: String? = null

    /**
     * Lines exchanged while offline (the owner's messages and the answers from the local model or the app), appended to
     * the next message for the online agent: the offline exchange lives in a different session, and without this the
     * online agent denied answers given offline.
     */
    private val offlineTalk = java.util.concurrent.CopyOnWriteArrayList<String>()

    /** Marks the start of the offline transcript the app appends to an owner's message; stripped before display by [said]. */
    const val OFFLINE_MARK = "\n\n(A note from the app: while there was no internet"

    /** Returns the owner's [text] followed by the last offline lines in [talk], if any (each line truncated, at most 12). */
    fun withOffline(text: String, talk: List<String>): String =
        if (talk.isEmpty()) text
        else text + OFFLINE_MARK + ", you answered on the phone's small model; that is this same conversation:\n" +
            talk.takeLast(12).joinToString("\n") { it.take(300) } + "\nOwn it as yours: if something you said then was wrong, say so plainly.)"

    /** The socket to the local model's agent, opened on the first offline turn. */
    @Volatile private var local: ChatSession? = null

    /** Time the current turn was sent to the local model; 0 when it went to the cloud. */
    @Volatile private var localSince = 0L

    private lateinit var app: Context

    /** Returns the seconds elapsed in the current local-model turn, or `null` when none is in progress. */
    fun localSeconds(): Long? = localSince.takeIf { it > 0 }?.let { (System.currentTimeMillis() - it) / 1000 }

    /**
     * Sends [text] to the `owner` agent, or, when offline answers are enabled and there is no network, to the local
     * model's agent on a background thread (starting the engine first if needed). [check] marks the [Promises] follow-up
     * note. Returns `false` when the message could not be sent.
     */
    fun send(text: String, check: Boolean = false): Boolean {
        val owners = !text.startsWith(Phrases.NOTE)
        if (owners) { asked = text; tools = 0; lastWords = text }
        // Offline, app-generated notes (a greeting, a catch-up) are dropped: only the owner's messages and the
        // promise check's note are worth the local model's time.
        if (!Offline.online && !owners && !check) return false
        if (Offline.now()) {
            thread(name = "owner-chat-local") {
                // Timed from the request, including engine startup, since the owner waits for all of it.
                localSince = System.currentTimeMillis()
                // Explicit reminder or alarm requests are handled by the app directly ([Offline.direct]), without the model.
                if (owners) offlineTalk += "Owner: $text"
                if (owners) Offline.direct(text)?.let { said ->
                    offlineTalk += "You: $said"
                    asked = null; retry = null; localSince = 0L
                    Ui.thinking = false
                    Ui.add(Line(Line.Who.ASSISTANT, said, from = Offline.AGENT))
                    onAnswer?.invoke(said)
                    return@thread
                }
                if (owners) retry = text
                val ok = Offline.ensure(app) && (local ?: openLocal())?.let { runCatching { it.send(Offline.toSmall(text)) }.isSuccess } == true
                if (ok) {
                    val since = localSince
                    // A turn that exceeds [LOCAL_TURN_MS] is reported, not retried: the engine is still busy with it.
                    thread(name = "owner-chat-watch") {
                        Thread.sleep(LOCAL_TURN_MS)
                        if (localSince == since) { localSince = 0; retry = null; failLocal(slow = true) }
                    }
                }
                else { Ui.thinking = false; Ui.add(Line(Line.Who.NOTE, t(R.string.ownerchat_without_internet_i_couldn_t_think))) }
            }
            return true
        }
        localSince = 0L
        // Past the owner's monthly budget no cloud turn starts: the basics handled in code still answer.
        if (owners && Budget.reached()) {
            asked = null
            thread(name = "owner-chat-budget") {
                val said = Offline.direct(text) ?: t(R.string.budget_reached_in_chat, Usage.usd(Guard.modelBudget))
                Ui.thinking = false
                Ui.add(Line(Line.Who.ASSISTANT, said))
                onAnswer?.invoke(said)
            }
            return true
        }
        if (!owners && Budget.reached()) return false
        // Offline without the local model: answer with [Offline.limited] (what is unavailable and how to enable it) instead of failing.
        if (!Offline.online) {
            asked = null
            Ui.thinking = false
            Ui.add(Line(Line.Who.ASSISTANT, Offline.limited))
            onAnswer?.invoke(Offline.limited)
            return true
        }
        val talk = if (owners) offlineTalk.toList() else emptyList()
        return (socket?.let { runCatching { it.send(withOffline(text, talk)) }.isSuccess } ?: false)
            .also { if (it) { offlineTalk.removeAll(talk.toSet()); lastFrame = System.currentTimeMillis(); saidSlow = false; turnAt = lastFrame } }
    }

    /** The owner's message of the current local-model turn, while a single retry is still available. */
    @Volatile private var retry: String? = null

    /** Resends the turn's message once in a new session; returns `false` when it was already retried. */
    private fun retryLocal(): Boolean {
        val words = retry ?: return false
        retry = null
        Log.w(AssistantService.TAG, "chat: the small model failed; once more")
        forgetLocal()
        thread(name = "owner-chat-retry") {
            Thread.sleep(300); Ui.thinking = true
            if (!(openLocal()?.let { runCatching { it.send(Offline.toSmall(words, again = true)) }.isSuccess } ?: false)) failLocal()
        }
        return true
    }

    private fun failLocal(slow: Boolean = false) {
        Ui.thinking = false
        val said = if (slow) t(R.string.ownerchat_without_internet_i_m_thinking_too)
        else t(R.string.ownerchat_without_internet_i_couldn_t_answer)
        Ui.add(Line(Line.Who.ASSISTANT, said))
        onAnswer?.invoke(said)
        onError?.invoke()
    }

    /**
     * Discards the local model's session so each offline period starts fresh: a small model tends to repeat its earlier
     * answers, and one wrong answer in its history was repeated for every following turn.
     */
    fun forgetLocal() {
        if (!::app.isInitialized) return
        AssistantService.prefs(app).edit().remove("session-${Offline.AGENT}").apply()
        local?.let { runCatching { it.close() } }
        local = null
    }

    /** Opens the local model's socket and waits up to ~10 s for it. */
    private fun openLocal(): ChatSession? {
        thread(name = "owner-chat-offline") { connect(app, Offline.AGENT) }
        repeat(50) { local?.let { return it }; Thread.sleep(200) }
        return local
    }

    /** Starts a new session (debug: `--ez fresh true`); ZeroClaw keeps the old one. */
    fun fresh(context: Context) {
        AssistantService.prefs(context).edit().remove("session").apply()
        socket?.close()
    }

    private fun connect(context: Context, agent: String) {
        // Reuses the stored session id so ZeroClaw keeps the conversation history across connections.
        val prefs = AssistantService.prefs(context)
        val main = agent == "owner"
        val key = if (main) "session" else "session-$agent"
        Turns.open(context, agent, session = prefs.getString(key, null), onFailure = {
            if (main) { socket = null; Ui.ready = false } else local = null
            Ui.thinking = false
            // Only the owner socket reconnects automatically; the local model's socket is reopened on demand.
            if (main) { Thread.sleep(1000); connect(context, agent) }
        }) { chat, frame ->
            if (main) { lastFrame = System.currentTimeMillis(); if (frame.type == FrameType.DONE || frame.type == FrameType.ERROR) turnAt = 0L }
            when (frame.type) {
                FrameType.SESSION_START -> {
                    frame.sessionId.takeIf { it.isNotEmpty() }?.let { id ->
                        prefs.edit().putString(key, id).apply()
                        if (main && frame.resumed && Ui.chat.isEmpty()) history(context, id)
                    }
                    if (main) { socket = chat; Ui.ready = true } else local = chat
                }
                FrameType.TOOL_CALL -> {
                    tools++
                    if (frame.name.substringAfter("__") in PhoneMcp.OTHERS_WORDS) Guard.heardOthers = true
                    Ui.add(Line(Line.Who.TOOL, Labels.tool(frame.name, (frame.args as? JsonObject)?.let { JSONObject(it.toString()) })))
                }
                FrameType.DONE -> frame.done?.fullResponse.orEmpty().let {
                    // The local model emitted a tool call as plain text instead of a call: hide it and retry once.
                    if (!main && Offline.rawCall(it) && retryLocal()) return@let
                    // The answer claims an action but no tool ran, so nothing was done. The answer is withheld and the agent
                    // gets a correction note; its next answer (after acting, or restating) is the one shown to the owner.
                    val checked = asked
                    asked = null
                    if (checked != null && tools == 0 && runCatching { Promises.claims(it) }.getOrDefault(false)) {
                        Log.w(AssistantService.TAG, "chat: a promise without a tool: ${AssistantService.logged(it)}")
                        if (send(Promises.note(Policy.owner, it), check = true)) return@let
                    }
                    Ui.thinking = false
                    if (!main && localSince > 0) { Offline.answered(context, System.currentTimeMillis() - localSince); localSince = 0; retry = null }
                    if (!main) offlineTalk += "You: $it"
                    Ui.add(Line(Line.Who.ASSISTANT, it, from = if (main) null else Offline.AGENT))
                    onAnswer?.invoke(it)
                }
                FrameType.ERROR -> {
                    // The local model sometimes returns nothing after a tool call and the turn fails: retry the same
                    // message once in a new session.
                    if (!main) { if (!retryLocal()) failLocal(); return@open }
                    Ui.thinking = false
                    Ui.add(Line(Line.Who.NOTE, t(R.string.ownerchat_something_failed) + frame.message))
                    onError?.invoke()
                }
            }
        }
    }

    /** Restores the last [HISTORY] lines from ZeroClaw's session when the app restarts with an empty chat. */
    private fun history(context: Context, session: String) = thread {
        val token = AssistantService.token(context) ?: return@thread
        val lines = runCatching { runBlocking { AssistantService.client(token).sessions.messages(session) } }
            .onFailure { Log.w(AssistantService.TAG, "chat history: ${it.message}") }.getOrNull().orEmpty()
            .mapNotNull { m ->
                when (m.role) {
                    // Restored as the owner wrote it (without the timestamp prefix ZeroClaw stores); app-generated notes
                    // (a greeting request, the setup question) are not the owner's and are skipped.
                    "user" -> said(m.content).takeUnless { it.startsWith(Phrases.NOTE) }?.let { Line(Line.Who.ME, it) }
                    "assistant" -> m.content.takeIf { it.isNotBlank() }?.let { Line(Line.Who.ASSISTANT, it) }
                    else -> null
                }
            }
            .takeLast(HISTORY)
        Log.i(AssistantService.TAG, "chat history: ${lines.size} lines from the session")
        if (lines.isNotEmpty()) Ui.prepend(lines)
    }

    /** Returns a stored message as sent: without ZeroClaw's "[CURRENT DATE & TIME: …]" prefix and the offline transcript. */
    fun said(stored: String) = stored.replaceFirst(Regex("""^\s*\[CURRENT DATE & TIME:[^\]]*]\s*"""), "").trimStart().substringBefore(OFFLINE_MARK)

    private const val LOCAL_TURN_MS = 180_000L

    /** A cloud turn with no frame for this long says it is still working. */
    private const val SLOW_MS = 25_000L

    /** A cloud turn with no frame for this long is given up. Long enough for a slow tool (a photo search labels images). */
    private const val SILENT_MS = 90_000L

    /** Number of lines restored on start. */
    private const val HISTORY = 30
}

/**
 * Detects answers that claim an action was or will be taken (a reminder set, a message sent, a call scheduled). When no
 * tool ran in that turn, nothing was done, so [OwnerChat] withholds the answer: a missed reminder is worse than a
 * delayed answer.
 */
object Promises {
    private val claim = Regex(
        // Includes claims of having shown or opened something (e.g. a settings screen) when nothing was opened.
        Lexicon["promises.claim"],
        RegexOption.IGNORE_CASE,
    )

    fun claims(answer: String) = claim.containsMatchIn(answer)

    /** Builds the correction note sent to the agent when [answer] claims an action but no tool ran. */
    fun note(owner: String, answer: String) =
        "${Phrases.NOTE}, not from $owner.) Your answer said something was done or will be done (\"${answer.take(160)}\"), but no tool ran in this " +
            "turn, so nothing was done: no reminder, message or change exists. If $owner asked for an action, do it now with the " +
            "right tool and then confirm it; if it was only conversation, give your answer again as it was. Do not mention this note."
}
