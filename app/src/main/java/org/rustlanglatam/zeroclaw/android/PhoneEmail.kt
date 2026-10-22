package org.rustlanglatam.zeroclaw.android

import com.sun.mail.imap.IMAPFolder
import com.sun.mail.imap.IMAPStore
import android.content.Context
import android.util.Log
import java.util.Properties
import javax.mail.AuthenticationFailedException
import javax.mail.Flags
import javax.mail.Folder
import javax.mail.Message
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.UIDFolder
import javax.mail.internet.MimeMessage

/**
 * Performs the app-side operations on the owner's email accounts ([EmailAccounts]) over IMAP and SMTP: verifying
 * credentials, watching the inbox, marking read, archiving, deleting and sending.
 *
 * Messages are identified by their INBOX UID, as returned by ZeroClaw's `email_search`. All calls block on the network
 * and run on tool or watcher threads, never on the main thread.
 */
object PhoneEmail {
    private fun session(account: EmailAccounts.Account) = Session.getInstance(Properties().apply {
        put("mail.imaps.connectiontimeout", "15000"); put("mail.imaps.timeout", "20000")
        put("mail.smtp.host", account.smtp); put("mail.smtp.port", "${account.smtpPort}"); put("mail.smtp.auth", "true")
        // Port 465 is implicit TLS; 587 (iCloud and many others) starts in plain text and must upgrade with STARTTLS.
        if (account.smtpPort == 465) put("mail.smtp.ssl.enable", "true") else put("mail.smtp.starttls.required", "true")
        put("mail.smtp.connectiontimeout", "15000"); put("mail.smtp.timeout", "20000")
    })

    private fun <T> inbox(account: EmailAccounts.Account, work: (IMAPStore, IMAPFolder) -> T): T {
        val store = session(account).getStore("imaps") as IMAPStore
        store.connect(account.imap, account.imapPort, account.address, account.password)
        try {
            val inbox = store.getFolder("INBOX") as IMAPFolder
            inbox.open(Folder.READ_WRITE)
            try { return work(store, inbox) } finally { if (inbox.isOpen) inbox.close(true) }
        } finally { store.close() }
    }

    private val watching: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * Starts one daemon thread per connected account that waits for new email with IMAP IDLE. This matters most for
     * accounts that no email app on the phone notifies about.
     *
     * The inbox is opened read-only so nothing is marked as read. Each new message goes into [Mailbox] with its UID and is
     * announced when classified as important, as for a notification. A thread ends when its account is removed; a dropped
     * connection is retried with exponential backoff (5 s up to 5 min) and waits while [Offline.online] is false.
     */
    fun watch(context: Context) = EmailAccounts.accounts.forEach { first ->
        if (!watching.add(first.alias)) return@forEach
        kotlin.concurrent.thread(name = "email-watch-${first.alias}", isDaemon = true) {
            var pause = 5_000L
            while (true) {
                val account = EmailAccounts.accounts.firstOrNull { it.alias == first.alias } ?: break
                if (!Offline.online) { Thread.sleep(10_000); continue }
                runCatching { idle(context, account) { pause = 5_000L } }
                    .onFailure { Log.w(AssistantService.TAG, "email watch ${account.alias}: ${it.javaClass.simpleName}") }
                Thread.sleep(pause)
                pause = (pause * 2).coerceAtMost(5 * 60_000L)
            }
            watching.remove(first.alias)
        }
    }

    /**
     * Holds one IDLE connection open, delivering new messages until the connection drops or the account changes.
     * Calls [up] once the inbox is open.
     */
    private fun idle(context: Context, account: EmailAccounts.Account, up: () -> Unit) {
        // Servers end an IDLE after about 29 minutes (RFC 2177; Gmail does): the socket times out at 25 minutes and the
        // caller reconnects. `peek` keeps fetches from setting \Seen.
        val session = Session.getInstance(Properties().apply {
            put("mail.imaps.connectiontimeout", "15000"); put("mail.imaps.timeout", "${25 * 60_000}"); put("mail.imaps.peek", "true")
        })
        val store = session.getStore("imaps") as IMAPStore
        store.connect(account.imap, account.imapPort, account.address, account.password)
        try {
            val inbox = store.getFolder("INBOX") as IMAPFolder
            inbox.open(Folder.READ_ONLY)
            var next = inbox.uidNext
            up()
            // Loops while the account exists with the same password; a change reconnects, a removal ends the thread.
            while (EmailAccounts.accounts.any { it.alias == account.alias && it.password == account.password }) {
                inbox.idle(true)
                val fresh = inbox.getMessagesByUID(next, UIDFolder.LASTUID).filter { inbox.getUID(it) >= next }
                fresh.forEach { arrived(context, account, inbox.getUID(it), it) }
                next = maxOf(next, inbox.uidNext)
            }
        } finally { runCatching { store.close() } }
    }

    private fun arrived(context: Context, account: EmailAccounts.Account, uid: Long, message: Message) {
        val from = (message.from?.firstOrNull() as? InternetAddress)?.let { a -> if (a.personal.isNullOrBlank()) a.address else "\"${a.personal}\" <${a.address}>" } ?: return
        val body = runCatching { text(message) }.getOrNull().orEmpty().take(2000)
        val at = (message.receivedDate ?: message.sentDate)?.time ?: System.currentTimeMillis()
        // Gmail accounts present on the phone open in the Gmail app; others open in the app's own viewer (MainActivity.openMail).
        val app = if (account.provider == EmailAccounts.Provider.GMAIL && account.onPhone) "com.google.android.gm" else "email"
        val mail = Mailbox.receive(app, "imap:${account.alias}:$uid", from, message.subject.orEmpty(), body, at, Policy.groups,
            uid = uid, account = account.alias, contactOf = { address -> saved(context, Identity.Clue.Email(address)) }) ?: return
        Log.i(AssistantService.TAG, "mail #${mail.id} (inbox): " + AssistantService.logged("from ${mail.from}: ${mail.subject}") + (mail.why?.let { " (important)" } ?: ""))
        Gestures.play(if (mail.important) Gesture.PERK else Gesture.READ)
        Ui.refresh()
        if (mail.important) MailNotice.announce(context, mail)
    }

    /** Extracts plain text: the first text/plain part, otherwise the first HTML part with tags stripped. */
    private fun text(part: Part): String? = when {
        part.isMimeType("text/plain") -> part.content as? String
        part.isMimeType("text/html") -> (part.content as? String)?.replace(Regex("<[^>]+>"), " ")?.replace(Regex("\\s+"), " ")?.trim()
        part.isMimeType("multipart/*") -> (part.content as Multipart).let { mp ->
            val parts = (0 until mp.count).map { mp.getBodyPart(it) }
            parts.firstNotNullOfOrNull { if (it.isMimeType("text/plain")) text(it) else null } ?: parts.firstNotNullOfOrNull { text(it) }
        }
        else -> null
    }

    /** Opens the account's inbox to verify it; returns `null` on success, otherwise a localized reason for the owner. */
    fun check(account: EmailAccounts.Account): String? = runCatching { inbox(account) { _, _ -> } }.exceptionOrNull()?.let {
        val who = account.provider.label.takeUnless { account.provider.other } ?: account.imap
        if (it is AuthenticationFailedException) t(R.string.phoneemail_x_did_not_accept_that_address, who)
        else t(R.string.phoneemail_i_could_not_reach_x_is, who)
    }

    private fun messages(inbox: IMAPFolder, uids: List<Long>) = uids.mapNotNull { inbox.getMessageByUID(it) }

    fun markRead(account: EmailAccounts.Account, uids: List<Long>, read: Boolean): Int = inbox(account) { _, inbox ->
        messages(inbox, uids).onEach { it.setFlag(Flags.Flag.SEEN, read) }.size
    }

    /** Finds a folder by its special-use attribute (\Archive, \Trash; RFC 6154), independent of its localized name. */
    private fun role(store: IMAPStore, role: String) =
        store.defaultFolder.list("*").map { it as IMAPFolder }.firstOrNull { role in it.attributes }

    /**
     * Removes messages from the inbox without deleting them. On Gmail, expunging from INBOX only removes the label (its
     * default behavior); other servers move them to the \Archive folder.
     *
     * @return the number of messages archived, or `-1` when the server has no archive folder.
     */
    fun archive(account: EmailAccounts.Account, uids: List<Long>): Int = inbox(account) { store, inbox ->
        if (account.provider == EmailAccounts.Provider.GMAIL) messages(inbox, uids).onEach { it.setFlag(Flags.Flag.DELETED, true) }.size
        else role(store, "\\Archive")?.let { folder -> messages(inbox, uids).also { inbox.moveMessages(it.toTypedArray(), folder) }.size } ?: -1
    }

    /** Moves messages to the \Trash folder, which servers empty after some days; throws when there is none. */
    fun trash(account: EmailAccounts.Account, uids: List<Long>): Int = inbox(account) { store, inbox ->
        val bin = role(store, "\\Trash") ?: error("no bin folder")
        messages(inbox, uids).also { inbox.moveMessages(it.toTypedArray(), bin) }.size
    }

    /**
     * Sends a plain-text message from the account's address. When [replyTo] (an INBOX UID) is given, sets `In-Reply-To`
     * and `References` so the message threads with the original.
     */
    fun send(account: EmailAccounts.Account, to: String, subject: String, body: String, replyTo: Long? = null): String {
        val s = session(account)
        val original = replyTo?.let { uid -> runCatching { inbox(account) { _, inbox -> inbox.getMessageByUID(uid)?.let { it.getHeader("Message-ID")?.firstOrNull() to (it.getHeader("References")?.firstOrNull()) } } }.getOrNull() }
        val message = MimeMessage(s).apply {
            setFrom(InternetAddress(account.address))
            setRecipients(Message.RecipientType.TO, InternetAddress.parse(to))
            setSubject(subject, "UTF-8")
            setText(body, "UTF-8")
            original?.first?.let { id -> setHeader("In-Reply-To", id); setHeader("References", listOfNotNull(original.second, id).joinToString(" ")) }
        }
        Transport.send(message, account.address, account.password)
        return "Sent to $to: \"$subject\"."
    }
}
