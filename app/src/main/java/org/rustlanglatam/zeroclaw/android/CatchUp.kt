package org.rustlanglatam.zeroclaw.android

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.util.Log
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.concurrent.thread

/**
 * Detects periods when the assistant was not running or was offline ([Absence]) and reports what happened meanwhile.
 *
 * A heartbeat timestamp lets the next start measure the gap and tell whether the phone was off; a default-network
 * callback detects when connectivity returns. The assistant first states why it was away, then gathers missed
 * reminders, emails, messages and upcoming events and has the agent summarize them by priority. The summary goes to
 * [onBack] when the app is visible, otherwise to a notification, and is repeated when the owner next opens the app.
 */
object CatchUp {

    /** Invoked with each report while the app is visible, so it can be spoken. */
    @Volatile var onBack: ((String) -> Unit)? = null

    /** The last catch-up summary and its timestamp; shown on the home screen for a while and persisted across restarts. */
    @Volatile var last: Pair<String, Long>? = null

    /** Whether [last] was delivered with the app open; when false, it is repeated the next time the owner opens the app. */
    @Volatile var told = true

    private val running = java.util.concurrent.atomic.AtomicBoolean()

    /** Marks the last catch-up as delivered and persists it, so a restart does not repeat it. */
    fun markTold(context: Context) { told = true; store(context).edit().putBoolean("told", true).apply() }

    /** Returns whether an offline period is currently recorded (of any length). */
    fun offline(context: Context) = store(context).getLong("offline", 0) > 0

    /** Set while an absence found at start has not been reported yet; suppresses other greetings meanwhile. */
    @Volatile var coming = false

    private fun store(context: Context) = context.getSharedPreferences("absence", Context.MODE_PRIVATE)

    /**
     * Called when the service starts: returns the absence since the last heartbeat, if any, with the reminders that fell
     * inside it (read before they are rescheduled). Starts the heartbeat and the network watch.
     *
     * @return `null` when there was no absence or when already started in this process.
     */
    fun start(context: Context): Pair<Absence.Away, List<Reminders.Reminder>>? {
        // Once per process: Android may recreate only the service, and a second heartbeat and network callback would run
        // alongside the first (any absence was already detected by then).
        if (!running.compareAndSet(false, true)) return null
        val now = System.currentTimeMillis()
        val prefs = store(context)
        // An absence detected by a previous start that died before reporting it is still pending; that start's heartbeat
        // has already overwritten "alive", so it is read from "awayFrom".
        val owed = prefs.getLong("awayFrom", 0).takeIf { it > 0 }?.let { from ->
            Absence.Away(Absence.Kind.entries.firstOrNull { it.name == prefs.getString("awayKind", null) } ?: Absence.Kind.CLOSED, from, now)
        }
        val away = owed ?: Absence.after(prefs.getLong("alive", 0), now - SystemClock.elapsedRealtime(), now)
        away?.let { prefs.edit().putLong("awayFrom", it.from).putString("awayKind", it.kind.name).commit() }
        // Known limitation: one-minute heartbeat; deep sleep also stops it, so a later kill reads as a longer absence than it was.
        thread(name = "heartbeat", isDaemon = true) {
            while (true) { store(context).edit().putLong("alive", System.currentTimeMillis()).apply(); Thread.sleep(60_000) }
        }
        watch(context.applicationContext)
        coming = away != null
        store(context).getString("said", null)?.let { last = it to store(context).getLong("saidAt", 0) }
        // A report delivered only as a notification before the process stopped is still repeated when the app opens.
        told = store(context).getBoolean("told", true)
        return away?.let { it to PhoneReminders(context).all().filter { r -> r.at in it.from..it.to } }
    }

    /**
     * Records when connectivity was lost (persisted, in case the app is closed meanwhile) and, once a validated network
     * returns, runs a catch-up for a long enough outage and retries what was missed.
     */
    private fun watch(context: Context) {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        if (manager.activeNetwork == null && store(context).getLong("offline", 0) == 0L) store(context).edit().putLong("offline", System.currentTimeMillis()).apply()
        Offline.network(manager.getNetworkCapabilities(manager.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
        manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                Offline.network(false)
                if (store(context).getLong("offline", 0) == 0L) store(context).edit().putLong("offline", System.currentTimeMillis()).apply()
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return
                Offline.network(true)
                val since = store(context).getLong("offline", 0).takeIf { it > 0 } ?: return
                store(context).edit().remove("offline").apply()
                // Waits for the connection and the messages it brings to settle. Any outage, however short, can leave
                // messages to retry; only a longer one ([Absence.offline]) warrants a catch-up report.
                val away = Absence.offline(since, System.currentTimeMillis())
                thread { Thread.sleep(10_000); away?.let { run(context, it, emptyList()) }; missed(context, since) }
            }
        })
    }

    /**
     * After reconnecting, retries the messages left unanswered since [since] (when their notification still has a
     * reply action) and checks followed topics whose scheduled time passed. Skipped while the assistant is paused.
     */
    private fun missed(context: Context, since: Long) {
        if (Guard.policy.stopped) return
        Inbox.missed(since).forEach { entry -> MessageListener.retry?.invoke(entry) }
        val zone = java.time.ZoneId.systemDefault()
        val news = PhoneNews(context)
        News.missed(news.topics(), java.time.Instant.ofEpochMilli(since).atZone(zone).toLocalDateTime(), java.time.LocalDateTime.now())
            .forEach { news.check(it.id) }
    }

    /** Reports the return and its cause, then gathers what happened during [away] and delivers the agent's summary. */
    fun run(context: Context, away: Absence.Away, reminders: List<Reminders.Reminder>) {
        coming = false
        // While paused the report stays pending and is delivered after resuming.
        if (Guard.policy.stopped) return
        if (away.kind != Absence.Kind.OFFLINE) store(context).edit().remove("awayFrom").remove("awayKind").apply()
        Log.i(AssistantService.TAG, "back after ${away.kind} since ${away.from}")
        tell(context, Absence.back(away, Policy.owner), quiet = true)
        val events = gather(context, away, reminders)
        Turns.scribe(context, Absence.ask(Policy.owner, away, events)) { text ->
            val said = text?.takeIf { it.isNotBlank() } ?: Absence.plain(events)
            last = said to System.currentTimeMillis()
            store(context).edit().putString("said", said).putLong("saidAt", last!!.second).putBoolean("told", false).apply()
            told = false
            tell(context, said, quiet = false)
            Ui.refresh()
        }
    }

    private fun gather(context: Context, away: Absence.Away, reminders: List<Reminders.Reminder>) = buildList {
        val hm = DateTimeFormatter.ofPattern("HH:mm")
        fun at(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()).format(hm)
        reminders.forEach { r ->
            add(Absence.Event(if (r.level == Reminders.Level.HIGH) Absence.URGENT else Absence.MISSED, "Reminder due at ${at(r.at)} (${r.level.name.lowercase()}): ${r.text}"))
        }
        Mailbox.mails.filter { it.at >= away.from }.forEach { m ->
            add(Absence.Event(if (m.important) Absence.IMPORTANT else Absence.NOTED, "Email at ${at(m.at)} from ${m.from}: ${m.subject}" + (m.why?.let { " (important: $it)" } ?: "")))
        }
        Inbox.digest.filter { it.time >= away.from }.forEach { e ->
            val waiting = e.status != Inbox.Status.ANSWERED
            add(Absence.Event(if (waiting) Absence.WAITING else Absence.NOTED, "Message at ${e.at} from ${e.sender}: \"${e.text.take(120)}\" (${e.status})"))
        }
        PhoneMcp.events(context, LocalDateTime.now(), LocalDateTime.now().plusHours(3))?.forEach { (title, begin, _) ->
            add(Absence.Event(Absence.SOON, "Coming up at ${begin.format(hm)}: $title"))
        }
    }

    /**
     * Delivers [text] to [onBack] when the app is visible, otherwise as a notification; [quiet] uses a silent,
     * low-importance channel for the initial "back" line.
     */
    private fun tell(context: Context, text: String, quiet: Boolean) {
        onBack?.let { if (Floating.appVisible) { it(text); if (!quiet) markTold(context); return } }
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = if (quiet) Chime.channel(context, "back-quiet", t(R.string.catchup_i_m_back), NotificationManager.IMPORTANCE_LOW)
        else Chime.channel(context, "back", t(R.string.catchup_while_i_was_away), NotificationManager.IMPORTANCE_DEFAULT)
        val open = PendingIntent.getActivity(
            context, 9500, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        manager.notify(
            9500,
            Notification.Builder(context, channel).setSmallIcon(R.drawable.ic_mark)
                .setContentTitle(if (quiet) t(R.string.catchup_x_is_back, Policy.ASSISTANT) else t(R.string.catchup_while_i_was_away))
                .setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(open).setAutoCancel(true).build(),
        )
    }
}
