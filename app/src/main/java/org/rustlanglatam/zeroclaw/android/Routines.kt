package org.rustlanglatam.zeroclaw.android

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import io.github.rustlanglatam.zeroclaw.NewCronJob
import io.github.rustlanglatam.zeroclaw.kind
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlin.concurrent.thread

/**
 * Recurring routines requested by the owner, scheduled as ZeroClaw cron jobs that run turns of the
 * owner's agent. Each job is named with [PREFIX]. The app builds and validates the cron expression in
 * code, listens to ZeroClaw's event stream for results and relays them to the owner. A routine's tool
 * calls go through the app's policy like any other owner turn.
 */
object Routines {
    /** Job name prefix that identifies the app's routines among all cron jobs. */
    const val PREFIX = "ksandra: "

    /**
     * Returns the cron expression (minute hour day-of-month month day-of-week) for [time] (`HH:MM`) on [days]:
     * `daily`, `weekdays`, `weekends`, or comma-separated day names in Spanish or English. Returns `null` when
     * either argument cannot be parsed.
     */
    fun cron(time: String, days: String): String? {
        val (h, m) = Regex("""^(\d{1,2}):(\d{2})$""").find(time.trim())?.destructured ?: return null
        val hour = h.toInt().takeIf { it in 0..23 } ?: return null
        val minute = m.toInt().takeIf { it in 0..59 } ?: return null
        val dow = when (val d = Commands.normalize(days)) {
            "", in Lexicon.words("routines.daily") -> "*"
            in Lexicon.words("routines.weekdays") -> "1-5"
            in Lexicon.words("routines.weekends") -> "0,6"
            else -> d.split(',', ' ', '/').filter(String::isNotBlank).map { DAYS[it] ?: return null }.distinct().sorted().joinToString(",")
        }
        return "$minute $hour * * $dow"
    }

    /** Day names to cron day-of-week numbers; `routines.day.N` lists the names of day N (0 = Sunday). */
    private val DAYS = (0..6).flatMap { n -> Lexicon.words("routines.day.$n").map { it to n } }.toMap()

    /** Builds the prompt the owner's agent receives on each scheduled run, quoting the routine's text. */
    fun prompt(owner: String, what: String) =
        "(A routine $owner asked for, run now by schedule: «$what».) Do it, then say the result to $owner in one to " +
            "three spoken sentences, in ${Language.current.english}. Nobody is waiting to approve anything now: only what you may do on your own."

    /** Creates a cron job for the routine [what]; returns a plain-text result for the agent. */
    fun add(context: Context, time: String, days: String, what: String): String {
        val expr = cron(time, days) ?: return "Say the time as HH:MM and the days as daily, weekdays, weekends or day names."
        val token = AssistantService.token(context) ?: return "ZeroClaw is not reachable now; try again in a moment."
        return runCatching {
            val job = runBlocking {
                AssistantService.client(token).cron.create(
                    NewCronJob(agent = "owner", schedule = expr, tz = java.time.ZoneId.systemDefault().id, name = PREFIX + what.take(80),
                        jobType = "agent", prompt = prompt(Policy.owner, what), usesMemory = true),
                )
            }
            "Routine set (#${job.id.take(8)}): «$what», $days at $time; its next run is ${job.nextRun}."
        }.getOrElse { "The routine could not be set: ${it.message}" }
    }

    /** Returns the app's routines as plain text, one per line. */
    fun list(context: Context): String {
        val token = AssistantService.token(context) ?: return "ZeroClaw is not reachable now."
        return runCatching {
            val jobs = runBlocking { AssistantService.client(token).cron.list() }.filter { it.name?.startsWith(PREFIX) == true }
            if (jobs.isEmpty()) "No routines." else jobs.joinToString("\n") { "#${it.id.take(8)} ${it.name!!.removePrefix(PREFIX)} (${it.expression}, next ${it.nextRun})" }
        }.getOrElse { "The routines could not be read: ${it.message}" }
    }

    /** Deletes the routine whose job id starts with [id] (an optional leading `#` is ignored). */
    fun remove(context: Context, id: String): String {
        val token = AssistantService.token(context) ?: return "ZeroClaw is not reachable now."
        return runCatching {
            val client = AssistantService.client(token)
            val job = runBlocking { client.cron.list() }.firstOrNull { it.name?.startsWith(PREFIX) == true && it.id.startsWith(id.removePrefix("#")) }
                ?: return "No routine #$id."
            runBlocking { client.cron.delete(job.id) }
            "Routine removed: ${job.name!!.removePrefix(PREFIX)}."
        }.getOrElse { "The routine could not be removed: ${it.message}" }
    }

    /**
     * Starts a daemon thread that reads ZeroClaw's event stream and relays each successful `cron_result` of
     * the app's routines to the owner: into the conversation if the app is open, otherwise as a notification.
     * The thread ends when the stream closes; [AssistantService] restarts it.
     */
    fun listen(context: Context) = thread(name = "routines", isDaemon = true) {
        val token = AssistantService.token(context) ?: return@thread
        runCatching {
            runBlocking {
                val client = AssistantService.client(token)
                val ours = client.cron.list().filter { it.name?.startsWith(PREFIX) == true }.associate { it.id to it.name!!.removePrefix(PREFIX) }.toMutableMap()
                val stream = client.events.subscribe()
                while (true) {
                    val event = stream.next() ?: break
                    if (event.kind != "cron_result") continue
                    val id = (event["job_id"] as? JsonPrimitive)?.content ?: continue
                    val name = ours[id] ?: client.cron.list().firstOrNull { it.id == id && it.name?.startsWith(PREFIX) == true }?.name?.removePrefix(PREFIX)?.also { ours[id] = it } ?: continue
                    if ((event["success"] as? JsonPrimitive)?.booleanOrNull != true) continue
                    val output = (event["output"] as? JsonPrimitive)?.content?.trim().orEmpty().ifEmpty { continue }
                    tell(context, name, output)
                }
            }
        }.onFailure { Log.w(AssistantService.TAG, "routines: ${it.message}") }
    }

    /** Delivers [text] to the open conversation if any, otherwise posts a notification per routine name. */
    private fun tell(context: Context, name: String, text: String) {
        MainActivity.note?.let { return it(text) }
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = Chime.channel(context, "routines", t(R.string.routines_your_routines), NotificationManager.IMPORTANCE_DEFAULT)
        manager.notify(
            600_000 + Math.floorMod(name.hashCode(), 1000),
            Notification.Builder(context, channel).setSmallIcon(R.drawable.ic_mark).setContentTitle(name.replaceFirstChar { it.uppercase() })
                .setContentText(text).setStyle(Notification.BigTextStyle().bigText(text)).setAutoCancel(true).build(),
        )
    }
}
