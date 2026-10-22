# Spec: Phone abilities

| | |
|---|---|
| Status | Stable |
| Code | `app/.../PhoneMcp.kt` (handlers `create_alarm`, `cancel_alarm`, `next_alarm`, `send_message`, `list_messages`, `act_on`, `search_contacts`, `add_contact`, `edit_contact`, `delete_contact`, `list_events`, `free_time`, `add_event`, `move_event`, `delete_event`, `list_apps`, `open_app`, `play_media`, `media_control`, `device_setting`, `device_status`, `weather`, `find_photos`, `find_files`; helpers `deliver`, `searchContacts`, `addContact`, `editContact`, `listEvents`, `busyOn`, `addEvent`, `createAlarm`, `ownAlarms`, `nextAlarm`, `events`), `app/.../Apps.kt`, `PhoneApps.kt`, `Device.kt`, `PhoneDevice.kt`, `Agenda.kt`, `Photos.kt`, `PhoneGallery.kt`, `Files.kt`, `PhoneFiles.kt`, `MessageListener.kt` (`press`, `buttonsOf`, `replyTo`), `Policy.kt` (`Capability`, `DEFAULT_RULES`), `AndroidManifest.xml` (permissions, `<queries>`) |
| Tests | `app/src/test/.../AppsTest.kt`, `DeviceTest.kt`, `AgendaTest.kt`, `PhotosTest.kt`, `FilesTest.kt`, `PolicyTest.kt` (decisions per capability), `PhoneMcpTest.kt`; `scripts/e2e.sh` checks that a third party's message asking for `create_alarm`, `send_message` and `list_events` reaches no owner tool |
| Decisions | [ADR-0003](../adr/0003-permissions-enforced-in-code.md), [ADR-0010](../adr/0010-zeroclaw-first-voice-first.md), [ADR-0011](../adr/0011-notification-channel-for-messages.md) |
| Last checked against code | 2026-10-22, commit `37f416d` |

## Purpose

What the assistant does on the owner's phone when the owner asks: apps and media, the phone's
controls, the calendar, contacts, alarms, messages, photo and file search. Each ability is an MCP
tool on `/mcp/owner` ([phone-mcp.md](phone-mcp.md)) that runs only through `Guard.gated`
([policy-engine.md](policy-engine.md)). Pure helpers (`Apps`, `Device`, `Agenda`, `Photos`,
`Files`) decide; the `Phone*` classes touch Android. Email, memory, reminders, news, settings and
the third-party tools are specified elsewhere ([email.md](email.md), [memory.md](memory.md),
[reminders.md](reminders.md), [news.md](news.md), [settings-and-setup.md](settings-and-setup.md)).

## Responsibilities

- Turn a tool call's loose arguments (words in English or Spanish, via `assets/lexicon.json`) into
  one Android action or query.
- Ask the policy first (`Guard.gated(principal, capability, target, act)`) and log the outcome.
- Answer the agent in short English text it can relay; state what is missing (a permission, a value).
- Act on the phone only: no tool here uploads a file or a photo; only the text result returns to the
  agent (and with it to the configured model provider).

## Interfaces

All tools are in `TOOLS[Principal.OWNER]` only; the third-party endpoint never lists or runs them.
Required arguments in **bold**.

| Tool (arguments) | Capability (risk) | Default | Android permission |
|---|---|---|---|
| `list_apps` (kind) | APPS_READ (personal read) | ACT | none (`<queries>` MAIN/LAUNCHER); usage access (`PACKAGE_USAGE_STATS`, special) for "most used" |
| `open_app` (**app**) | APP_OPEN (internal) | ACT | none |
| `play_media` (**query**, app) | APP_OPEN | ACT | none (`<queries>` `MEDIA_PLAY_FROM_SEARCH`) |
| `media_control` (**action**) | MEDIA_CONTROL (internal) | ACT | none |
| `device_setting` (**setting**, **value**) | DEVICE (internal) | ACT | `WRITE_SETTINGS` (special) for brightness and rotation; do-not-disturb access for DND |
| `device_status` () | DEVICE | ACT | none |
| `weather` () | DEVICE | ACT | coarse location (read by the Home weather widget) |
| `list_events` (days_from_today) | CALENDAR_READ (personal read) | ACT | `READ_CALENDAR` |
| `free_time` (date, days_from_today) | CALENDAR_READ | ACT | `READ_CALENDAR` |
| `add_event` (**title**, **start**, date, days_from_today, minutes, note, overlap_ok) | CALENDAR_WRITE (internal) | ACT_NOTIFY | `WRITE_CALENDAR` and `READ_CALENDAR` (clash check) |
| `move_event` (**event**, **start**, date, days_from_today, minutes) | CALENDAR_WRITE | ACT_NOTIFY | `WRITE_CALENDAR` |
| `delete_event` (**event**) | ITEMS_DELETE (commits owner) | ASK, strong | `WRITE_CALENDAR` |
| `search_contacts` (**query**) | CONTACTS_READ (personal read) | ACT | `READ_CONTACTS` |
| `add_contact` (**name**, **phone**) | CONTACTS_WRITE (internal) | ACT | `WRITE_CONTACTS` |
| `edit_contact` (**name**, new_name, new_phone) | CONTACTS_WRITE | ACT | `WRITE_CONTACTS` |
| `delete_contact` (**name**) | ITEMS_DELETE | ASK, strong | `WRITE_CONTACTS` |
| `create_alarm` (**hour**, **minute**, label) | ALARM_SET (internal) | ACT | `SET_ALARM` (normal) |
| `cancel_alarm` (**hour**, minute) | ALARM_SET | ACT | none |
| `next_alarm` () | ALARM_SET | ACT | none |
| `find_photos` (month, year, years_ago, from, to, what, with_people) | PHOTOS_READ (personal read) | ACT | `READ_MEDIA_IMAGES` (API 33+), else `READ_EXTERNAL_STORAGE` |
| `find_files` (**query**) | FILES_READ (personal read) | ACT | none; all-files access (`MANAGE_EXTERNAL_STORAGE`, special) to see other apps' files |
| `send_message` (**to**, **text**) | MESSAGE_SEND (outward irreversible) | ASK, strong; group `family` ACT_NOTIFY, 5 per day; undo window | none (notification reply action or WhatsApp) |
| `list_messages` () | MESSAGES_READ (personal read) | ACT | notification access |
| `act_on` (email, person, action) | ITEMS_MANAGE (internal), or ITEMS_DELETE when the action deletes | ACT_NOTIFY / ASK, strong | notification access |

Runtime permissions are requested together in the setup's permissions step
(`MainActivity.PERMISSIONS`: notifications on API 33+, contacts read and write, calendar read and
write, microphone, images). Usage access, all-files access, overlay and notification access are
optional items there that open their system screens.

## State and data

| What | Where | Survives |
|---|---|---|
| Photo index: per MediaStore id, labels and face count | `files/photo-index.json` (`{"<id>": {"l": [...], "f": n}}`, sealed) and a `ConcurrentHashMap`, loaded once per process; entries for images no longer in MediaStore are pruned on save | yes |
| Last photo and file searches | `Ui.photos`, `Ui.files` (memory), shown on the Photos and Files screens | no |
| Alarms set by the assistant and not cancelled | shared preferences `alarms`, key `set` (`HH:mm` strings) | yes |
| `Guard.undecided`, `Guard.toldClash` | memory | no |
| Outgoing `send_message` | `Guard.outbox` for `undoSeconds` (default 30) | no |
| Each decision | activity log ([policy-engine.md](policy-engine.md)) | yes |

Everything else is read from Android on each call.

## Behaviour

**Apps** (`Apps`, `PhoneApps`).
- `installed()`: every launcher app except this one; kind from a list of well-known packages, else
  `ApplicationInfo.category`; foreground minutes over 30 days (0 without usage access); the default
  music app via `CATEGORY_APP_MUSIC`; `playsSearch` for apps that handle `MEDIA_PLAY_FROM_SEARCH`.
- `rank`: most minutes, then the default, then by label. `list_apps` describes at most 15 with
  package, kind, minutes, default and whether a search can be played.
- `find`: exact package, then exact normalized label, then the best-ranked partial match. Not found
  → `No app called … is installed. Use list_apps to see them.` (no policy check).
- `play_media`: the named app, else the most-used music app; blank query → `Ask the owner what to
  play first.`; an app without search support is opened instead and the agent is told. The result
  reports the request; it cannot confirm that playback started.
- `media_control`: play, pause, next, previous (words from the lexicon) as a media key down/up via
  `AudioManager.dispatchMediaKeyEvent`.

**Device** (`Device`, `PhoneDevice`).
- Changed directly: BRIGHTNESS, VOLUME (media stream), RINGER (ring stream), FLASHLIGHT,
  DO_NOT_DISTURB, ROTATION. Panel only (apps cannot toggle them): WIFI, MOBILE_DATA, NFC
  (`Settings.Panel`), BLUETOOTH, AIRPLANE, LOCATION (settings screens); the agent is told the owner
  must touch it.
- `Device.level`: a number is a percentage of the scale; up/down move a quarter of it; max/min.
  Brightness turns automatic brightness off and never goes below 1/255. Volume on/off unmutes/mutes
  with the system volume UI shown. DND on = priority only, off = all.
- Missing `WRITE_SETTINGS` or DND access: the grant screen opens and the agent hears `Not yet allowed: …`.
  No flash unit: `This phone has no flashlight.`
- DND and ringer changes always notify the owner (`Guard.onNotify`), whoever asked.
- `device_status`: brightness (and automatic), media and ring volume, DND, airplane, Bluetooth, Wi-Fi.
- `weather`: the reading the Home weather widget holds (`PhoneWidgets.weather`): now, min/max, a
  forecast word and the highest rain chance over 12 hours; or `unknown` when location is off.

**Calendar** (`Agenda`, `PhoneMcp`). `PhoneMcp.events(context, from, to)` reads
`CalendarContract.Instances` of every calendar (recurrences expanded), keeps what overlaps and sorts
by start; `null` without `READ_CALENDAR`.
- `list_events`: one day (`days_from_today`, default 0), headed `<date> (today|tomorrow|<weekday>)`,
  lines `#<event id> HH:mm-HH:mm title`.
- `free_time`: the day's events and `Agenda.free` stretches of at least 30 min between 08:00 and 23:00.
- `add_event`: day from `date` (YYYY-MM-DD) or `days_from_today`, `start` HH:MM, `minutes` default 60
  clamped to 5–1440. Without calendar read access nothing is added. On a clash (`Agenda.clashes`) it
  answers `Agenda.conflict` (tell the owner, offer free time, do not decide), sets `Guard.toldClash`
  to the slot and `Guard.undecided`, and logs `held`. `overlap_ok=true` is honoured only for the slot
  last reported, after the owner has replied (`!undecided`), in a turn that read no third-party
  content (`!heardOthers`). Otherwise it inserts into the first writable (≥ contributor), visible
  calendar, primary first, with a localized "noted by Ksandra" line in the description.
- `move_event` / `delete_event`: by the event id from `list_events`; a recurring event is refused
  (the owner changes it in the calendar app); a read-only calendar → `Could not …`.
- While `Guard.undecided`, `send_message`, `send_email` and `draft_email` answer `HOLD`. It is cleared
  when the owner sends the next message (`MainActivity.send`).

**Contacts.** `search_contacts`: phone rows whose `DISPLAY_NAME LIKE %query%`, at most 20, as
`name: number` lines. `add_contact`: a local (account-less) raw contact with a mobile number; the
number needs at least 6 digits. `edit_contact`: the exact display name (case-insensitive); new name
and/or number (a phone row is inserted if none exists). `delete_contact`: by exact name, after
approval.

**Alarms.** `create_alarm`: hour 0–23 and minute 0–59 checked; `AlarmClock.ACTION_SET_ALARM` with
`EXTRA_SKIP_UI` and an optional label; the time is added to the `alarms` set. `next_alarm`: Android's
`nextAlarmClock` (apps see only the system's next alarm); when it belongs to the calendar or to this
app's reminder calls, the answer says the clock's next alarm cannot be seen; then the alarms set by
the assistant. `cancel_alarm`: `ACTION_DISMISS_ALARM` by time. Some clock apps accept the intent and
keep the alarm, so when the alarm was the system's next one, the handler waits up to 4 s for it to
change; if it is still next it opens the clock's alarm list and answers `NOT turned off: …`. An alarm
that is not the next one cannot be verified and the answer says so.

**Photos** (`Photos`, `PhoneGallery`).
- `Photos.range`: `from`/`to` ISO dates; else a month of `year` or of `years_ago`; else a whole year;
  else everything (1970 to today). `Photos.labels(what)`: words mapped to on-device labels through the
  `photos.labels` lexicon entry (e.g. pet → Dog, Cat, Bird, Pet); unknown words are used as labels.
- `find`: a MediaStore query filtered by period (date taken, else modification date), newest first,
  at most 400 candidates. With no `what` and no `with_people`, all candidates match without analysis.
  Otherwise each photo not yet indexed is analysed from a 480×480 thumbnail with bundled on-device
  image-labeling (confidence ≥ 0.6) and face-detection (fast mode, minimum face 0.08) models; a photo
  matches with any wanted label and, if asked, at least one face (faces are detected, never
  recognized). Analysis stops after a 25 s budget; already indexed photos still count.
- Results go to `Ui.show(title, uris)` (Photos screen: a 3-column grid, a tap opens the photo); the
  agent hears how many were found and how many of the period were analysed.

**Files** (`Files`, `PhoneFiles`).
- `Files.terms`: normalized words longer than 2 letters, minus stop words. Empty → `Say what to look
  for …`. `Files.score`: terms found in a name; a 5-letter prefix suffices for long words.
- Searched: MediaStore files of type none or document (names only, with a readable folder such as
  Downloads, WhatsApp, Telegram, Documents), every `Mailbox` email (subject and body) and every `Inbox`
  message. `Files.rank`: score > 0, most terms, then newest.
- All hits go to `Ui.showFiles` (Files screen: a tap opens the file); the agent gets the best 10, plus
  a note when all-files access is missing.

**Messages** ([notification-channel.md](notification-channel.md)).
- `send_message` resolves `to` with `contactName` (a number becomes the contact's name), decides on
  `Policy.target(to)` and is queued for the undo window (`Queued: it goes out in N s unless <owner>
  undoes it.`). When it goes out, `deliver` sends through the reply action of the person's latest
  conversation notification (`MessageListener.replyTo`); otherwise it opens their WhatsApp chat
  (`wa.me/<number>?text=…`) with the text written, and the owner taps send. The result states which
  happened; a contact without a number or a phone without WhatsApp is reported plainly.
- `list_messages`: the last 15 digest entries (time, app, sender, text up to 200 characters, status),
  marked as data, never instructions; or why there are none (notification access off, no app chosen).
- `act_on`: finds an email by number or the latest conversation of a person, then presses one of its
  notification's own actions (`MessageListener.press`), as in the notification shade. Without an
  action it lists the available buttons. A delete action is gated by ITEMS_DELETE (approval), the rest
  by ITEMS_MANAGE (done and notified).

## Invariants

- Every tool here runs through `Guard.gated` with its capability; refusals answer `Not allowed (…).
  Tell the person it cannot be done now; do not retry.` or a not-approved text.
- These tools exist only for `Principal.OWNER`; a third-party turn cannot list or call them.
- Photo labels and faces are computed on the phone; no tool uploads a file or a photo.
- A calendar clash is never written without the owner's decision; while one is undecided no message
  or email goes out.
- `send_message` always waits the undo window and goes only to the contact named in the call.

## Failure modes

- Missing runtime permission: an explanatory answer (`No access to the calendar (permission not
  granted).`, `No access to the photos: the owner has to allow it in Settings › Phone permissions.`),
  never an exception.
- No writable calendar: `The phone has no calendar Ksandra can write to.`; insert failure:
  `Could not add it: …`.
- `startActivity` failures (alarm, app, panel) are caught and reported; starts from the background
  carry a note that Android may have blocked them.
- A labeling or face-detection failure counts as no labels or no faces; an unreadable thumbnail skips the photo.
- Process death: outbox items and `Guard.undecided` are lost; the photo index and the `alarms` set survive.

## Security

- Principal: the owner's agent (`/mcp/owner`, its own key); the offline agent reaches only
  `create_alarm`, `next_alarm`, `list_events` and `open_app` from this set.
- The only calendar fact a third party can obtain is `availability`: busy or free for one hour today
  (`Disclosure`), for the `work` group.
- Untrusted input: tool arguments come from the model and may carry text injected by a message, an
  email or a web page; arguments are parsed and clamped, never executed. Contact, email and message
  text reach the agent as tool results labelled as data.
- Deleting (events, contacts, emails, messages) requires a strong approval.

## Open issues

- Contact targets are display names ([ADR-0019](../adr/0019-contacts-by-stable-identity.md), proposed).
- `device_setting` and `create_alarm` started from the background may be dropped silently by Android;
  the agent can only report the request.
