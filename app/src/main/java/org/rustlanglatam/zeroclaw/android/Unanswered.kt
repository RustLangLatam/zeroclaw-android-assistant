package org.rustlanglatam.zeroclaw.android

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.time.LocalDate

/**
 * What is still waiting for the owner's own answer: messages from family and work that nobody answered and whose
 * notification is still shown (a conversation the owner opened clears it), and important emails the owner has not
 * opened, after [Guard.unansweredHours]. The owner is told quietly once a day per person; the `unanswered` tool lists
 * it on request. Pure apart from [check].
 */
object Unanswered {
    /** Statuses after which a message needs nothing more from the owner. */
    private val SETTLED = setOf(Inbox.Status.ANSWERED, Inbox.Status.QUEUED)

    /**
     * Returns the family and work senders whose last message is unsettled, older than [hours] and still shown
     * ([shown]: conversation keys of the notifications on screen), with their unsettled messages, oldest first.
     */
    fun messages(entries: List<Inbox.Entry>, groups: Map<String, Set<String>>, shown: Set<String>, now: Long, hours: Int): Map<String, List<Inbox.Entry>> =
        entries.groupBy { it.sender }
            .filterKeys { groups[it].orEmpty().isNotEmpty() }
            .mapValues { (_, list) -> list.sortedBy { it.time } }
            .filterValues { list ->
                val last = list.last()
                last.status !in SETTLED && last.conversation in shown && now - last.time >= hours * 3600_000L
            }
            .mapValues { (_, list) -> list.takeLastWhile { it.status !in SETTLED } }

    /** Returns important emails not opened, older than [hours]. */
    fun mails(mails: List<Mailbox.Mail>, opened: Set<Int>, now: Long, hours: Int): List<Mailbox.Mail> =
        mails.filter { it.important && it.id !in opened && now - it.at >= hours * 3600_000L }

    /** Renders what is waiting, for the owner's agent. */
    fun describe(messages: Map<String, List<Inbox.Entry>>, mails: List<Mailbox.Mail>, format: (Long) -> String): String {
        if (messages.isEmpty() && mails.isEmpty()) return "Nothing waits for the owner's answer."
        val m = messages.entries.joinToString("\n") { (who, list) -> "${Identity.bare(who)}: ${list.size} message(s) since ${format(list.first().time)}, last «${list.last().text.take(120)}»" }
        val e = mails.joinToString("\n") { "Email from ${it.from}: «${it.subject.take(120)}» (${format(it.at)})" }
        return listOf(m, e).filter { it.isNotEmpty() }.joinToString("\n") + "\nSay it in one or two sentences, people first."
    }

    /** Posts the quiet notice for people not told today; called once a minute by the service. */
    fun check(context: Context) {
        val hours = Guard.unansweredHours
        if (hours <= 0 || !Guard.setUp || Guard.policy.stopped) return
        val shown = MessageListener.shownConversations() ?: return
        val now = System.currentTimeMillis()
        val waiting = messages(Inbox.digest.toList(), Policy.groups, shown, now, hours)
        val prefs = context.getSharedPreferences("unanswered", Context.MODE_PRIVATE)
        val today = LocalDate.now().toString()
        val told = if (prefs.getString("day", null) == today) prefs.getStringSet("told", emptySet()).orEmpty() else emptySet()
        val fresh = waiting.keys - told
        if (fresh.isEmpty()) return
        prefs.edit().putString("day", today).putStringSet("told", told + fresh).apply()
        val names = fresh.map { Identity.bare(it) }
        val text = if (names.size == 1) t(R.string.unanswered_one, names[0], hours) else t(R.string.unanswered_many, names.joinToString(", "), hours)
        val open = PendingIntent.getActivity(
            context, ID, MainActivity.sealed(Intent(context, MainActivity::class.java).putExtra("place", Place.ACTIVITY)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        context.getSystemService(NotificationManager::class.java).notify(
            ID,
            Notification.Builder(context, Chime.channel(context, "unanswered", t(R.string.unanswered_channel), NotificationManager.IMPORTANCE_LOW))
                .setSmallIcon(R.drawable.ic_mark).setContentTitle(t(R.string.unanswered_title)).setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text)).setContentIntent(open).setAutoCancel(true).build(),
        )
    }

    /** A notification id in a range no other notification uses. */
    private const val ID = 400_003
}
