package org.rustlanglatam.zeroclaw.android

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.ContactsContract
import java.time.LocalDate
import java.time.LocalTime
import java.time.MonthDay
import java.time.format.DateTimeFormatter

/**
 * Contacts' birthdays, as saved in the phone's contacts: the owner is told the day before (a quiet notice from 09:00,
 * once a day), and a card offers to write something (the agent proposes a text; sending follows the usual rules) or to
 * be reminded on the day. The `birthdays` tool lists the upcoming ones. Pure apart from [read], [check] and [offer].
 */
object Birthdays {
    /** A contact's birthday: [name] as saved and the day of the year (the year, when saved, is not needed). */
    data class Birthday(val name: String, val day: MonthDay)

    /** Parses a contacts birthday: `1990-03-14`, `--03-14` (no year) or `03-14`. */
    fun parse(name: String, date: String): Birthday? = runCatching {
        val parts = date.trim().trimStart('-').split('-')
        val (month, day) = if (parts.size >= 3) parts[parts.size - 2].toInt() to parts.last().toInt() else parts[0].toInt() to parts[1].toInt()
        Birthday(name, MonthDay.of(month, day))
    }.getOrNull()

    /** Birthdays within the next [days] days from [today] (today included), soonest first, with their date. A 29 February falls on the 28th in other years. */
    fun upcoming(all: List<Birthday>, today: LocalDate, days: Int): List<Pair<Birthday, LocalDate>> =
        all.mapNotNull { b ->
            fun on(year: Int) = if (b.day == MonthDay.of(2, 29) && !java.time.Year.isLeap(year.toLong())) LocalDate.of(year, 2, 28) else b.day.atYear(year)
            val next = on(today.year).let { if (it.isBefore(today)) on(today.year + 1) else it }
            (b to next).takeIf { !next.isAfter(today.plusDays(days.toLong())) }
        }.sortedBy { it.second }

    /** Reads every contact birthday; empty without the contacts permission. */
    fun read(context: Context): List<Birthday> {
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return emptyList()
        val event = ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE
        return context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data.DISPLAY_NAME, ContactsContract.CommonDataKinds.Event.START_DATE),
            "${ContactsContract.Data.MIMETYPE} = ? AND ${ContactsContract.CommonDataKinds.Event.TYPE} = ?",
            arrayOf(event, ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY.toString()), null,
        )?.use { c -> buildList { while (c.moveToNext()) parse(c.getString(0).orEmpty(), c.getString(1).orEmpty())?.let(::add) } }.orEmpty().distinctBy { it.name }
    }

    /** Renders upcoming birthdays for the agent. */
    fun describe(list: List<Pair<Birthday, LocalDate>>, today: LocalDate): String =
        if (list.isEmpty()) "No birthdays in that time."
        else list.joinToString("\n") { (b, d) -> "${b.name}: ${d.format(DateTimeFormatter.ISO_LOCAL_DATE)}" + if (d == today) " (today)" else if (d == today.plusDays(1)) " (tomorrow)" else "" }

    /** From 09:00, once a day, tells the owner about tomorrow's birthdays; called once a minute by the service. */
    fun check(context: Context) {
        if (!Guard.setUp || Guard.policy.stopped || LocalTime.now().isBefore(LocalTime.of(9, 0))) return
        val today = LocalDate.now()
        val prefs = context.getSharedPreferences("birthdays", Context.MODE_PRIVATE)
        if (prefs.getString("day", null) == today.toString()) return
        prefs.edit().putString("day", today.toString()).apply()
        val tomorrow = upcoming(read(context), today, 1).filter { it.second == today.plusDays(1) }.map { it.first.name }
        if (tomorrow.isEmpty()) return
        val text = t(R.string.birthdays_tomorrow, tomorrow.joinToString(", "))
        val open = PendingIntent.getActivity(
            context, ID, MainActivity.sealed(Intent(context, MainActivity::class.java).putExtra("birthday", tomorrow.first())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        context.getSystemService(NotificationManager::class.java).notify(
            ID, Notification.Builder(context, Chime.channel(context, "birthdays", t(R.string.birthdays_channel), NotificationManager.IMPORTANCE_DEFAULT))
                .setSmallIcon(R.drawable.ic_mark).setContentTitle(t(R.string.birthdays_title)).setContentText(text).setContentIntent(open).setAutoCancel(true).build(),
        )
    }

    /** The card for [name]'s birthday tomorrow: write something (through the agent, never sent on its own) or a reminder on the day. */
    fun offer(name: String, ask: (String) -> Unit) {
        val tools = AssistantService.phoneTools
        val actions = linkedMapOf<String, () -> Unit>(t(R.string.birthdays_write_something) to { ask(t(R.string.birthdays_ask, name)) })
        if (tools != null) actions[t(R.string.birthdays_remind_me_tomorrow)] = {
            tools.owner("remind", org.json.JSONObject().put("text", t(R.string.birthdays_reminder_text, name)).put("days_from_today", 1).put("time", "09:00"))
        }
        Cards.show(t(R.string.birthdays_title), t(R.string.birthdays_tomorrow, name), actions.keys.toList(), actions)
    }

    /** A notification id in a range no other notification uses. */
    private const val ID = 400_004
}
