package org.rustlanglatam.zeroclaw.android

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import java.time.YearMonth

/**
 * The owner's monthly budget for the cloud model ([Guard.modelBudget], US$, 0 = none), enforced by the app on top of
 * ZeroClaw's per-day caps: once this month's cost (ZeroClaw's `/api/cost`, [Usage.read]) reaches it, no cloud turn is
 * started until the next month: the owner gets the basics handled in code ([Offline.direct]), third parties get no
 * reply, background briefings fall back to their plain text. The owner is told at 80 % and at 100 %, once each per
 * month. Without a price for the model the cost is unknown and the budget cannot apply ([priced]). The cost is read in
 * the background once a minute ([check]); a turn checks the last value read, so at most a minute of turns can pass
 * the budget.
 */
object Budget {
    /** The share of the budget spent, as the stage the owner was last told about: 0, 80 or 100. */
    fun stage(spent: Double, budget: Double): Int = when {
        budget <= 0 -> 0
        spent >= budget -> 100
        spent >= budget * 0.8 -> 80
        else -> 0
    }

    @Volatile private var cached: Pair<Long, Double?> = 0L to null

    /** This month's model cost in US$, read at most once a minute; `null` when ZeroClaw is unreachable or no price is set. */
    fun spent(context: Context): Double? {
        val (at, value) = cached
        if (System.currentTimeMillis() - at < CACHE_MS) return value
        val summary = Usage.read(context)
        val usd = summary?.monthlyCostUsd?.takeIf { it > 0 || summary.totalTokens == 0L }
        cached = System.currentTimeMillis() to usd
        return usd
    }

    /** Whether the model has a price, so its cost (and the budget) can be known. */
    val priced get() = Keys[Keys.Key.MODEL_INPUT_PER_MTOK].toDoubleOrNull() != null && Keys[Keys.Key.MODEL_OUTPUT_PER_MTOK].toDoubleOrNull() != null

    /** Whether a budget is set and the last cost read has reached it; never blocks (callable on the main thread). */
    fun reached(): Boolean = Guard.modelBudget > 0 && stage(cached.second ?: 0.0, Guard.modelBudget) == 100

    /** Tells the owner when this month's cost crosses 80 % or 100 % of the budget, once per stage and month. */
    fun check(context: Context) {
        if (Guard.modelBudget <= 0) return
        val now = stage(spent(context) ?: return, Guard.modelBudget)
        val prefs = context.getSharedPreferences("budget", Context.MODE_PRIVATE)
        val month = YearMonth.now().toString()
        val told = if (prefs.getString("month", null) == month) prefs.getInt("stage", 0) else 0
        if (now <= told) return
        prefs.edit().putString("month", month).putInt("stage", now).apply()
        val text = if (now == 100) t(R.string.budget_reached, Usage.usd(Guard.modelBudget)) else t(R.string.budget_80, Usage.usd(Guard.modelBudget))
        val channel = Chime.channel(context, "budget", t(R.string.budget_channel), NotificationManager.IMPORTANCE_DEFAULT)
        context.getSystemService(NotificationManager::class.java).notify(
            ID, Notification.Builder(context, channel).setSmallIcon(R.drawable.ic_mark).setContentTitle(t(R.string.budget_title))
                .setContentText(text).setStyle(Notification.BigTextStyle().bigText(text)).setAutoCancel(true).build(),
        )
    }

    /** Forgets the cached cost, so a new budget applies at once. */
    fun forget() { cached = 0L to null }

    private const val CACHE_MS = 60_000L

    /** A notification id in a range no other notification uses. */
    private const val ID = 400_002
}
