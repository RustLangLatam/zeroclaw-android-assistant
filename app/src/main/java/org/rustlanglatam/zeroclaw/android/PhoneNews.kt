package org.rustlanglatam.zeroclaw.android

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import okhttp3.Request
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Android side of [News]: stores followed topics (`files/topics.json`) and briefings (`files/briefings.json`), schedules
 * each topic's check with an inexact alarm, fetches headlines, and turns new ones into a briefing written by the agent.
 *
 * A briefing is kept for the news screen, passed to [onBriefing] and, when the app is not visible, posted as a
 * notification.
 */
class PhoneNews(context: Context) {
    private val context = context.applicationContext
    private val topicsFile = File(this.context.filesDir, "topics.json")
    private val briefingsFile = File(this.context.filesDir, "briefings.json")

    @Synchronized fun topics(): List<News.Topic> = if (topicsFile.exists()) News.decodeTopics(topicsFile.readSealed()) else emptyList()

    @Synchronized private fun saveTopics(list: List<News.Topic>) {
        topicsFile.writeSealed(News.encodeTopics(list))
        Ui.refresh()
    }

    @Synchronized fun briefings(): List<News.Briefing> = if (briefingsFile.exists()) News.decodeBriefings(briefingsFile.readSealed()) else emptyList()

    @Synchronized private fun keep(b: News.Briefing) {
        val list = (briefings() + b).takeLast(30)
        briefingsFile.writeSealed(News.encodeBriefings(list))
        Ui.refresh()
    }

    /**
     * Follows [query], or updates the schedule of an already followed topic. Current headlines are marked as seen, so the
     * first briefing reports only what is published afterwards. A new topic is also stored as an interest in [Memory].
     */
    @Synchronized fun follow(query: String, time: String?, everyHours: Int?): News.Topic {
        val list = topics()
        list.firstOrNull { Commands.normalize(it.query) == Commands.normalize(query) }?.let { old ->
            val t = old.copy(time = if (everyHours != null) null else time ?: old.time, everyHours = everyHours)
            saveTopics(list - old + t); schedule(t); scheduleWatch(t); return t
        }
        val seen = runCatching { fetch(query) }.getOrDefault(emptyList()).map { it.link }
        val t = News.Topic((list.maxOfOrNull { it.id } ?: 0) + 1, query.trim(), if (everyHours != null) null else time ?: "08:00", everyHours, seen)
        saveTopics(list + t)
        // A followed topic is also an owner interest in Memory.
        Memory.remember("Interested in ${query.trim()}", confirmed = true, area = Memory.Area.INTERESTS)
        schedule(t)
        scheduleWatch(t)
        return t
    }

    @Synchronized fun unfollow(what: String): Boolean {
        val list = topics()
        val t = list.firstOrNull { it.id.toString() == what.trim().removePrefix("#") || Commands.normalize(it.query) == Commands.normalize(what) } ?: return false
        alarms().cancel(fire(t.id))
        alarms().cancel(fire(t.id, watch = true))
        saveTopics(list - t)
        return true
    }

    /** Fetches the headlines about [query] from the last two days. Blocks on the network; call off the main thread. */
    fun fetch(query: String): List<News.Headline> {
        // Edition by the network's country, then the SIM's, then the locale's.
        val phone = context.getSystemService(android.telephony.TelephonyManager::class.java)
        val country = listOfNotNull(phone?.networkCountryIso, phone?.simCountryIso, Locale.getDefault().country).firstOrNull { it.length == 2 } ?: "US"
        val (language, gl) = News.edition(country)
        val request = Request.Builder().url(News.feed(query, language, gl)).build()
        return http.newCall(request).execute().use { r -> if (!r.isSuccessful) emptyList() else News.parse(r.body!!.string()) }
    }

    /** Debug builds only: forgets the headlines already seen for topic [id], so the next [check] briefs them all. */
    @Synchronized fun unsee(id: Int) = saveTopics(topics().map { if (it.id == id) it.copy(seen = emptyList()) else it })

    /**
     * Runs the scheduled check of topic [id] on a background thread: reschedules it and, unless the assistant is
     * paused, turns new headlines into a briefing. Does nothing when there is nothing new.
     */
    fun check(id: Int) = thread {
        val topic = topics().firstOrNull { it.id == id } ?: return@thread
        schedule(topic)
        if (Guard.policy.stopped) return@thread
        val headlines = runCatching { fetch(topic.query) }.onFailure { Log.w(AssistantService.TAG, "news: ${it.message}") }.getOrNull() ?: return@thread
        val (new, seen) = News.fresh(topic, headlines)
        synchronized(this) { saveTopics(topics().map { if (it.id == id) seen else it }) }
        if (new.isEmpty()) return@thread
        Turns.scribe(context, News.ask(Policy.owner, topic.query, new)) { text ->
            tell(News.Briefing(topic.query, System.currentTimeMillis(), text?.takeIf { it.isNotBlank() } ?: News.plain(topic.query, new), new.take(8)))
        }
    }

    /**
     * Runs the periodic watch of a daily topic [id] between briefings: new headlines are announced at once only when the
     * agent judges them urgent ([News.urgent]).
     */
    fun watch(id: Int) = thread {
        val topic = topics().firstOrNull { it.id == id } ?: return@thread
        scheduleWatch(topic)
        if (Guard.policy.stopped) return@thread
        val headlines = runCatching { fetch(topic.query) }.getOrNull() ?: return@thread
        val (new, seen) = News.fresh(topic, headlines)
        Log.i(AssistantService.TAG, "news watch #$id: ${new.size} new")
        if (new.isEmpty()) return@thread
        Turns.scribe(context, News.watch(Policy.owner, topic.query, new)) { answer ->
            val text = News.urgent(answer) ?: return@scribe
            // Marked as seen so the next briefing does not repeat them.
            synchronized(this) { saveTopics(topics().map { if (it.id == id) seen else it }) }
            tell(News.Briefing(topic.query, System.currentTimeMillis(), text, new.take(8), important = true))
        }
    }

    private fun tell(b: News.Briefing) {
        keep(b)
        Gestures.play(Gesture.NOTICE)
        Floating.notice(context, (if (b.important) t(R.string.phonenews_important) else t(R.string.phonenews_news)) + b.topic)
        onBriefing?.invoke(b)
        // While the app is visible, the callback plus the chime is enough; the briefing is kept on the news screen.
        if (Floating.appVisible) return Chime.play(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = if (b.important) Chime.channel(context, "news-important", t(R.string.news_channel_important), NotificationManager.IMPORTANCE_HIGH)
            else Chime.channel(context, "news", t(R.string.phonenews_news_on_what_you_follow), NotificationManager.IMPORTANCE_DEFAULT)
        val open = PendingIntent.getActivity(
            context, 9300, MainActivity.sealed(Intent(context, MainActivity::class.java).putExtra("place", Place.NEWS)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        manager.notify(
            // One notification id per topic in a reserved range; floorMod avoids negative hashes colliding with other ids.
            500_000 + Math.floorMod(b.topic.hashCode(), 1000),
            Notification.Builder(context, channel).setSmallIcon(R.drawable.ic_mark)
                .setContentTitle((if (b.important) t(R.string.phonenews_important) else t(R.string.phonenews_news_about)) + b.topic).setContentText(b.text)
                .setStyle(Notification.BigTextStyle().bigText(b.text)).setContentIntent(open).setAutoCancel(true).build(),
        )
    }

    /** Schedules the next check of [t]: at its daily time (today or tomorrow), or [News.Topic.everyHours] from now. */
    private fun schedule(t: News.Topic) {
        val now = System.currentTimeMillis()
        val at = t.everyHours?.let { now + it * 3600_000L } ?: run {
            val time = runCatching { LocalTime.parse(t.time) }.getOrDefault(LocalTime.of(8, 0))
            var day = LocalDate.now().atTime(time)
            if (day.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() <= now + 60_000) day = day.plusDays(1)
            day.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }
        // A 10-minute window: a briefing tolerates some delay, so no exact-alarm permission is needed.
        alarms().setWindow(AlarmManager.RTC_WAKEUP, at, 10 * 60_000L, fire(t.id))
    }

    /** Schedules the urgent-change watch of a daily topic every [News.WATCH_HOURS]; cancels it for interval topics. */
    private fun scheduleWatch(t: News.Topic) {
        if (t.everyHours != null) return alarms().cancel(fire(t.id, watch = true))
        alarms().setWindow(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + News.WATCH_HOURS * 3600_000L, 15 * 60_000L, fire(t.id, watch = true))
    }

    fun rescheduleAll() = topics().forEach { schedule(it); scheduleWatch(it) }

    private fun fire(id: Int, watch: Boolean = false) = PendingIntent.getBroadcast(
        context, (if (watch) 19_400 else 9400) + id, Intent(context, NewsReceiver::class.java).putExtra("topic", id).putExtra("watch", watch),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun alarms() = context.getSystemService(AlarmManager::class.java)

    companion object {
        /** Invoked with each new briefing so the open app can read it aloud. */
        @Volatile var onBriefing: ((News.Briefing) -> Unit)? = null

        private val http = AssistantService.http.newBuilder().callTimeout(15, TimeUnit.SECONDS).build()
    }
}

class NewsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra("topic", -1)
        if (id < 0) return
        if (intent.getBooleanExtra("watch", false)) PhoneNews(context).watch(id) else PhoneNews(context).check(id)
    }
}
