# Spec: Away and catch-up

| | |
|---|---|
| Status | Stable |
| Code | `app/.../Absence.kt` (pure), `app/.../CatchUp.kt`, `app/.../AssistantService.kt` (`onStartCommand`), `app/.../MessageListener.kt` (`Turns.scribe`, `retry`), `app/.../MainActivity.kt` (`onStart`), `app/.../Panorama.kt`, `app/.../Screens.kt` (`panorama`) |
| Tests | `app/src/test/.../AbsenceTest.kt` (kinds, the immediate line, priorities), `BackOnlineTest.kt` (missed topics and unanswered messages after a cut), `PanoramaTest.kt`; `CatchUp` itself has none |
| Decisions | [ADR-0001](../adr/0001-run-official-zeroclaw-daemon-in-app.md), [ADR-0018](../adr/0018-tool-less-agent-for-unattended-turns.md) |
| Last checked against code | 2026-10-22, commit `64e1087` |

## Purpose

When the assistant was not running (phone off, app closed or killed) or had no internet, it says
at once that it is back and why, gathers what happened meanwhile, has the `scribe` agent word it by
priority, and delivers it aloud or as a notification. After a connection cut it also retries what
was missed. Not for: firing missed reminders (that is `PhoneReminders.rescheduleAll`).

## Responsibilities

- Heartbeat: record that the process is alive once a minute.
- Decide at service start whether there was an absence and of which kind (`Absence.after`).
- Watch the default network, detect a return after an offline gap (`Absence.offline`), and report
  connectivity to `Offline.network`.
- After a cut: retry unanswered messages (`Inbox.missed`) and check followed topics whose time passed (`News.missed`).
- Gather events (reminders, emails, messages, upcoming calendar) with a starting priority.
- Run one `scribe` turn to word them; fall back to `Absence.plain`.
- Deliver aloud with the app in front, else by notification, and repeat it when the app opens.

## Interfaces

| What | Input → output | Caller |
|---|---|---|
| `CatchUp.start(context)` | → `(Away, reminders in the gap)` or `null`; starts heartbeat and network watch; once per process | `AssistantService.onStartCommand`, before `PhoneReminders.rescheduleAll` |
| `CatchUp.run(context, away, reminders)` | says it is back, gathers, asks the agent, delivers | thread `zeroclaw` 20 s after the daemon is first up, only if a token exists; network callback 10 s after a validated network returns |
| `CatchUp.offline(context)` | `Boolean` (prefs `offline > 0`) | Home `panorama` |
| `CatchUp.markTold(context)` | marks the last report delivered (persisted) | `MainActivity.onStart`, `tell` |
| `CatchUp.onBack` | `(String) -> Unit`, speaks | set in `MainActivity.onStart`, cleared in `onStop` |
| `CatchUp.last`, `told`, `coming` | state read by `MainActivity` and `panorama` | |
| `Absence.ask(owner, away, events)` | prompt for the `scribe` agent (English, asks for an answer in `Language.current`) | `run` |
| `Turns.scribe(context, prompt, done)` | own new session of `scribe`; `done(text or null)`; timeout `OWNER_MS = 120 000` ms | `run` |
| `MessageListener.retry` | answers an entry again (status `retrying`) | `missed` |
| Notifications | id 9500; channels `back-quiet` (LOW) and `back` (DEFAULT) via `Chime.channel`; tap opens `MainActivity` | `tell` |

## Configuration

Nothing is configurable: no `local.properties` key, no Settings entry, no `set_preference` key.
Thresholds are constants in `Absence` (`MIN_AWAY` 10 min, `MIN_OFFLINE` 5 min), the heartbeat
period (60 s), the start delay (20 s) and the reconnect delay (10 s) in `CatchUp` and
`AssistantService`. Android permissions: `ACCESS_NETWORK_STATE` (network watch),
`POST_NOTIFICATIONS` (API 33+) for the two notifications. The kill switch (pause) suppresses
reports and retries.

## State and data

`SharedPreferences` "absence" (plain, app-private):

| Key | Meaning | Written | Cleared |
|---|---|---|---|
| `alive` | last heartbeat (ms) | every 60 s by thread `heartbeat` (daemon thread) | never |
| `awayFrom`, `awayKind` | an absence found and still owed | `start` (`commit`) | `run`, for PHONE_OFF / CLOSED, unless paused |
| `offline` | offline since (ms) | `watch` when no active network, or `onLost` (only if 0) | on a validated network |
| `said`, `saidAt` | last catch-up text and time | `run` | never (overwritten) |
| `told` | whether `said` was delivered with the app open | `run` (`false`), `markTold` (`true`) | never |

Memory: `last` (restored from `said` at start), `told` (restored from prefs), `coming` (true from
`start` until `run`, when an absence was found), `onBack`.

## Behaviour

**Kinds** (`Absence.after(lastAlive, bootedAt, now)`, `bootedAt = now - elapsedRealtime()`):
none if `lastAlive <= 0` (first start) or gap `< MIN_AWAY = 10 min`; `PHONE_OFF` if the phone booted
after the last heartbeat, else `CLOSED`. `OFFLINE` from `Absence.offline(since, now)` when the gap
is `>= MIN_OFFLINE = 5 min`. An owed absence (`awayFrom` set by a start that died before reporting)
takes precedence, ending now.

**Order at start.** `CatchUp.start` → reminders in `[from, to]` read from `PhoneReminders.all()`
before `rescheduleAll` fires the missed ones → MCP server → daemon → wait 20 s (the listener reads
what came in) → `run` if paired.

**`run`.** `coming = false`; if `Guard.policy.stopped`, return (the absence stays owed in
`awayFrom` and is reported at a later start). Otherwise clear `awayFrom/awayKind` (not for OFFLINE),
then:

1. `tell(Absence.back(away, owner), quiet = true)` — at once, before looking. Each kind has its own
   line (string resources): the phone was off since …; the assistant was resting since …; there was
   no internet since …; each says a summary follows. `Absence.since` formats the start relative to
   the end: a time, "yesterday at" a time, a weekday (under 7 days), or month/day and time.
2. `gather` events:

| Source | Filter | Priority |
|---|---|---|
| reminders in the gap | from `start` (empty for OFFLINE) | HIGH level → `URGENT` (0), else `MISSED` (2) |
| `Mailbox.mails` | `at >= away.from` | important → `IMPORTANT` (1), else `NOTED` (5) |
| `Inbox.digest` | `time >= away.from`; text cut to 120 chars | not `Inbox.Status.ANSWERED` → `WAITING` (3), else `NOTED` (5) |
| calendar (`PhoneMcp.events`) | next 3 h | `SOON` (4) |

3. `Turns.scribe(Absence.ask(...))`: events sorted by priority, marked as data, not instructions;
   asks for a short spoken account in the owner's language, health and timed items first, then
   people waiting, a suggestion for the first item, one sentence if nothing happened, no tools, no
   mention of the note.
4. Answer blank or `null` (error, 120 s timeout) → `Absence.plain(events)`: "nothing important
   happened" or "while I was away:" plus the first 5 by priority.
5. Save `last`, `said`, `saidAt`, `told = false`; `tell(said, quiet = false)`; `Ui.refresh()`.

**`tell`.** If `onBack` is set and `Floating.appVisible`: speak it (and `markTold` for the full
report). Else post notification 9500 (the second replaces the first): quiet → title "<assistant>
is back", low importance; full → "While I was away", default importance, big text.

**When the app opens** (`MainActivity.onStart`): an untold `last` is spoken, marked told, and counts
as the greeting; the three-hour greeting is also skipped while `CatchUp.coming`.

**Network watch** (`registerDefaultNetworkCallback`): `onLost` reports offline to `Offline` and
records `offline` if unset; on `NET_CAPABILITY_VALIDATED` with `offline` set, clear it and, after
10 s, `run` if the gap qualifies (no reminders), then `missed(since)` for any gap: unless paused,
each `Inbox.missed(since)` entry (status `no_answer`, with a known conversation) is retried through
`MessageListener.retry`, and each topic from `News.missed` is checked.

**Panorama** (Home's line, `Panorama.items`): a STATE line for offline while `CatchUp.offline`
(after the paused line, before attention); a BACK item with `CatchUp.last` for 6 h after `saidAt`,
right after the state line.

## Invariants

- Gaps under 10 min (closed/off) or 5 min (offline) are never reported.
- Reminders in the gap are read before they are rescheduled.
- Nothing is reported, retried or checked while the kill switch is on.
- At most one greeting-like turn on opening the app: catch-up or greeting.
- The fallback always produces text.
- The summary is written by the tool-less `scribe` agent: what other people wrote cannot trigger an action.

## Failure modes

- Not paired 20 s after the daemon starts: `run` is skipped and `coming` cleared; `awayFrom` stays owed for the next start.
- Process dies between `start` and `run`: the owed absence is reported at the next start (ending then).
- Process dies after a report delivered only by notification: `told` is persisted, so it is still spoken when the app opens; it stays on the panorama for 6 h.
- Deep sleep stops the heartbeat; a later kill reads as a longer absence (known limitation, noted in `start`).
- Paused at return: nothing is reported; `awayFrom` stays owed.
- Flapping networks: `offline` keeps the first loss; a short gap only triggers retries.
- No calendar permission: `PhoneMcp.events` returns `null`; no SOON events.
- A retried message whose notification is gone gets no reply action and ends as `could_not_answer`.

## Security

- The summary turn runs on `scribe` (no MCP tools, only `calculator`), so the senders, message
  texts, email subjects and calendar titles it receives cannot act.
- Senders in what it gathers are the names resolved through the owner's contacts or marked unverified
  ([ADR-0019](../adr/0019-contacts-by-stable-identity.md)).
- `Turns.scribe` does not check the kill switch itself; `run` checks it once before starting.
- Notification text shows on the lock screen according to the system's settings.

## Open issues

- An absence owed because the assistant was paused is only reported at the next service start, not on resume.
