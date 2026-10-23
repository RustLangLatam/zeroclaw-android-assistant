package org.rustlanglatam.zeroclaw.android

import android.content.Context
import kotlin.concurrent.thread

/**
 * Features worth offering once, when the owner would not find them alone. Today: the morning briefing, offered on a
 * morning (06:00–11:59) of the first week after setup, when the owner has no routine yet; once, whatever the answer.
 * Pure apart from [morning].
 */
object Offers {
    /** Whether to offer the morning briefing now: day 1 to 7 after [setupAt], in the morning, not asked, no routines. */
    fun morningDue(setupAt: Long, now: Long, hour: Int, asked: Boolean, hasRoutines: Boolean): Boolean =
        !asked && !hasRoutines && hour in 6..11 && (now - setupAt) / 86_400_000L in 1..7

    /** Offers the morning briefing as a card when [morningDue]; [say] tells the owner the outcome. Runs on a background thread. */
    fun morning(context: Context, say: (String) -> Unit) = thread {
        if (!Guard.setUp) return@thread
        val prefs = context.getSharedPreferences("offers", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val setupAt = prefs.getLong("setupAt", 0L).takeIf { it > 0 } ?: now.also { prefs.edit().putLong("setupAt", it).apply() }
        val hour = java.time.LocalTime.now().hour
        if (prefs.getBoolean("morningAsked", false) || hour !in 6..11) return@thread
        val hasRoutines = Routines.list(context).let { it != "No routines." }
        if (!morningDue(setupAt, now, hour, false, hasRoutines)) return@thread
        prefs.edit().putBoolean("morningAsked", true).apply()
        showMorning(context, say)
    }

    /** Shows the morning-briefing card: "yes" creates the 08:00 daily routine in ZeroClaw's cron; "no" does nothing. */
    fun showMorning(context: Context, say: (String) -> Unit) {
        val yes = t(R.string.offers_morning_yes)
        Cards.show(t(R.string.offers_morning_title), t(R.string.offers_morning_body), listOf(yes, t(R.string.offers_morning_no)), mapOf(
            yes to { say(Routines.add(context, "08:00", "daily", t(R.string.offers_morning_what)).let { if (it.startsWith("Routine set")) t(R.string.offers_morning_done) else it }) },
            t(R.string.offers_morning_no) to {},
        ))
    }
}
