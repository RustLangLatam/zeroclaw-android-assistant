package org.rustlanglatam.zeroclaw.android

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * Before an appointment: when an event with an address is [Guard.leaveMinutes] away, the owner is told it is time to
 * leave, and the notice opens the maps app with the address (the assistant does not navigate; real travel time is
 * not computed, so no address leaves the phone). With the battery at 20 % or less and not charging, an appointment
 * within the hour gets its own notice. Each notice once per event. Pure apart from [read] and [check].
 */
object Appointments {
    data class Event(val id: Long, val title: String, val begin: Long, val location: String)

    /** Events whose time to leave ([begin] minus [margin] minutes) has come and that have not started, with an address. */
    fun leaving(events: List<Event>, now: Long, margin: Int): List<Event> =
        events.filter { it.location.isNotBlank() && now >= it.begin - margin * 60_000L && now < it.begin }

    /** Events starting within the hour, when [level] is 20 % or less and the phone is not [charging]. */
    fun lowBattery(events: List<Event>, now: Long, level: Int, charging: Boolean): List<Event> =
        if (charging || level > 20) emptyList() else events.filter { it.begin in now..now + 3600_000L }

    /** Event instances starting in the next 3 hours; empty without calendar access. */
    fun read(context: Context, now: Long): List<Event> {
        if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return emptyList()
        val projection = arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.EVENT_LOCATION)
        return CalendarContract.Instances.query(context.contentResolver, projection, now, now + 3 * 3600_000L)
            ?.use { c -> buildList { while (c.moveToNext()) add(Event(c.getLong(0), c.getString(1).orEmpty(), c.getLong(2), c.getString(3).orEmpty())) } }
            .orEmpty().filter { it.begin >= now }
    }

    /** Posts the leave and low-battery notices due now, once per event; called once a minute by the service. */
    fun check(context: Context) {
        val margin = Guard.leaveMinutes
        if (margin <= 0 || !Guard.setUp || Guard.policy.stopped) return
        val now = System.currentTimeMillis()
        val events = read(context, now)
        if (events.isEmpty()) return
        val prefs = context.getSharedPreferences("appointments", Context.MODE_PRIVATE)
        val told = prefs.getStringSet("told", emptySet()).orEmpty()
        val (level, charging) = PhoneWidgets(context).battery()
        val fresh = mutableSetOf<String>()
        fun at(e: Event) = Instant.ofEpochMilli(e.begin).atZone(ZoneId.systemDefault()).toLocalTime().withSecond(0).withNano(0).toString()
        leaving(events, now, margin).filter { "leave:${it.id}:${it.begin}" !in told }.forEach { e ->
            fresh += "leave:${e.id}:${e.begin}"
            val map = PendingIntent.getActivity(context, ID + Math.floorMod(e.id.toInt(), 500),
                Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(e.location))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
            notify(context, ID + Math.floorMod(e.id.toInt(), 500), t(R.string.appointments_leave_title), t(R.string.appointments_leave, e.location, e.title, at(e)), map)
        }
        lowBattery(events, now, level, charging).filter { "battery:${it.id}:${it.begin}" !in told }.forEach { e ->
            fresh += "battery:${e.id}:${e.begin}"
            notify(context, ID + 500 + Math.floorMod(e.id.toInt(), 500), t(R.string.appointments_battery_title), t(R.string.appointments_battery, level, e.title, at(e)), null)
        }
        // Keys of past events are dropped so the set stays small.
        if (fresh.isNotEmpty()) prefs.edit().putStringSet("told", (told.filter { k -> k.substringAfterLast(':').toLongOrNull()?.let { it > now - 86_400_000L } == true } + fresh).toSet()).apply()
    }

    private fun notify(context: Context, id: Int, title: String, text: String, open: PendingIntent?) {
        context.getSystemService(NotificationManager::class.java).notify(
            id, Notification.Builder(context, Chime.channel(context, "appointments", t(R.string.appointments_channel), NotificationManager.IMPORTANCE_HIGH))
                .setSmallIcon(R.drawable.ic_mark).setContentTitle(title).setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text)).apply { open?.let { setContentIntent(it) } }.setAutoCancel(true).build(),
        )
    }

    const val DEFAULT_LEAVE_MINUTES = 30

    /** A notification id range no other notification uses. */
    private const val ID = 403_000
}
