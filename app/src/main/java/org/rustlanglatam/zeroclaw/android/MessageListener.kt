package org.rustlanglatam.zeroclaw.android

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.ContactsContract
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import io.github.rustlanglatam.zeroclaw.ChatFrame
import io.github.rustlanglatam.zeroclaw.ChatSession
import io.github.rustlanglatam.zeroclaw.FrameType
import io.github.rustlanglatam.zeroclaw.statusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.Semaphore
import kotlin.concurrent.thread

/**
 * Notification listener for messaging apps: reads incoming messages from their notifications
 * (enabled by the owner in Settings › Notification access), hands each to the `third_party`
 * agent, and replies through the notification's own reply action when the policy allows it.
 * Email notifications are routed to [Mailbox] instead.
 */
class MessageListener : NotificationListenerService() {

    override fun onCreate() {
        super.onCreate()
        AssistantService.keepAlive()
    }

    /**
     * Android unbinds the listener when notification access is turned off, and occasionally for no visible reason:
     * rebinds while access is still granted; otherwise [Health] tells the owner.
     */
    override fun onListenerDisconnected() {
        running = null
        val granted = getSystemService(android.app.NotificationManager::class.java)
            .isNotificationListenerAccessGranted(android.content.ComponentName(this, MessageListener::class.java))
        if (granted) requestRebind(android.content.ComponentName(this, MessageListener::class.java))
        runCatching { Health.check(this) }.onFailure { Log.w(AssistantService.TAG, "health check: ${it.message}") }
    }

    override fun onListenerConnected() {
        running = this
        Guard.load(filesDir)
        // Lets a message that got no answer (e.g. no connection) be answered again while its conversation is still known.
        retry = { entry ->
            Inbox.status(entry, Inbox.Status.RETRYING)
            Turns.thirdParty(this, entry.sender, entry.text) { answer -> answer(entry.conversation, entry.sender, entry, answer) }
        }
        runCatching { startForegroundService(Intent(this, AssistantService::class.java)) }
            .onFailure { Log.w(AssistantService.TAG, "assistant not started from the listener: ${it.message}") }
        // Notifications posted while the listener was disconnected (phone off, app closed) and still shown are
        // processed now as if just posted; messages already seen are skipped by Inbox.accept.
        runCatching { activeNotifications.orEmpty().forEach(::onNotificationPosted) }
            .onFailure { Log.w(AssistantService.TAG, "notifications shown before: ${it.message}") }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (isEmail(sbn)) return mail(sbn)
        val extras = sbn.notification.extras
        // Some apps name the phone's user as the sender of their own messages instead of leaving it empty.
        // The typed getters are API 33; these work from API 30 on.
        @Suppress("DEPRECATION") val me = extras.getParcelable<android.app.Person>(Notification.EXTRA_MESSAGING_PERSON)?.name?.toString()
        @Suppress("DEPRECATION") val bundles = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        val raw = Notification.MessagingStyle.Message
            .getMessagesFromBundleArray(bundles)
            .map { Inbox.Raw(it.senderPerson?.name?.toString()?.takeIf { name -> name != me }, it.text?.toString().orEmpty(), it.timestamp, it.senderPerson?.let { p -> p.uri ?: p.key }) }
        if (raw.isEmpty()) return
        val group = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION)
        val messages = Inbox.accept(sbn.packageName, sbn.key, group, raw)
        if (messages.isEmpty()) {
            // No new messages within 20 s of a reply: the app re-posting the assistant's reply, already in the activity log.
            if ((answered[sbn.key] ?: 0L) > System.currentTimeMillis() - 20_000) { taken += sbn.key; cancelNotification(sbn.key) }
            return
        }
        replyAction(sbn.notification)?.let { replies[sbn.key] = it } ?: replies.remove(sbn.key)
        keep("msg:${sbn.key}", sbn.notification)
        Gestures.play(Gesture.NOTICE)
        takeOver(sbn)
        for (message in messages) {
            // The sender is resolved through the contacts, never from the displayed name, which anyone can set to a contact's.
            val sender = sender(this, message.sender, message.who)
            val entry = Inbox.received(message.copy(sender = sender))
            // Kill switch on: no turn runs (the daemon is stopped too); the message stays unanswered for the owner.
            if (Guard.policy.stopped) {
                Guard.journal.add(Principal.THIRD_PARTY, "reply to $sender", "refused", "kill switch: paused")
                Inbox.status(entry, Inbox.Status.PAUSED)
                continue
            }
            // A possible scam is pointed out to the owner; the usual rules still decide any reply (strangers get none).
            runCatching { Scam.warn(this, sender, message.text, Policy.groups[sender].orEmpty().isNotEmpty() && !Identity.unverified(sender)) }
            if (busy(message, sender, entry)) continue
            Turns.thirdParty(this, sender, message.text) { answer -> answer(message.conversation, sender, entry, answer) }
        }
    }

    /**
     * In busy mode ([Busy]): tells a family or work contact once, with the fixed reply in their language, and keeps their
     * later messages for the owner without an agent turn. Returns `false` when busy mode is off or the policy refuses
     * the reply (anyone else), so the usual turn runs.
     */
    private fun busy(message: Inbox.Message, sender: String, entry: Inbox.Entry): Boolean {
        val period = Busy.now() ?: return false
        val target = Policy.target(sender)
        if (target.groups.isEmpty()) return false
        if (!period.owes(sender)) { Inbox.status(entry, Inbox.Status.HELD_BACK); return true }
        val until = java.time.Instant.ofEpochMilli(period.until).atZone(java.time.ZoneId.systemDefault()).toLocalTime().withSecond(0).withNano(0).toString()
        val lang = if (Inbox.english(message.text)) Lang.EN else Lang.ES
        val text = Inbox.withIntro(message.conversation, Busy.reply(lang, period.reason, Policy.owner, until))
        val result = Guard.attempt(Principal.THIRD_PARTY, Capability.BUSY_REPLY, target, Act.busyReply(entry.id, sender, text)) {
            if (reply(message.conversation, text)) "Told $sender the owner is busy." else "NOT sent: the notification has no reply action or is gone"
        }
        return when (result) {
            is Guard.Outcome.Done -> { Busy.told(sender); Inbox.status(entry, if (result.text.startsWith("NOT")) Inbox.Status.COULD_NOT_ANSWER else Inbox.Status.ANSWERED); true }
            else -> false
        }
    }

    private fun isEmail(sbn: StatusBarNotification) =
        sbn.packageName != packageName && sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 &&
            (sbn.notification.category == Notification.CATEGORY_EMAIL || sbn.packageName in Mailbox.EMAIL_APPS)

    /** Handles an email notification: stores it in [Mailbox] for the agent and, when classified as important, notifies the owner via [MailNotice]. */
    private fun mail(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val subject = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val big = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val body = big.removePrefix(subject).trim()
        val mail = Mailbox.receive(sbn.packageName, sbn.key, title, subject, body, sbn.notification.`when`, Policy.groups) { address ->
            saved(this, Identity.Clue.Email(address))
        } ?: return
        // The email app's content intent for this email, kept in memory for the process lifetime so the email can be opened later.
        sbn.notification.contentIntent?.let { mailLinks[mail.id] = it }
        keep("mail:${mail.id}", sbn.notification)
        runCatching { Scam.warn(this, mail.from, "${mail.subject}\n${mail.body}", Policy.groups[mail.from].orEmpty().isNotEmpty()) }
        // A bill with a due date or a parcel with a delivery date is offered as a reminder, on the owner's tap.
        runCatching { Shares.fromMail(this, mail) }
        Log.i(AssistantService.TAG, "mail #${mail.id}: " + AssistantService.logged("from ${mail.from}: ${mail.subject}") + (mail.why?.let { " (important)" } ?: ""))
        Gestures.play(if (mail.important) Gesture.PERK else Gesture.READ)
        Ui.refresh()
        takeOver(sbn)
        if (mail.important) MailNotice.announce(this, mail)
    }

    /**
     * While the app is in the foreground, cancels the source app's notification (heads-up and sound) and plays
     * [Chime] instead. The message stays in the activity log, the email in the email app, and the reply action
     * is kept (see [taken]).
     */
    private fun takeOver(sbn: StatusBarNotification) {
        if (!Floating.appVisible) return
        taken += sbn.key
        cancelNotification(sbn.key)
        Chime.play(this)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (!taken.remove(sbn.key)) replies.remove(sbn.key)
    }

    private fun appLabel(pkg: String) = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)

    /** Sends the agent's [answer] back as far as the policy allows and records the outcome as the [Inbox] entry's status. */
    private fun answer(conversation: String, sender: String, entry: Inbox.Entry, answer: String?) {
        if (answer.isNullOrBlank()) return Inbox.status(entry, Inbox.Status.NO_ANSWER)
        Inbox.status(entry, Inbox.Status.WAITING)
        val text = Inbox.withIntro(conversation, answer.trim())
        val result = Guard.attempt(Principal.THIRD_PARTY, Capability.NOTIFICATION_REPLY, Policy.target(sender), Act.reply(entry.id, sender, appLabel(entry.app), text)) {
            val sent = reply(conversation, text)
            Inbox.status(entry, if (sent) Inbox.Status.ANSWERED else Inbox.Status.COULD_NOT_ANSWER)
            if (sent) "Replied to $sender." else "cannot reply: the notification has no reply action or is gone".also { Guard.onNotify(t(R.string.messagelistener_i_couldn_t_answer_x, sender)) }
        }
        when (result) {
            is Guard.Outcome.Refused -> Inbox.status(entry, when {
                result.expired -> Inbox.Status.EXPIRED
                result.notApproved -> Inbox.Status.NOT_APPROVED
                else -> Inbox.Status.NO_ANSWER
            })
            is Guard.Outcome.Queued -> Inbox.status(entry, Inbox.Status.QUEUED)
            is Guard.Outcome.Done -> Unit
        }
    }

    /** Fills the conversation's reply action with [text] and fires it, as the user's own reply would. */
    private fun reply(conversation: String, text: String): Boolean {
        val (action, input) = replies[conversation] ?: return false
        val intent = Intent()
        RemoteInput.addResultsToIntent(arrayOf(input), intent, Bundle().apply { putCharSequence(input.resultKey, text) })
        RemoteInput.setResultsSource(intent, RemoteInput.SOURCE_FREE_FORM_INPUT)
        return runCatching { action.send(this, 0, intent) }.isSuccess.also { if (it) answered[conversation] = System.currentTimeMillis() }
    }

    private fun replyAction(notification: Notification): Pair<PendingIntent, RemoteInput>? =
        notification.actions.orEmpty().firstNotNullOfOrNull { action ->
            action.remoteInputs?.firstOrNull { it.allowFreeFormInput }?.let { action.actionIntent to it }
        }

    /** Stores the notification's plain action buttons (e.g. archive, mark as read) under [key]; actions that take text input are skipped. */
    private fun keep(key: String, notification: Notification) {
        notification.actions.orEmpty().filter { it.remoteInputs.isNullOrEmpty() && it.title != null && it.actionIntent != null }
            .takeIf { it.isNotEmpty() }?.let { buttons[key] = it }
    }

    companion object {
        @Volatile private var running: MessageListener? = null

        /**
         * Returns the conversation keys of the messaging notifications on screen now, or `null` while the listener is
         * not connected. A conversation the owner opened is usually cleared by its app, so it is no longer here.
         */
        fun shownConversations(): Set<String>? = running?.let { l -> runCatching { l.activeNotifications.orEmpty().map { it.key }.toSet() }.getOrNull() }

        /** Notification action buttons of an email or a conversation, keyed by `mail:<id>` or `msg:<conversation>`, for the agent to press. */
        private val buttons = ConcurrentHashMap<String, List<Notification.Action>>()

        /** Returns the button titles as the source app names them; empty when the notification had none. */
        fun buttonsOf(key: String): List<String> = buttons[key].orEmpty().map { it.title.toString() }

        /**
         * Presses the button whose normalized title contains [what] (or is contained in it), as the owner would from
         * the notification shade. Returns the button's title, or `null` when none matches or it can no longer be sent.
         */
        fun press(context: Context, key: String, what: String): String? {
            val want = Commands.normalize(what)
            val action = buttons[key].orEmpty().firstOrNull { Commands.normalize(it.title.toString()).let { t -> want in t || t in want } } ?: return null
            return if (runCatching { action.actionIntent.send(context, 0, null) }.isSuccess) action.title.toString().also { buttons.remove(key) } else null
        }

        /**
         * Sends [text] in [conversation] through its notification's reply action, as a reply typed in the shade would.
         * Returns `false` when the conversation currently has no reply action (its notification is gone).
         */
        fun replyTo(context: Context, conversation: String, text: String): Boolean {
            val (action, input) = replies[conversation] ?: return false
            val intent = Intent()
            RemoteInput.addResultsToIntent(arrayOf(input), intent, Bundle().apply { putCharSequence(input.resultKey, text) })
            RemoteInput.setResultsSource(intent, RemoteInput.SOURCE_FREE_FORM_INPUT)
            return runCatching { action.send(context, 0, intent) }.isSuccess.also { if (it) answered[conversation] = System.currentTimeMillis() }
        }

        /** Answers again a message that got no answer; `null` while the listener is not connected. */
        @Volatile var retry: ((Inbox.Entry) -> Unit)? = null

        /** Content intents that open each email in its app (the notification's tap action), by [Mailbox.Mail.id]. */
        val mailLinks = ConcurrentHashMap<Int, PendingIntent>()

        /** Each conversation's reply action, from its latest notification. */
        private val replies = ConcurrentHashMap<String, Pair<PendingIntent, RemoteInput>>()

        /** Keys of notifications the app cancelled itself; their reply action is kept when they are removed. */
        private val taken = ConcurrentHashMap.newKeySet<String>()

        /** Time of the last reply sent in each conversation; a notification re-posted shortly after is the app echoing that reply. */
        private val answered = ConcurrentHashMap<String, Long>()
    }
}

/**
 * Returns a message's sender as named in the owner's contacts (ADR-0019): the saved contact's name when the
 * notification's [who] (or a phone number given as [name]) resolves to one; otherwise [name], unverified.
 */
fun sender(context: Context, name: String, who: String?): String = Identity.named(name, Identity.clue(who, name)?.let { saved(context, it) })

/** Returns the saved contact's display name for [clue], or `null` when there is no match or no contacts permission. */
fun saved(context: Context, clue: Identity.Clue): String? {
    if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
    val (uri, column) = when (clue) {
        is Identity.Clue.Number -> android.net.Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, android.net.Uri.encode(clue.number)) to ContactsContract.PhoneLookup.DISPLAY_NAME
        is Identity.Clue.Email -> android.net.Uri.withAppendedPath(ContactsContract.CommonDataKinds.Email.CONTENT_LOOKUP_URI, android.net.Uri.encode(clue.address)) to ContactsContract.Contacts.DISPLAY_NAME
        is Identity.Clue.Contact -> android.net.Uri.parse(clue.uri) to ContactsContract.Contacts.DISPLAY_NAME
    }
    return runCatching {
        context.contentResolver.query(uri, arrayOf(column), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()
}

/** Returns the contact name for a phone number given by the owner, so contact group rules apply; other input is returned unchanged. */
fun contactName(context: Context, sender: String): String {
    if (!sender.any { it.isDigit() } ||
        context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED
    ) return sender
    val uri = android.net.Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, android.net.Uri.encode(sender))
    return context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
        ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: sender
}

/** Runs agent turns; `third_party` turns run one at a time so the tools know which sender the current turn serves. */
object Turns {

    /** Sink for turn progress lines; logs by default and is replaced by the UI while the app is open. */
    @Volatile var show: (String) -> Unit = { Log.i(AssistantService.TAG, "turn: ${AssistantService.logged(it)}") }

    private val one = Semaphore(1)

    /** The daemon's error for the last failed [scribe] turn; `null` after one that did not fail. */
    @Volatile var scribeError: String? = null

    /**
     * Hands [text] from [sender] to the `third_party` agent in a new session; [done] receives the answer, or
     * `null` on failure or timeout. The message is framed as data: the agent's instructions and tools decide
     * what happens, not the message.
     */
    fun thirdParty(context: Context, sender: String, text: String, done: (String?) -> Unit = {}) = thread {
        // Past the owner's monthly budget no cloud turn starts; the message stays unanswered in Activity.
        if (Budget.reached()) {
            Guard.journal.add(Principal.THIRD_PARTY, "reply to $sender", "not started", "the owner's monthly budget is reached")
            return@thread done(null)
        }
        one.acquire()
        Guard.thirdPartySender = sender
        // Each turn has its own completion flag, and the semaphore is released before [done] runs: a reply waiting
        // up to a minute for the owner's approval must not block the next turn.
        val finished = java.util.concurrent.atomic.AtomicBoolean()
        fun finish(answer: String?) {
            if (!finished.compareAndSet(false, true)) return
            Guard.thirdPartySender = null
            one.release()
            done(answer)
        }
        show("← $sender: $text")
        val turn = open(context, "third_party", onFailure = { finish(null) }) { chat, frame ->
            when (frame.type) {
                FrameType.SESSION_START -> chat.send(Guard.inert("$sender wrote to ${Policy.owner}:\n$text\n\n(Your answer goes to $sender.)"))
                FrameType.TOOL_CALL -> show("  · ${frame.name} ${frame.args}")
                FrameType.DONE -> { val answer = frame.done?.fullResponse.orEmpty(); show("→ $sender: $answer"); chat.close(); finish(answer) }
                FrameType.ERROR -> { show("Error: ${frame.message}"); chat.close(); finish(null) }
            }
        }
        // A turn that never ends (provider unreachable, stalled connection) must not block the queue.
        timer.schedule({ if (!finished.get()) { show("Error: no answer in ${THIRD_PARTY_MS / 1000} s"); turn.cancel(); finish(null) } }, THIRD_PARTY_MS, TimeUnit.MILLISECONDS)
    }

    /**
     * Runs a turn not typed by the owner (a news briefing, the catch-up summary, reading an email aloud) on the
     * `scribe` agent, which has no tools, so third-party text in [prompt] cannot trigger actions (ADR-0018).
     * Uses its own session; [done] receives the answer, or `null` on failure or timeout. An [image] (a picture's path)
     * is attached as ZeroClaw's `[IMAGE:…]` marker; [agent] is the scribe, or the seer on the photos model.
     */
    fun scribe(context: Context, prompt: String, image: String? = null, agent: String = AssistantService.SCRIBE, done: (String?) -> Unit) {
        scribeError = null
        // Past the owner's monthly budget the caller uses its own plain text.
        if (Budget.reached()) return done(null)
        val finished = java.util.concurrent.atomic.AtomicBoolean()
        fun finish(answer: String?) { if (finished.compareAndSet(false, true)) done(answer) }
        val turn = open(context, agent, onFailure = { finish(null) }) { chat, frame ->
            when (frame.type) {
                FrameType.SESSION_START -> chat.send(Guard.inert(prompt) + image?.let { "\n[IMAGE:$it]" }.orEmpty())
                FrameType.DONE -> { chat.close(); finish(frame.done?.fullResponse.orEmpty()) }
                FrameType.ERROR -> { Log.w(AssistantService.TAG, "scribe turn failed: ${frame.message}"); scribeError = frame.message; chat.close(); finish(null) }
            }
        }
        // On timeout the caller (a briefing, the catch-up) falls back to its own text.
        timer.schedule({ if (!finished.get()) { Log.w(AssistantService.TAG, "scribe turn: no answer in ${OWNER_MS / 1000} s"); turn.cancel(); finish(null) } }, OWNER_MS, TimeUnit.MILLISECONDS)
    }

    /** Schedules turn timeouts without a sleeping thread per turn. */
    private val timer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { Thread(it, "turn-timer").apply { isDaemon = true } }

    /**
     * Opens a chat with [agent] over the gateway's `/ws/chat`, waiting until the app is paired. [onFrame] receives
     * every frame, starting with the session start; [onFailure] runs when the chat cannot be opened or when it
     * ends. A rejected token (HTTP 401) triggers pairing again.
     */
    fun open(context: Context, agent: String, session: String? = null, onFailure: () -> Unit = {}, onFrame: (ChatSession, ChatFrame) -> Unit): Job =
        scope.launch {
            var token = AssistantService.token(context)
            while (token == null) {
                delay(500)
                token = AssistantService.token(context)
            }
            val chat = try {
                AssistantService.client(token).chat(agent, session)
            } catch (e: Throwable) {
                if (e.statusCode == 401) runCatching { AssistantService.pairingTaken(context); AssistantService.pair(context, refused = token) }
                    .onFailure { Log.w(AssistantService.TAG, "pairing again failed: ${it.message}") }
                return@launch onFailure()
            }
            runCatching {
                // The client already read the session's start; it is handed on as the first frame.
                onFrame(chat, ChatFrame(FrameType.SESSION_START, sessionId = chat.sessionId, resumed = chat.resumed, messageCount = chat.messageCount))
                while (true) onFrame(chat, chat.next() ?: break)
            }
            chat.close()
            onFailure()
        }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
        Log.e(AssistantService.TAG, "chat turn failed", e)
    })

    private const val THIRD_PARTY_MS = 180_000L
    private const val OWNER_MS = 120_000L
}
