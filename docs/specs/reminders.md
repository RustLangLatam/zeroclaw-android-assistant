# Spec: Reminders

| | |
|---|---|
| Status | Stable |
| Code | `app/.../Reminders.kt` (model, levels, time parsing, `asked`), `app/.../PhoneReminders.kt` (`PhoneReminders`, `BootReceiver`, `ReminderReceiver`, `CallActivity`); tools in `PhoneMcp.kt` (`remind`, `list_reminders`, `cancel_reminder`, `move_reminder`); rescheduling in `AssistantService.onStartCommand` |
| Tests | `app/src/test/.../RemindersTest.kt` (health rule, repeated calls, daily next occurrence, encoding and description); `scripts/e2e.sh`: none |
| Decisions | [ADR-0014](../adr/0014-reminders-in-the-app.md), [ADR-0003](../adr/0003-permissions-enforced-in-code.md), [ADR-0023](../adr/0023-app-files-sealed-to-the-phone.md), [ADR-0027](../adr/0027-assistant-voice-from-a-cloud-speech-model.md) |
| Last checked against code | 2026-10-22, commit `37f416d` |

## Purpose

The owner's reminders, each with a delivery level: a quiet notice, a sounding notification, or a
full-screen incoming-call screen that repeats until answered. Health reminders are always high,
decided in code. It is not the calendar, not a telephony call, and not ZeroClaw's cron (routines).

## Responsibilities

- Decide the level (`Reminders.level`), including the health rule.
- Keep the reminders (`files/reminders.json`) and set one exact alarm per reminder.
- Deliver them by level; call again for an unanswered high one; move a daily one to its next day.
- Show the call (`CallActivity`): ring, vibrate, then speak once answered; snooze or done.
- Set every alarm again when the service starts and after a reboot.

## Interfaces

MCP tools, owner endpoint only (the offline agent reaches `remind` and `list_reminders`), through
`Guard.gated(…, Capability.REMINDERS, Policy.OWNER_TARGET, …)`:

| Tool | Arguments | Does |
|---|---|---|
| `remind` | `text` (required); `time` HH:MM with `date` YYYY-MM-DD or `days_from_today`, or `minutes_from_now` (clamped 1–1440); `level` low / normal / high; `daily` | `PhoneReminders.add`; returns `Reminder #N set for yyyy-MM-dd HH:mm[, every day], level L` plus ` (you will call them, full screen, until they answer).` for high |
| `list_reminders` | — | `Reminders.describe`: `#id yyyy-MM-dd HH:mm [every day] [level] text`, sorted by time |
| `cancel_reminder` | `id` | `PhoneReminders.cancel(id)`: alarm and notification cancelled, removed |
| `move_reminder` | `id`; `time` with `date` or `days_from_today`, or `minutes_from_now` | cancels and re-adds with the same text, level and repetition (new id) |

Argument rules for `remind`: for the owner's principal, a time stated in the owner's last message
(`Reminders.spoken(OwnerChat.lastWords)`) takes precedence over the model's `time`; a time without a
date means its next occurrence (09:00 asked at 10:00 is tomorrow). Level words come from the
lexicon (English and Spanish). Missing text or an unparsable time →
`Needs text, and time HH:MM with date (YYYY-MM-DD) or days_from_today, or minutes_from_now.`; a past
time → `That time has passed; ask the owner for another.` Both are checked before the policy. A high
or daily reminder is announced to the owner at once through `Guard.onNotify`, whoever asked.

Offline, `Reminders.asked(words)` maps an explicit request (a reminder, an alarm, the day's agenda,
the reminders list, the next alarm, opening an app, the weather) to a tool call that the app runs
without the model (`Offline.direct`, [ADR-0025](../adr/0025-an-offline-agent-on-a-local-model.md)).

Android:
- `ReminderReceiver` (not exported): `AlarmManager` broadcast with extra `reminder` → `PhoneReminders.due(id)`.
- `BootReceiver` (exported for `BOOT_COMPLETED`): `rescheduleAll()` for reminders and news checks.
- `CallActivity` (not exported, `singleTask`, `taskAffinity=".call"`, shown over the lock screen,
  turns the screen on, keeps it on), extra `reminder`.
- Notification channels: `reminders-low` (low importance), `reminders-k` (high, the app's chime,
  `Chime.channel`), `reminders-call` (high, no sound, `setBypassDnd(true)`).
- Notification id `100000 + id`; PendingIntent request codes: call `200000 + id`, open app
  `300000 + id`, fire `400000 + id` (the legacy `9900 + id` alarm is cancelled on reschedule).
- Permissions: `USE_EXACT_ALARM`, `SCHEDULE_EXACT_ALARM` (maxSdk 32), `USE_FULL_SCREEN_INTENT`,
  `VIBRATE`, `RECEIVE_BOOT_COMPLETED`; `MainActivity.canCall()`/`openFullScreen()` for the Android 14
  full-screen permission.
- Out: `PhoneReminders.calling` (Compose state: the mark's URGENT load), `PhoneReminders.onDue`
  (set by `MainActivity` to say a normal reminder aloud), `Floating.notice`, `Gestures.play`.
- Readers: Home widget `REMINDERS` and the panorama ([home-and-widgets.md](home-and-widgets.md)),
  `CatchUp.start` (reminders due while away, [away-and-catch-up.md](away-and-catch-up.md)). There is
  no reminders screen.

## State and data

`files/reminders.json`, sealed ([ADR-0023](../adr/0023-app-files-sealed-to-the-phone.md)): a JSON
array of `{id, text, at, level, daily, tries}` (`Reminders.encode`/`decode`); `at` in epoch ms,
`level` `LOW|NORMAL|HIGH`, `tries` = unanswered calls. Every write calls `Ui.refresh()`. A new id is
max id + 1. Alarms live in `AlarmManager` only. An undecodable file reads as an empty list.

## Behaviour

**Levels** (`Reminders.Level`): `Reminders.level(text, asked)` returns `HIGH` when the normalized text
matches the health pattern (`reminders.health` in the lexicon: medicine, pill, dose, insulin, syrup,
drops, inhaler, antibiotic, treatment and their Spanish equivalents), whatever was asked; otherwise
the asked level, or `NORMAL`.

**Scheduling** (`schedule`): `HIGH`, or any level when `canScheduleExactAlarms()` is false →
`setAlarmClock` (exact, shown as the next alarm, opens the app when tapped); otherwise
`setExactAndAllowWhileIdle(RTC_WAKEUP)`.

**Delivery** (`due(id)`):

| Level | What happens | After |
|---|---|---|
| `LOW` | gesture NOD; notification on `reminders-low`; floating notice `Reminder · <text>` | `done(id)` |
| `NORMAL` | gesture PERK; app visible → chime only, else notification on `reminders-k`; floating notice; `onDue` (spoken in the app) | `done(id)` |
| `HIGH` | gesture ALERT; ongoing `CATEGORY_CALL` notification on `reminders-call` with a full-screen intent to `CallActivity`; the activity is also started directly when the app is visible or holds the overlay permission | `tries + 1` saved; next attempt by `setAlarmClock` in `AGAIN_MS` = 5 min |

A high reminder whose `tries` reached `CALLS` (6) is not called again: a floating notice says it was
not answered and `done(id)` runs.

`done(id)`: cancels notification and alarm; a daily reminder moves to `Reminders.next` (same time,
the next day after now) with `tries = 0` and is scheduled; any other is removed.

`snooze(id, minutes)`: the reminder moves to now + minutes, `daily = false`, `tries = 0`; a daily one
also gets a new daily copy (new id) at its next usual time. Every future reminder is scheduled again.

**Call** (`CallActivity`): `onStart` cancels the notification, sets `calling = true` and hides the
floating mark. It rings the default ringtone (or alarm sound) with alarm usage, looping, so it sounds
through do-not-disturb, and vibrates (`0, 800, 600` repeating, alarm usage). Nothing is spoken until
answered (speech behind a lock screen would be unseen).
- Ringing: Decline → `snooze(id, 10)`; Answer → ring and vibration stop and speech starts.
- Unanswered after `RING_MS` = 45 s: a missed-call notification on `reminders-call`, and the activity
  closes; the next attempt is already scheduled.
- Answered: the line `t(R.string.phonereminders_x_it_s_x_i_m, owner, assistant, text)` is spoken up to
  five times, 6 s after each completion, with the natural voice when usable (`CloudVoice.say`) and the
  phone's TTS otherwise. Buttons: "In 10 minutes" → `snooze(id, 10)`; "Done it" → `done(id)`.
- `onDestroy` stops ring, speech and vibration and sets `calling = false`.

**Start** (`AssistantService.onStartCommand`, first start only, after `CatchUp.start`) and
`BootReceiver`: `rescheduleAll()`: future → scheduled; past and daily → scheduled at `next` (not
saved); past and one-time → `due(id)` at once.

## Invariants

- A health reminder is `HIGH` whatever the agent asked (code, not prompt).
- A high reminder calls at most `CALLS = 6` times, `AGAIN_MS = 5 min` apart, unless answered.
- One alarm per reminder id (`FLAG_UPDATE_CURRENT` on request code `400000 + id`).
- Low and normal reminders end (or move to the next day if daily) as soon as they are delivered.
- All PendingIntents are immutable; the alarm receiver and the call activity are not exported.
- High and daily reminders are announced to the owner when created.

## Failure modes

- Process killed: alarms survive (system); the receiver starts the process. Reboot: `BootReceiver`
  reschedules.
- Full-screen intent not allowed (Android 14, new install) and no overlay permission: only the
  heads-up notification; the setup and Home offer `openFullScreen()`. On some OEM builds a separate
  lock-screen permission is also needed; the setup offers it.
- TTS and the natural voice both unavailable: the call rings and vibrates; nothing is spoken.
- `CallActivity` for an id no longer in the file finishes at once.
- The kill switch stops the tools, not alarms already set.

## Security

- Principal: the owner's agent only (`Capability.REMINDERS`, `Risk.INTERNAL`, default `ACT`, no
  approval). Logged as `remind [<level>] <text> at <time>`.
- The text is the agent's; it is spoken aloud and shown on the lock screen.
- A high reminder bypasses do-not-disturb and takes the screen over the lock screen; the owner is
  told at once of every high or daily reminder created.

## Open issues

- The call notification's title is a literal string in `PhoneReminders.due`, not a string resource,
  so it is not localized.
- Reminders stay in the app rather than in ZeroClaw ([ADR-0014](../adr/0014-reminders-in-the-app.md)):
  the full-screen call needs Android.
