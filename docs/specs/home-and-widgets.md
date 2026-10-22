# Spec: Home, navigation and widgets

| | |
|---|---|
| Status | Stable |
| Code | `app/.../App.kt` (`App`, `Main`, `Place`, `Sections`, `presence()`, `MenuSheet`, `ApprovalSheet`), `app/.../Screens.kt` (`Home`, `PanoramaLine`, `panorama`, `Board`, `Presence`, `TalkHint`, `Moment`), `app/.../Widgets.kt`, `app/.../HomeWidgets.kt`, `app/.../PhoneWidgets.kt`, `app/.../Panorama.kt`, `app/.../Notes.kt`, `app/.../Commands.kt`, `app/.../MainActivity.kt` (`send`, `act`, `setAppMode`, `ask`, `Ui`) |
| Tests | `app/src/test/.../WidgetsTest.kt` (rows, move, swap, limits, `layout:2` migration, sky, unread), `PanoramaTest.kt` (order, calm and arranging, curious never health), `CommandsTest.kt`, `NotesTest.kt`, `EvanesceTest.kt`, `PrivateScreensTest.kt`; `scripts/e2e.sh` taps *Undo* on Home's moment |
| Decisions | [ADR-0015](../adr/0015-interface-commands-in-code.md), [ADR-0010](../adr/0010-zeroclaw-first-voice-first.md), [ADR-0017](../adr/0017-splash-melts-into-the-button.md), [ADR-0029](../adr/0029-ui-text-in-android-string-resources.md) |
| Last checked against code | 2026-10-22, commit `01f6f19` |

## Purpose

How the owner moves through the app and what Home shows: the Presence Mark, a few widgets the
owner arranges, what needs attention now, and, in app mode, a board of every place. Places open on
request (voice, text, the mark's menu, the board), never through a permanent menu. It is not the
mark itself ([presence-mark.md](presence-mark.md)) nor the content of each place.

## Responsibilities

- Root composition: setup (`Ui.draft != null`) or the places; menu and approval sheets on top.
- Places and back navigation; interface commands handled in code, never sent to the model.
- Private screens kept out of screenshots and the recent-apps view (`FLAG_SECURE`).
- Home in two modes (assistant, app); moments; the paused state's *Resume*.
- Widgets: layout rules, drag-and-drop, add/remove, persistence; what each card shows.
- Weather: the only widget data fetched from outside the phone.
- The panorama line: what is going on, one item at a time.

## Interfaces

| Interface | Input → output | Who calls it |
|---|---|---|
| `Ui.place` (`Place.HOME` 0, `TALK` 1 (Interactions), `ACTIVITY` 2, `CONTROL` 3 (Settings), `RULES` 4 ("What I can do"), `MEMORY` 5, `PHOTOS` 6, `FILES` 7, `NEWS` 8, `CONNECTORS` 9) | the place shown | commands, menu, board, panorama, cards, `open_section`, `connect_email`, photos and files found |
| `Place.private(place, approving, connector)` | `true` for `Place.PRIVATE` (`TALK`, `ACTIVITY`, `CONTROL`, `RULES`, `MEMORY`, `CONNECTORS`), an open approval, or a connector sheet | `MainActivity` sets or clears `FLAG_SECURE` (debug builds can lift it for captures, `Ui.allowCapture`) |
| `Sections.open(key)` | places by English key (`home`, `interactions`, `activity`, `settings`, `rules`, `memory`, `photos`, `files`, `news`, `connectors`, `email`), settings anchors (`presence`, `color`, `people`, `messages`, `voice`, `offline`, `cost`, `privacy`), sheets (`name`, `family`, `work`, `apps`), `add_email`, `phone_permissions` | MCP `open_section` |
| `MainActivity.send(text, byVoice, from)` | `Commands.of` → `act(command)`, else the text goes to the owner agent; dropped when `OwnerChat.socket == null` | mic, composer, panorama, cards, widgets |
| `Commands.of(text)` | `MENU, CLOSE, HOME, TALK, ACTIVITY, CONTROL, RULES, MEMORY, PHOTOS, FILES, PAUSE, RESUME, WIDGETS, NEWS, APP_MODE, ASSISTANT_MODE` or `null`; whole-utterance patterns on normalized text, in Spanish and English (`commands.*` in `assets/lexicon.json`) | `send` |
| `setAppMode(on)` | `Guard.appMode`, `Guard.save()`, a spoken remark | menu, board, commands |
| MCP `set_mode`, `show_on_home`, `remove_from_home`, `open_section` (`INTERFACE`); `set_preference widget_add` / `widget_remove` (`SETTINGS`); `my_settings` (lists widgets) | owner principal only | owner agent |
| `Widgets.rows / fits / move / add / remove / available / encode / decode / forecast / kind / sky / unread` | pure functions over `List<Placed>` | `HomeWidgets.kt`, `PhoneMcp`, `Guard` |
| `PhoneWidgets(context).alarm() / agenda(max) / battery() / refreshWeather()`, `PhoneWidgets.weather`, `PhoneWidgets.last(context)` | read from the phone | cards, panorama, MCP `weather` |
| `Notes.put / remove / current` | the assistant's own card's notes | `show_on_home`, `remove_from_home`, the `KSANDRA` card |
| `Panorama.items(Now)`, `Panorama.curious(depth)` | ordered `Item`s | `panorama()` in `Screens.kt` |
| HTTPS GET `api.open-meteo.com/v1/forecast` | rounded coordinates → current temperature, WMO code, day/night, today's max/min, rain chance | `PhoneWidgets.fetch` |

All visible text comes from string resources through `t(R.string…)` / `tn(R.plurals…)`, in the
assistant's language ([ADR-0029](../adr/0029-ui-text-in-android-string-resources.md)).

## State and data

| What | Where | Survives |
|---|---|---|
| `Guard.widgets` (default `Widgets.DEFAULT`) | `policy.json` as `["layout:2", "GREETING:top", …]` via `Guard.save` | yes |
| `Guard.appMode` (Compose state) | `policy.json` | yes |
| Notes of the `KSANDRA` card | ZeroClaw memory (agent `owner`, category `widget`, key `widget-<title>`), with a sealed copy in `files/notes.json` | yes |
| Last weather reading | prefs `weather` (`w`, `at`) | yes |
| `Ui.place`, `Ui.menu`, `Ui.arranging`, `Ui.pending`, `Ui.connector`, `Ui.tick` | memory (`Ui`) | across rotation, not process death |
| `Arranging` (dragged, pointer, bounds, origin) | `remember` in `Home` | no |
| `PhoneWidgets.weather`, `fetchedAt`, `noPlace` | companion, memory | no |
| Panorama turn | `remember` in `PanoramaLine` | no |

`DEFAULT`: GREETING, PANORAMA, WEATHER, ALARM, AGENDA above; MESSAGES, MAIL, REMINDERS below.

## Behaviour

**Root and places** (`App`, `Main`). A one-second clock bumps `Ui.tick`. Places cross-fade. Back
from `RULES` or `CONNECTORS` goes to `CONTROL`, from any other place to `HOME`. Places other than
Home have a header (back, eyebrow) whose title becomes the voice line while the assistant speaks.
While the assistant listens, thinks or speaks, and during a reminder call, the screen stays on
(`keepScreenOn`).

**The menu** opens only on request: a long press on the mark or the `MENU` command (which also says
`MENU_PROMPT` aloud when spoken or when voice is on). Options: Interactions, Activity, Settings,
News, Files, Photos, Memory, "What I can do", App mode / Assistant mode, Pause / Resume. `CLOSE` or
a choice closes it. The menu and the app-mode board use the same icon for each place, and no two
places share one.

**Home** (`Home`):
- Assistant mode: widget rows above; the mark at 220 dp centred in the remaining room (it never
  shrinks for widgets), with `TalkHint` under it (hidden when paused or arranging); widget rows below.
- App mode: both widget areas together, then `Board`: 12 tiles four to a row — Talk, Interactions,
  Activity, News, Memory, Photos, Files, Settings, "What I can do", Widgets, Pause / Resume,
  Assistant mode (drawn as the live mark). Tiles arrive staggered; modes cross-fade.
- The mark: a tap ends arranging, else listens (if not paused and `Ui.ready`); a long press opens the menu.
  `TalkHint` shows the listening state; a microphone indicator appears only when the open
  microphone is on.
- Moments (at most two, none while arranging): the pending approval (*Decide* re-opens the sheet);
  each outgoing item with its countdown and *Undo* (`Guard.undo`); the newest unopened important
  email of the last 12 h (*Read* calls `MainActivity.openMail`); the first missing permission
  (*Fix it*). The countdown rounds up: it shows 1 until the reply goes, never 0.
- Paused: a *Resume* button under everything.

**Evanescing.** A moment whose time is over (a reply sent, an approval answered) is not cut: its
card fades and its words dissolve upward (`Evanescing`; timings and distances in `Evanesce`,
`Theme.kt`). While leaving it ignores touch and is hidden from accessibility; with animations off
it goes at once. Its place then closes as a falling piece would (`Evanesce.FALL_MS`). The same
applies to outgoing items in Interactions, an approval sheet whose request ended while shown
(`ApprovalSheet`; its buttons do nothing meanwhile), a widget value that passed (`Passing` in
`HomeWidgets.kt`, keyed by what it is about, so a language change does not trigger it; a placeholder
is simply replaced), and the floating mark's notice pill.

**Widgets** (`Widgets.kt`). Kinds and spans: GREETING and PANORAMA span 4 (a whole row); WEATHER,
ALARM, AGENDA, MESSAGES, MAIL, REMINDERS, BATTERY, NEWS, KSANDRA span 1. `COLUMNS = 4`,
`ROWS_ABOVE = 3`, `ROWS_BELOW = 1`.
- `rows`: in order, a wide widget takes its own row; cards fill a row while the spans fit in 4.
- `fits`: rows above ≤ 3 and below ≤ 1.
- `move(widget, target, top)`: insert at the target's place (or last in the area when `target` is
  `null`); if that does not fit, swap the two; if neither fits, unchanged.
- `add`: below first, else above, else unchanged. `available`: absent widgets that `add` can place.
- `decode`: `null` → `DEFAULT`; malformed or unknown entries dropped, duplicates removed; a list
  without `"layout:2"` gets GREETING and PANORAMA on top and keeps only its cards; then the last
  entries are dropped until it fits.

**Arranging** (`HomeWidgets.kt`): a long press and drag on any widget (or the `WIDGETS` command, or
the board's Widgets tile) sets `Ui.arranging`. Cards wobble; the dragged one follows the finger, its
slot faded. The drop target is the card under the finger or an area; `drop()` applies
`Widgets.move` and saves. Each card has a "−" to remove. Free cells are outlined; an area with room
shows an empty row. The bar offers *Add* (when any is available; opens `AddWidgetSheet`) and *Done*.

**Cards** (one value, one caption; `Ui.tick` recomposes them every second). Cards keep one shape
(`TILE_RATIO`, 84 × 74): on a narrower phone they are smaller copies, never stretched; a figure
shrinks to fit rather than being cut (`Fitted`), and so does the greeting; a caption that does not
fit glides once.

| Widget | Shows | Tap |
|---|---|---|
| WEATHER | the sky drawn (`SkyIcon`: sun or moon by `is_day`, partly, cloudy, fog, rain, snow, storm, from `Widgets.kind(code)`) beside `now°`; under it `Widgets.forecast`: the rain chance when it is at least `Widgets.RAIN` (30 %) in the next 12 h and it is not already raining or snowing, else the sky in one short word (`Widgets.sky`). Without location permission "Tap"; with location off "Location · turn it on"; no data yet "…" | asks `ACCESS_COARSE_LOCATION` (after a denial, opens the app's settings); with location off, opens the location settings |
| ALARM | next clock alarm (`AlarmManager.nextAlarmClock`), time and day; alarms set by a calendar app or by this app's reminder calls are ignored | opens the clock's alarms |
| AGENDA | next event today or tomorrow, "Free", or "—" without access | asks what is on today and tomorrow |
| MESSAGES | unread count, icons of the two apps with most | opens Activity |
| MAIL | unopened count; the value is warm when any is important | asks which emails are unread |
| REMINDERS | the earliest reminder's time and text (warm when high) | asks which reminders are set |
| BATTERY | percent, charging; warm at ≤ 20 % when not charging | — |
| NEWS | last briefing's topic (warm under 3 h) | opens News |
| KSANDRA | notes the assistant put there (`show_on_home`: title, value, optional expiry in hours); several take turns every 6 s | asks about the first note |
| GREETING (wide) | good morning (6–11), good afternoon (12–19), else good evening, with the owner's name; in app mode, the voice line while the assistant speaks | — |
| PANORAMA (wide) | `PanoramaLine` | per item |

`show_on_home` also adds the KSANDRA card when it is missing and there is room, and says so when
there is none; `remove_from_home` removes a note by title.

**Weather** (`PhoneWidgets`). The card calls `refreshWeather()` on show and every 60 s; it fetches
only with coarse location granted, location services on, and 30 min since the last attempt. Place:
the newest last-known location of the enabled providers, else a current fix. Sent: latitude and
longitude formatted `%.1f` (about 10 km), `current=temperature_2m,weather_code,is_day`,
`daily=temperature_2m_max,temperature_2m_min`, `hourly=precipitation_probability`,
`forecast_hours=12`, `timezone=auto`, `forecast_days=1`; no key, no account; 10 s call timeout. The
reading is persisted for offline answers. A failure resets the timer, so the next minute retries.

**Panorama** (`Panorama.items`). First a STATE line when it matters — arranging (then only that),
paused, offline, or "something to show below" (an approval, an outgoing item or missing
permissions) — then: BACK (catch-up of the last 6 h), important MAIL (12 h; a touch opens it),
MESSAGE (one: sender and text; several: count and senders; opens Activity), REMINDER (next 12 h),
AGENDA (next event; a touch asks about the day), NEWS (briefing of the last 24 h; opens News),
unread MAIL count, CURIOUS (first memory area at NONE, never HEALTH). Nothing: an all-calm line. One
item at a time, the next after 5 s plus 160 ms per character over 40; a long line glides once from
its start. Touch: open `place`; for CURIOUS, the assistant asks the question in its own words
(`MainActivity.ask`) and listens once for the answer; else send `ask`; else speak the line.

## Invariants

- A command recognized by `Commands.of` never reaches the model; anything else always does.
- Widget layout always `fits` after `move`, `add` and `decode`; each widget at most once.
- The mark on Home keeps 220 dp in assistant mode whatever the widgets.
- Only the weather request leaves the phone from Home, with coordinates rounded to 0.1°.
- No permanent menu: places open on request; every back leads to Home (or to Settings).
- A private screen is never captured in a screenshot or the recent-apps thumbnail (release builds).

## Failure modes

- No calendar access: AGENDA "—", no AGENDA panorama item. No location permission: WEATHER "Tap";
  location off: "Location · turn it on"; no fix: "…", retried at the next minute; network error:
  logged, retried.
- `ACTION_SHOW_ALARMS` with no clock app: swallowed.
- Not connected (`OwnerChat.socket == null`): a non-command `send` is dropped silently; card and
  panorama questions do nothing (a CURIOUS question is spoken instead).
- Process death: place, arranging and the in-memory weather are lost; layout, mode and notes persist.

## Security

- Only the owner uses these screens; the agent reaches them only through owner-principal tools
  gated by `Guard.gated` (`INTERFACE`, `SETTINGS`).
- The panorama and cards show third parties' names and message text on Home (not a private place).
- The weather request carries coarse coordinates and the device's IP to `api.open-meteo.com`.
- Notes on Home are written by the owner agent; their text is the model's.

## Open issues

None known.
