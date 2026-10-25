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
     * note. With an [image] (a picture's path), [text] goes to the tool-less scribe instead ([Turns.scribe]); if that
     * fails, [blind] (the words for a model that cannot see it), or [text], is sent alone. Returns `false` when the message could not be sent.
     */
    fun send(text: String, check: Boolean = false, image: String? = null, blind: String? = null): Boolean {
        val owners = !text.startsWith(Phrases.NOTE)
        if (owners) { asked = text; tools = 0; lastWords = text }
        // Offline or in local mode, app-generated notes (a greeting, a catch-up) are dropped: only the owner's messages
        // and the promise check's note are worth the local model's time.
        if ((!Offline.online || Offline.now()) && !owners && !check) return false
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
                val ok = Offline.ensure(app) && (local ?: openLocal())?.let { runCatching { it.send(Offline.toSmall(Guard.inert(text))) }.isSuccess } == true
                if (ok) {
                    val since = localSince
                    // A turn that exceeds [LOCAL_TURN_MS] is reported, not retried: the engine is still busy with it.
                    thread(name = "owner-chat-watch") {
                        Thread.sleep(LOCAL_TURN_MS)
                        if (localSince == since) { localSince = 0; retry = null; failLocal(slow = true) }
                    }
                }
                else { Ui.thinking = false; Ui.add(Line(Line.Who.NOTE, t(if (Offline.online) R.string.ownerchat_the_phone_s_model_didn_t_start else R.string.ownerchat_without_internet_i_couldn_t_think))) }
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
        // A picture never enters the owner's session: ZeroClaw keeps it in the history and sends every later turn to the
        // model set for pictures. The scribe looks at it in a session of its own, with no tools to act on what it sees,
        // and the owner's agent hears its answer with the next message ([aside]).
        if (image != null) {
            asked = null
            fun look(again: Boolean): Unit = Turns.scribe(app, text, image) { said ->
                val why = photoFailure(Turns.scribeError, Keys.visionReady)
                when {
                    // A busy provider usually frees up in seconds: one more try before telling the owner.
                    said.isNullOrBlank() && why == R.string.photo_model_busy_x && again -> thread { Thread.sleep(BUSY_RETRY_MS); look(again = false) }
                    !said.isNullOrBlank() -> { aside = said; Ui.thinking = false; Ui.add(Line(Line.Who.ASSISTANT, said)); onAnswer?.invoke(said) }
                    // The photos model set by the owner failed: say why and offer to fix it, rather than guess from labels.
                    why != null -> {
                        val note = t(why, Keys[Keys.Key.VISION_MODEL])
                        Ui.thinking = false
                        Ui.add(Line(Line.Who.NOTE, note))
                        onAnswer?.invoke(note)
                        // Busy is temporary: nothing to change.
                        if (why != R.string.photo_model_busy_x) Cards.show(t(R.string.photo_model_failed_title), note, listOf(t(R.string.photo_model_change)),
                            mapOf(t(R.string.photo_model_change) to { Sections.open("photos_model") }))
                    }
                    else -> send("${blind ?: text}\n\n$NO_VISION").also { Guard.heardOthers = true }
                }
            }
            look(again = true)
            return true
        }
        val talk = if (owners) offlineTalk.toList() else emptyList()
        val photo = aside?.takeIf { owners }
        return (socket?.let { runCatching { it.send(Guard.inert(withOffline(text, talk) + photo?.let { "$PHOTO_MARK${it.take(1500)})" }.orEmpty())) }.isSuccess } ?: false)
            .also { if (it) { offlineTalk.removeAll(talk.toSet()); lastFrame = System.currentTimeMillis(); saidSlow = false; turnAt = lastFrame } }
            .also { if (it && photo != null) { aside = null; Guard.heardOthers = true } }
    }

    /**
     * Returns the plain-words note for a failed cloud turn: the model's settings when none is set or the provider
     * refused (a missing or wrong key), otherwise try again.
     */
    fun failure(error: String, modelReady: Boolean) =
        if (!modelReady || Regex("provider|api.?key|unauthori[sz]ed|authenticat|\\b40[13]\\b", RegexOption.IGNORE_CASE).containsMatchIn(error))
            R.string.ownerchat_i_couldn_t_reach_my_model
        else R.string.ownerchat_i_couldn_t_answer_this_time

    /**
     * Why the photos model the owner set failed, from the daemon's [error]: busy for now, the model no longer offered,
     * or the key refused; `null` when no photos model is set ([vision]) or the cause is another (then the labels explain it).
     */
    fun photoFailure(error: String?, vision: Boolean): Int? = when {
        !vision || error == null -> null
        Regex("""\b(503|429|529)\b|unavailable|high demand|overloaded|rate.?limit|resource.?exhausted""", RegexOption.IGNORE_CASE).containsMatchIn(error) -> R.string.photo_model_busy_x
        Regex("""\b404\b|not.?found|no longer available|does not exist|not supported for""", RegexOption.IGNORE_CASE).containsMatchIn(error) -> R.string.photo_model_gone_x
        Regex("""\b40[13]\b|api.?key|unauthori[sz]ed|permission""", RegexOption.IGNORE_CASE).containsMatchIn(error) -> R.string.photo_key_refused_x
        else -> null
    }

    /** The scribe's words on the last photo, for the owner's next message ([PHOTO_MARK]). */
    @Volatile private var aside: String? = null

    /** Marks the scribe's words on a photo appended to the owner's next message; stripped before display by [said]. */
    const val PHOTO_MARK = "\n\n(A note from the app: on the photo just taken, which may show other people's words, you said: "

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
        val said = when {
            slow && Offline.online -> t(R.string.ownerchat_on_the_phone_i_m_thinking_too_slowly)
            slow -> t(R.string.ownerchat_without_internet_i_m_thinking_too)
            Offline.online -> t(R.string.ownerchat_with_the_phone_s_model_i_couldn_t_answer)
            else -> t(R.string.ownerchat_without_internet_i_couldn_t_answer)
        }
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
                        if (main && frame.resumed) history(context, id, restore = Ui.chat.isEmpty())
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
                    // The daemon's error is for the log; the owner gets plain words.
                    Log.w(AssistantService.TAG, "chat: turn failed: ${frame.message}")
                    val note = t(failure(frame.message.orEmpty(), Keys.modelReady))
                    Ui.add(Line(Line.Who.NOTE, note))
                    // Said aloud too: an owner talking by voice otherwise hears nothing.
                    onAnswer?.invoke(note)
                    onError?.invoke()
                }
            }
        }
    }

    /**
     * Checks a resumed session and, when the chat is empty ([restore]), restores its last [HISTORY] lines. A session
     * holding a picture (sent into it by an earlier version) is left for a new one: ZeroClaw would send every later turn
     * there to the model set for pictures.
     */
    private fun history(context: Context, session: String, restore: Boolean) = thread {
        val token = AssistantService.token(context) ?: return@thread
        val messages = runCatching { runBlocking { AssistantService.client(token).sessions.messages(session) } }
            .onFailure { Log.w(AssistantService.TAG, "chat history: ${it.message}") }.getOrNull().orEmpty()
        if (messages.any { "[IMAGE:" in it.content }) {
            Log.w(AssistantService.TAG, "chat: the session holds a picture; a new session")
            return@thread fresh(context)
        }
        if (!restore) return@thread
        val lines = messages
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
    fun said(stored: String) = stored.replaceFirst(Regex("""^\s*\[CURRENT DATE & TIME:[^\]]*]\s*"""), "").trimStart().substringBefore(OFFLINE_MARK).substringBefore(PHOTO_MARK)
        .replace(Regex("""\s*\[IMAGE:[^\]]*]"""), "")

    private const val LOCAL_TURN_MS = 180_000L

    /** Wait before the one retry of a photo whose model was busy. */
    private const val BUSY_RETRY_MS = 4_000L

    /** Added when the scribe could not look at a photo and the owner's agent explains it from the words alone. */
    private const val NO_VISION = "(The picture could not be seen: explain from what the phone recognized and read, if anything, and say you could not see the photo itself.)"

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
