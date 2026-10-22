package org.rustlanglatam.zeroclaw.android

import android.app.NotificationManager
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Busy, meeting or driving mode: until [Period.until], family and work contacts who write get one fixed reply each
 * (written in code, never by the model: it says only that the owner cannot answer, which is what availability already
 * discloses), do-not-disturb is on, and no agent turn answers them. Anyone else follows the usual rules (never
 * answered). The reply goes through [Guard] as [Capability.BUSY_REPLY]. The period is kept sealed in `busy.json`, so
 * it survives a restart; [tick] ends it and restores do-not-disturb if the assistant turned it on.
 */
object Busy {
    enum class Reason { BUSY, DRIVING }

    /** A busy period: its end, why, whether the assistant turned do-not-disturb on, and who was already told. */
    data class Period(val until: Long, val reason: Reason, val dndByUs: Boolean = false, val told: Set<String> = emptySet()) {
        fun active(now: Long) = now < until

        /** Whether [sender] still has to be told in this period. */
        fun owes(sender: String) = sender !in told

        fun telling(sender: String) = copy(told = told + sender)
    }

    /** Longest period accepted, so a forgotten mode does not answer people for days. */
    const val MAX_MS = 12 * 3600_000L

    @Volatile var period: Period? = null
        private set

    private var file: File? = null

    /** Returns the active period, or `null`. */
    fun now(at: Long = System.currentTimeMillis()): Period? = period?.takeIf { it.active(at) }

    /** The fixed reply in [lang], for [owner] and the end time [until] as the owner's clock shows it. */
    fun reply(lang: Lang, reason: Reason, owner: String, until: String): String = when (reason) {
        Reason.BUSY -> t(lang, R.string.busy_reply_busy, owner, until, Policy.ASSISTANT)
        Reason.DRIVING -> t(lang, R.string.busy_reply_driving, owner, Policy.ASSISTANT)
    }

    fun load(dir: File) {
        file = File(dir, "busy.json")
        period = runCatching {
            val o = JSONObject(file!!.readSealed())
            val told = o.optJSONArray("told")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty().toSet()
            Period(o.getLong("until"), Reason.valueOf(o.getString("reason")), o.optBoolean("dnd"), told)
        }.getOrNull()
    }

    @Synchronized private fun save(p: Period?) {
        period = p
        val f = file ?: return
        if (p == null) { f.delete(); return }
        runCatching { f.writeSealed(JSONObject().put("until", p.until).put("reason", p.reason.name).put("dnd", p.dndByUs).put("told", JSONArray(p.told.toList())).toString()) }
        Ui.refresh()
    }

    /** Starts (or replaces) a period of [minutes] for [reason]; turns do-not-disturb on when allowed and not on yet. */
    fun start(context: Context, minutes: Long, reason: Reason): Period {
        val manager = context.getSystemService(NotificationManager::class.java)
        val dndBefore = manager.currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL
        val ours = !dndBefore && manager.isNotificationPolicyAccessGranted &&
            runCatching { manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY) }.isSuccess
        val p = Period(System.currentTimeMillis() + (minutes * 60_000L).coerceIn(60_000L, MAX_MS), reason, ours || (period?.dndByUs == true))
        save(p)
        return p
    }

    /** Ends the period now; restores do-not-disturb if the assistant turned it on. Returns who was told, or `null` if none was active. */
    fun end(context: Context): Set<String>? {
        val p = period ?: return null
        if (p.dndByUs) runCatching { context.getSystemService(NotificationManager::class.java).setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL) }
        save(null)
        return p.told
    }

    /** Records that [sender] was told. */
    fun told(sender: String) { now()?.let { save(it.telling(sender)) } }

    /** Ends an expired period and tells the owner who was told meanwhile; called once a minute by the service. */
    fun tick(context: Context) {
        val p = period ?: return
        if (p.active(System.currentTimeMillis())) return
        val told = end(context) ?: return
        Guard.onNotify(if (told.isEmpty()) t(R.string.busy_ended) else t(R.string.busy_ended_told, told.joinToString(", ") { Identity.bare(it) }))
    }
}
