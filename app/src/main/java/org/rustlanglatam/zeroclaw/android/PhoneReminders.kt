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
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Bundle
import android.os.VibrationEffect
import android.os.VibratorManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Stores the owner's reminders in `files/reminders.json` (sealed, see [Vault]) and schedules each as an exact
 * [AlarmManager] alarm. When one is due, [due] delivers it with an intensity that depends on its
 * [Reminders.Level].
 */
class PhoneReminders(context: Context) {
    private val context = context.applicationContext
    private val file = File(this.context.filesDir, "reminders.json")
    private val hm = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    /** Returns all stored reminders. */
    @Synchronized fun all(): List<Reminders.Reminder> = if (file.exists()) Reminders.decode(file.readSealed()) else emptyList()

    @Synchronized private fun save(list: List<Reminders.Reminder>) {
        file.writeSealed(Reminders.encode(list))
        Ui.refresh()
    }

    /** Formats epoch millis [at] as `yyyy-MM-dd HH:mm` in the system time zone. */
    fun format(at: Long): String = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(hm)

    /** Stores a new reminder with the next free id, schedules it and returns it. */
    @Synchronized fun add(text: String, at: Long, level: Reminders.Level, daily: Boolean, repeat: Reminders.Repeat? = null): Reminders.Reminder {
        val list = all()
        val asked = Reminders.Reminder((list.maxOfOrNull { it.id } ?: 0) + 1, text, at, level, daily, repeat = repeat)
        // The first time falls on a day the repetition allows: "the 5th of every month at 9" asked on the 23rd is the 5th.
        val r = asked.copy(at = Reminders.first(asked))
        save(list + r)
        schedule(r)
        return r
    }

    /** Deletes reminder [id] with its alarm and notification; returns `false` if it does not exist. */
    @Synchronized fun cancel(id: Int): Boolean {
        val list = all()
        val r = list.firstOrNull { it.id == id } ?: return false
        alarms().cancel(fire(r.id))
        context.getSystemService(NotificationManager::class.java).cancel(NOTE + id)
        save(list - r)
        return true
    }

    /**
     * Completes reminder [id], either [confirmed] by the owner (recorded, so "did I take it?" can be answered) or after
     * a low or normal one was shown. A repeating reminder is rescheduled for its next occurrence; any other is deleted.
     */
    @Synchronized fun done(id: Int, confirmed: Boolean = false) {
        val list = all()
        val r = list.firstOrNull { it.id == id } ?: return
        context.getSystemService(NotificationManager::class.java).cancel(NOTE + id)
        alarms().cancel(fire(id))
        val now = System.currentTimeMillis()
        val next = Reminders.next(r, now)
        val log = if (confirmed) (r.doneAt + now).takeLast(Reminders.DONE_KEPT) else r.doneAt
        if (next == null) save(list - r) else r.copy(at = next, tries = 0, doneAt = log).also { save(list - r + it); schedule(it) }
    }

    /** Postpones reminder [id] by [minutes]. */
    @Synchronized fun snooze(id: Int, minutes: Long) {
        val list = all()
        val r = list.firstOrNull { it.id == id } ?: return
        context.getSystemService(NotificationManager::class.java).cancel(NOTE + id)
        val later = r.copy(at = System.currentTimeMillis() + minutes * 60_000L, daily = false, repeat = null, tries = 0)
        // A repeating reminder becomes a one-off snooze plus a new repeating copy at its next occurrence.
        save(list - r + later + (if (r.repeats) listOfNotNull(Reminders.next(r, later.at)?.let { r.copy(id = list.maxOf { it.id } + 1, at = it, tries = 0) }) else emptyList()))
        all().filter { it.at > System.currentTimeMillis() }.forEach(::schedule)
    }

    /**
     * Reschedules every reminder, since alarms do not survive a reboot. Past-due one-off reminders are delivered
     * immediately.
     */
    fun rescheduleAll() = all().forEach { r ->
        // Cancels alarms registered under the legacy request code (9900 + id); otherwise they would fire twice.
        alarms().cancel(PendingIntent.getBroadcast(context, 9900 + r.id, Intent(context, ReminderReceiver::class.java).putExtra("reminder", r.id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        if (r.at > System.currentTimeMillis()) schedule(r) else Reminders.next(r, System.currentTimeMillis())?.let { schedule(r.copy(at = it)) } ?: due(r.id)
    }

    private fun schedule(r: Reminders.Reminder) {
        val manager = alarms()
        // High reminders (and all reminders when exact alarms are not permitted) use setAlarmClock: exact, shown as
        // the next alarm, and allowed to fire in Doze.
        if (r.level == Reminders.Level.HIGH || !manager.canScheduleExactAlarms()) {
            manager.setAlarmClock(AlarmManager.AlarmClockInfo(r.at, openApp(r.id)), fire(r.id))
        } else {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.at, fire(r.id))
        }
    }

    /**
     * Delivers reminder [id]: low is a silent notification, normal a chime or heads-up notification, and high a
     * full-screen [CallActivity] that repeats until answered or [Reminders.CALLS] attempts are reached.
     */
    fun due(id: Int) {
        val list = all()
        val r = list.firstOrNull { it.id == id } ?: return
        val manager = context.getSystemService(NotificationManager::class.java)
        Gestures.play(when (r.level) { Reminders.Level.LOW -> Gesture.NOD; Reminders.Level.NORMAL -> Gesture.PERK; Reminders.Level.HIGH -> Gesture.ALERT })
        when (r.level) {
            Reminders.Level.LOW -> {
                manager.createNotificationChannel(NotificationChannel("reminders-low", t(R.string.phonereminders_gentle_reminders), NotificationManager.IMPORTANCE_LOW))
                manager.notify(NOTE + id, note("reminders-low", r).build())
                Floating.notice(context, t(R.string.phonereminders_reminder_x, r.text))
                done(id)
            }
            Reminders.Level.NORMAL -> {
                // With the app in the foreground the assistant announces it with the chime; no heads-up notification.
                if (Floating.appVisible) Chime.play(context)
                else manager.notify(NOTE + id, note(Chime.channel(context, "reminders", t(R.string.phonereminders_reminders), NotificationManager.IMPORTANCE_HIGH), r).build())
                Floating.notice(context, t(R.string.phonereminders_reminder_x, r.text))
                onDue?.invoke(r)
                done(id)
            }
            Reminders.Level.HIGH -> {
                // All call attempts unanswered: stop, notify the owner, and let done() advance a daily reminder.
                if (r.tries >= Reminders.CALLS) {
                    Floating.notice(context, t(R.string.phonereminders_i_called_you_about_x_and, r.text))
                    return done(id)
                }
                // Full-screen incoming-call UI over the lock screen and other apps; not a telephony call.
                manager.createNotificationChannel(NotificationChannel("reminders-call", t(R.string.phonereminders_ksandra_calls_you_important_reminders), NotificationManager.IMPORTANCE_HIGH).apply {
                    setSound(null, null)
                    setBypassDnd(true)
                })
                val call = PendingIntent.getActivity(context, CALL + id, callIntent(id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                manager.notify(
                    NOTE + id,
                    note("reminders-call", r).setCategory(Notification.CATEGORY_CALL).setFullScreenIntent(call, true).setOngoing(true)
                        .setContentTitle(t(R.string.reminders_call_title_x, Policy.ASSISTANT)).build(),
                )
                // With the app in the foreground or the overlay permission granted, the activity is started directly:
                // on Android 14 a fresh install may not get USE_FULL_SCREEN_INTENT until the owner allows it.
                if (Floating.appVisible || Settings.canDrawOverlays(context)) runCatching { context.startActivity(callIntent(id)) }
                // Schedules the next attempt; answering cancels it. After the last attempt, one more alarm closes it.
                val again = Reminders.again(r, System.currentTimeMillis()) ?: (System.currentTimeMillis() + Reminders.AGAIN_MS)
                val updated = r.copy(tries = r.tries + 1)
                save(list - r + updated)
                alarms().setAlarmClock(AlarmManager.AlarmClockInfo(again, openApp(id)), fire(id))
            }
        }
    }

    private fun note(channel: String, r: Reminders.Reminder) = Notification.Builder(context, channel)
        .setSmallIcon(R.drawable.ic_mark)
        .setContentTitle(t(R.string.phonereminders_reminder))
        .setContentText(r.text)
        .setContentIntent(openApp(r.id))
        .setAutoCancel(true)

    private fun callIntent(id: Int) = Intent(context, CallActivity::class.java).putExtra("reminder", id)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    private fun openApp(id: Int) = PendingIntent.getActivity(
        context, OPEN + id, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun fire(id: Int) = PendingIntent.getBroadcast(
        context, FIRE + id, Intent(context, ReminderReceiver::class.java).putExtra("reminder", id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun alarms() = context.getSystemService(AlarmManager::class.java)

    companion object {
        /** Whether a high reminder's call is in progress; the UI's mark shows an alert state meanwhile. */
        var calling by mutableStateOf(false)

        // Widely spaced id offsets: reminder ids grow without bound, and overlapping ranges would share a notification
        // id or a PendingIntent request code (whose extras the second would replace).
        const val NOTE = 100_000
        private const val CALL = 200_000
        private const val OPEN = 300_000
        private const val FIRE = 400_000

        /** Callback set while the app is open, used to announce a normal reminder aloud. */
        @Volatile var onDue: ((Reminders.Reminder) -> Unit)? = null
    }
}

/** After a reboot or an app update: reschedules reminders and news checks (alarms do not survive) and starts the assistant. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        runCatching { PhoneReminders(context).rescheduleAll() }
        runCatching { PhoneNews(context).rescheduleAll() }
        // Starts the assistant without waiting for the owner to open the app or for the notification listener to
        // reconnect (it never does without notification access). Before setup there is no owner to serve. Both
        // broadcasts are exempt from the background foreground-service start restrictions for `specialUse`.
        runCatching {
            Guard.load(context.filesDir)
            if (Guard.setUp) context.startForegroundService(Intent(context, AssistantService::class.java))
        }.onFailure { Log.w(AssistantService.TAG, "assistant not started after ${intent.action}: ${it.message}") }
    }
}

/** Receives a reminder's alarm and delivers it through [PhoneReminders.due]. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra("reminder", -1)
        if (id >= 0) PhoneReminders(context).due(id)
    }
}

/**
 * Full-screen incoming-call UI for a high reminder (not a telephony call). Shown over the lock screen, it rings with
 * alarm audio attributes (so it sounds through do not disturb) and vibrates; once answered, the assistant speaks the
 * reminder and repeats it after pauses. "Done" completes the reminder; "In 10 minutes" snoozes it.
 */
class CallActivity : ComponentActivity() {
    private var ring: Ringtone? = null
    private var tts: TextToSpeech? = null
    @Volatile private var voiced = false
    private var answered by mutableStateOf(false)
    private var reminder by mutableStateOf<Reminders.Reminder?>(null)
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Guard.load(filesDir)
        Voices.load(this)
        show(intent)
        setContent {
            KsandraTheme {
                Atmosphere {
                    val r = reminder
                    Column(Modifier.fillMaxSize().padding(horizontal = Dimens.screen, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Spacer(Modifier.height(24.dp))
                        Eyebrow(if (answered) t(R.string.phonereminders_on_the_call) else t(R.string.phonereminders_is_calling_you), color = Ink.warmth)
                        Spacer(Modifier.height(10.dp))
                        Text(Policy.ASSISTANT.lowercase(), style = Wordmark, color = Ink.mist)
                        Spacer(Modifier.weight(1f))
                        // The logo stands in for a caller photo and animates until answered.
                        KsandraLogo(200.dp, ringing = !answered)
                        Spacer(Modifier.weight(1f))
                        Text(r?.text.orEmpty(), Modifier.fillMaxWidth(), style = MaterialTheme.typography.displaySmall, color = Ink.mist, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(36.dp))
                        // Answer or decline while ringing; once answered, done or snooze.
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (!answered) {
                                GhostButton(t(R.string.phonereminders_decline), Modifier.weight(1f)) { finish(snooze = true) }
                                GlowButton(t(R.string.phonereminders_answer), Modifier.weight(1f)) { answer() }
                            } else {
                                GhostButton(t(R.string.phonereminders_in_10_minutes), Modifier.weight(1f)) { finish(snooze = true) }
                                GlowButton(t(R.string.phonereminders_done_it), Modifier.weight(1f)) { finish(snooze = false) }
                            }
                        }
                    }
                }
            }
        }
    }

    // While visible, the activity replaces the notification and the floating bubble.
    override fun onStart() {
        super.onStart()
        reminder?.let { getSystemService(NotificationManager::class.java).cancel(PhoneReminders.NOTE + it.id) }
        PhoneReminders.calling = true
        Floating.shown(this, true)
    }

    override fun onStop() {
        Floating.shown(this, false)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        show(intent)
    }

    private fun show(intent: Intent) {
        val id = intent.getIntExtra("reminder", -1)
        reminder = PhoneReminders(this).all().firstOrNull { it.id == id } ?: return finish()
        if (ring != null) return
        // Only rings until answered; speech starts in answer(). Speaking immediately would talk unseen when the
        // device is asleep behind the lock screen.
        tts = TextToSpeech(this) { if (it == TextToSpeech.SUCCESS) { tts?.asKsandra(); voiced = true } }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) { Ui.speaking = true }
            override fun onDone(id: String?) { Ui.speaking = false; main.postDelayed({ say(round + 1) }, 6_000) }
            @Deprecated("Deprecated in Java") override fun onError(id: String?) { Ui.speaking = false }
        })
        ring = RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))?.apply {
            audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
            isLooping = true
            play()
        }
        val vibrator = getSystemService(VibratorManager::class.java).defaultVibrator
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 600), 0), android.os.VibrationAttributes.createForUsage(android.os.VibrationAttributes.USAGE_ALARM))
        // Unanswered after RING_MS: posts a missed-call notification; the next attempt is already scheduled.
        main.postDelayed({ if (!answered && !isFinishing) missed() }, RING_MS)
    }

    /** Stops ringing and vibration and starts speaking the reminder. */
    private fun answer() {
        if (answered) return
        answered = true
        ring?.stop()
        getSystemService(VibratorManager::class.java).defaultVibrator.cancel()
        say(0)
    }

    @Volatile private var round = 0

    /** Speaks the reminder for round [n]; each completed utterance schedules the next round after a pause, up to five rounds. */
    private fun say(n: Int) {
        round = n
        if (isFinishing || n > 4) return
        val r = reminder ?: return
        if (!voiced) { main.postDelayed({ say(n) }, 500); return }
        val owner = Policy.owner
        val line = t(R.string.phonereminders_x_it_s_x_i_m, owner, Policy.ASSISTANT, r.text)
        val phone = { tts?.speak(line, TextToSpeech.QUEUE_FLUSH, null, "call$n") }
        // Uses the cloud voice when usable; the on-device TTS speaks the line if it is unusable or fails before any audio.
        if (!CloudVoice.say(this, line, { Ui.speaking = true }, { Ui.word++ }, { Ui.speaking = false; main.postDelayed({ say(round + 1) }, 6_000) }) { phone() }) phone()
    }

    /** Posts a missed-call notification and closes the activity. */
    private fun missed() {
        val r = reminder ?: return finish()
        getSystemService(NotificationManager::class.java).notify(
            PhoneReminders.NOTE + r.id,
            android.app.Notification.Builder(this, "reminders-call").setSmallIcon(R.drawable.ic_mark)
                .setContentTitle(t(R.string.phonereminders_missed_call_from_x, Policy.ASSISTANT))
                .setContentText(r.text).setAutoCancel(true)
                .setContentIntent(android.app.PendingIntent.getActivity(this, 9_700 + r.id, Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), android.app.PendingIntent.FLAG_IMMUTABLE)).build(),
        )
        finish()
    }

    private companion object {
        /** Ring duration before the call counts as missed. */
        const val RING_MS = 45_000L
    }

    private fun finish(snooze: Boolean) {
        val id = reminder?.id ?: return finish()
        if (snooze) PhoneReminders(this).snooze(id, 10) else PhoneReminders(this).done(id, confirmed = true)
        finish()
    }

    override fun onDestroy() {
        PhoneReminders.calling = false
        main.removeCallbacksAndMessages(null)
        ring?.stop()
        tts?.shutdown()
        CloudVoice.stop()
        Ui.speaking = false
        getSystemService(VibratorManager::class.java).defaultVibrator.cancel()
        super.onDestroy()
    }
}
