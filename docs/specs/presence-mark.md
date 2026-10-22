# Spec: Presence mark

| | |
|---|---|
| Status | Stable |
| Code | `app/.../Theme.kt` (`Palette`, `Palettes`, `Daylight`, `Ink`, `Presence`, `PresenceSymbol`, `ledRing`, `ledColor`, `ledLight`, `LED_HALO`, `EYE`, `EyeLed`, `glassBack`, `glassFront`, `VoiceLine`, `Gesture`, `Gestures`, `KsandraLogo`, `stillness`, `Modifier.landing`), `app/.../Bond.kt`, `app/.../Floating.kt`, `app/.../Chime.kt`, `app/.../MainActivity.kt` (`Ui.splashing`, `wokeAt`, `wokeBare`, `markBounds`, `micLevel`, `word`, `standIn`, `fuse`), `app/.../App.kt` (`presence()`), `app/.../Screens.kt` (`Presence`), `res/drawable/splash_mark.xml`, `splash_mark_lit.xml`, `ic_mark.xml`, `ic_launcher_foreground.xml`, `res/values-v31/themes.xml`, `res/raw/ksandra_chime.wav`, `scripts/splash.py`, `scripts/chime.py` |
| Tests | `app/src/test/.../BondTest.kt` (strands, intensity, load, the button and the floating mark light each LED the same), `LabelsTest.kt` › `DaylightTest` (night dimming), `EvanesceTest.kt`, `PreferencesTest.kt` › `whereTheFloatingMarkGoes`; no test draws the mark, the splash or the floating view |
| Decisions | [ADR-0017](../adr/0017-splash-melts-into-the-button.md), [ADR-0010](../adr/0010-zeroclaw-first-voice-first.md) |
| Last checked against code | 2026-10-22, commit `64e1087` |

## Purpose

The assistant's animated mark: a raised disc with an open ring of 25 LEDs around a glass eye with a
glowing core. It shows the assistant's state, its pending load and how much it knows about the
owner, and it is the main control (tap to talk, hold for the menu). This spec also covers what
extends the mark outside the app's screens: the system splash that lands on it, the floating mark
over other apps, the notification chime and the voice waveform. It is not the screens around it
([home-and-widgets.md](home-and-widgets.md)) and not speech itself
([owner-chat-and-voice.md](owner-chat-and-voice.md)).

## Responsibilities

- Palettes and their tuning to the hour (`Daylight.tune`); the palette in use (`Ink`).
- Draw the mark for six states (`Presence`), with gaze, blinks, gestures, listening rings and the
  voice waveform.
- Encode meaning in color: the ring's strands = `Bond.Ring` (knowledge), the halo = `Bond.Load`.
- Hand the system splash (API 31+) over to the first screen's mark without a visible seam.
- Float a small mark over other apps while no app screen is in front; show short notices beside it.
- Play the app's chime on its notification channels and while it handles another app's notification.

## Interfaces

| Interface | Input → output | Used by |
|---|---|---|
| `PresenceSymbol(state, size, modifier, pressed)` | Compose; draws on a `Canvas` of `size` | Home (`Presence`, 220 dp), setup (`SetupName`, 168 dp, 88 dp while typing), Settings header (72 dp), menu and board tile (52 dp), the thinking row in Interactions (36 dp, PROCESSING), reminder call (`KsandraLogo`, 200 dp) |
| `presence()` (`App.kt`) | `Ui`/`Guard` → `Presence`: `splashing` → RESTING, debug `pose`, `stopped` → PAUSED, `listening` → LISTENING, `speaking` → SPEAKING, `thinking` → PROCESSING, `!ready` → OFFLINE, else RESTING | screens |
| `Gestures.play(gesture)` | posts `last = gesture to nanoTime` on the main looper (no-op on the JVM) | `MessageListener` (NOTICE; READ/PERK for email), `PhoneNews` (NOTICE), `PhoneReminders` (NOD/PERK/ALERT by level), `MainActivity` approval prompt (EXPECT), `Guard.attempt` and the outbox (DONE) |
| `VoiceLine(modifier)` | the voice waveform filling the box | headers while `Ui.speaking` |
| `KsandraLogo(size, ringing)` | the mark with two expanding rings while ringing | `CallActivity` |
| `Modifier.landing(delay)` | no entrance animation when `Ui.splashing` was true at first composition, else `rise(delay)` | Home's and setup's mark |
| `Floating.update(context)` | shows or hides the overlay | `AssistantService`, `MainActivity.onStart`, `Floating.shown`, `set_preference floating_mark` |
| `Floating.shown(context, front)` | counts app screens in front (`AtomicInteger`) | `MainActivity.onStart/onStop`, `CallActivity` |
| `Floating.place(context, right?, height?)` | moves and persists (`Guard.save`) | drag release, Settings › Presence chips, `set_preference floating_place` |
| `Floating.notice(context, text, app?)` | a pill beside the mark; no-op when the mark is not showing | `AssistantService`, `MailNotice`, `PhoneNews`, `PhoneReminders`, `show`, the mark's own check |
| `Chime.play(context)` | plays `R.raw.ksandra_chime` once (`USAGE_NOTIFICATION`, follows silent mode) | `MessageListener`, `MailNotice`, `PhoneReminders`, in-app notices |
| `Chime.channel(context, id, name, importance)` | deletes `id`, creates `"$id-k"` with the chime; returns the new id | `approvals`, `outbox`, `reminders`, `news-important` (high); `important`, `news`, `routines`, `back` (default); `back-quiet` (low) |
| MCP `set_preference` | `palette`, `floating_mark` (needs the overlay permission), `floating_place` (left/right; top 0.18, middle 0.45, bottom 0.72) | owner's agent |

## Configuration

| What | Where | Default |
|---|---|---|
| Palette | Settings › Color; by voice `set_preference palette dusk|lavender|coral|night`; `policy.json` `palette` | `night` (`Palettes.default`, also for unknown keys) |
| Floating mark on/off | Settings › Presence toggle (opens the overlay permission screen when missing); by voice `set_preference floating_mark on|off`; `policy.json` `bubble` | on, shown only with the overlay permission |
| Floating mark place | drag; Settings › Presence chips (left/right; top 0.18, middle 0.45, bottom 0.72); by voice `set_preference floating_place`; `policy.json` `bubblePlace` | `right:0.62` |
| Chime | `res/raw/ksandra_chime.wav`, generated by `scripts/chime.py`; not configurable in the app | — |
| Reduced motion | the system's animator duration scale (`stillness()`) | — |
| Android permissions | `SYSTEM_ALERT_WINDOW` (floating mark); none for the mark, the splash or the chime | — |
| `local.properties` | none | — |

## State and data

| What | Where | Survives |
|---|---|---|
| `Ink.base` (palette), `Ink.hour` | Compose state; `palette` saved in `policy.json` | palette: yes; the hour is updated by the app's clock and by the floating mark's tick |
| `Guard.bubble`, `Floating.right` (default `true`), `Floating.height` (default 0.62, clamped 0.08–0.9) | `policy.json` (`bubble`, `bubblePlace` = `"right:0.62"`) | yes |
| `Floating.seen` | memory; set to `Inbox.digest.size` in `MainActivity.onStop` | no |
| `Ui.splashing`, `wokeAt`, `wokeBare`, `markBounds`, `micLevel`, `word`, `speaking` | `Ui` (memory) | no |
| `Gestures.last` | memory | no |
| Splash drawables and icon | generated by `python3 scripts/splash.py app/src/main/res/drawable/splash_mark.xml`: `splash_mark.xml` (animated), `splash_mark_lit.xml` (fully lit; also the launcher foreground through `ic_launcher_foreground.xml`), `ic_mark.xml` (silhouette: monochrome launcher layer and notification small icon); drawn in the default palette (night) | checked in |

## Behaviour

**Palettes.** `Palettes.all` = lavender, dusk (label "Sunrise"), coral ("Light coral", the only light
palette), night (`Palettes.default`; also used for unknown keys and by the splash). Every palette has
the same roles: void, sheet, mist, slate, accentTop/Bottom, onLight, warmth, rose, signal, ice,
ringA/B/C, pearl, pearlEdge, halo, background lights, glass, line, ok, wait, alert, glow.
`Daylight.of(hour)`: 0 for 0–5, 0.35 at 6, 0.65 at 7, 1 for 8–16, then 0.85, 0.7, 0.55, 0.4, 0.25 for
17–21, 0.1 after. `tune`: background lights tinted amber (`#FFA25A`, 0.35) from 17 to 20 and dimmed to
`0.45 + 0.55·d`; light palettes move void, sheet and glass toward `#E9D8D0` and glow to `0.6 + 0.4·d`;
dark palettes darken void and sheet, mute accents, signal and ice toward slate (`night·0.22`), shift
the pearl toward its edge, glow `0.55 + 0.45·d`. Ring colors are not tuned.

**Geometry** (`u = size / 120`): halo radius 60u; disc 47u (drop shadow, diagonal gradient lit from
the top left, rim stroke); ring radius 40u, `RING_LEDS` = 25 LEDs along 296.7° from 8.7° (gap near two
o'clock), each a solid core of 1.8u and a halo of 2.2× at 14 % (`LED_HALO`); glass eye radius
`EYE` = 16u (socket shadow and body lit from the top left by `glassBack`, a reflected-light arc and a
thin rim by `glassFront`, no specular highlight); core 6.5u (+0.4u breathing, +1u when PROCESSING),
clipped to the eye, offset up to 7u along the gaze with its bloom offset 4u. The core uses fixed
colors whatever the palette (`EyeLed`: core `#F4FBFF`, edge `#7CCBFF`, bloom `#4FA8FF`), resembling
a cool-white LED. Pressed: spring squash to 0.92 and the disc's shadow lowers.

**LEDs.** `ledColor(f)`: the palette's ringA/B/C across three strands (thirds of the arc, blended over
0.28–0.38 and 0.62–0.72), slate when grey. `ledLight(f)`: `Bond.color(known)` = `0.65 + 0.35·known`
for the strand, 0.7 when grey. The halo's strength combines the strand's level with a brightness wave
running along the ring. The same functions draw the in-app mark, the floating mark and (in
`scripts/splash.py`) the splash, so the three match.

**States.** Beat period LISTENING 1600 ms, PROCESSING 2600 ms, SPEAKING 1100 ms, else 4000 ms.
Wave amplitude along the ring: PROCESSING 1, LISTENING 0.45, SPEAKING 0.3, RESTING 0.18, else 0.

- RESTING: slow breathing; gaze, blinks and gestures (below).
- LISTENING: up to three rings in `signal` expand from the disc only while `heard > 0.03`, where
  `heard` is `Ui.micLevel` eased over 120 ms (`micLevel = ((rmsdB + 2) / 12).coerceIn(0, 1)`).
- PROCESSING: the ring rotates at 1.6 rad/s; when it ends it completes the turn at ≥ 0.8 rad/s and
  stops at 0.
- SPEAKING (the state, or `Ui.speaking` anywhere the mark is): the disc flattens (scaleY
  `1 − 0.85·talk`) and fades while a voice waveform grows (420 ms in, 650 ms out): width 54u, height up
  to 16u, tone ringC → ringB → ringA with level; each `Ui.word` sets the level to 1, decaying over 360 ms;
  between words an irregular swell; four fainter blurred waves behind.
- PAUSED: still, grey LEDs, slate core, no halo. OFFLINE: grey LEDs lighting one by one in a loop
  (connecting), core drawn as an outline.

**Idle motion** (when not still): gaze shifts every 1.6–5.2 s (60 % look around, 20 % recenter, 20 %
quick left-right look), the ring's start angle following `7°·gazeX`; blinks every 2.8–7 s, one in four
double; from 23:00 to 06:00 in RESTING, dimmed to 0.65, half-closed and slower.

**Gestures** (`Gesture`): NOTICE glance down-left, one light pulse, nod; READ gaze scans two lines,
then a nod; PERK blink, core widens, three pulses; NOD one nod; ALERT core widens, horizontal shake ×4,
four pulses; EXPECT recenter, half widen, two slow pulses; DONE a glint sweeps along the ring twice
with a small nod (played for performed actions that are not reads, and for queued items when sent).

**Color meaning** (`Bond`). Ring: each strand's intensity from `Memory.depth` of its areas (NONE 0,
LITTLE ⅓, SOME ⅔, WELL 1, averaged): YOU = personal, people, health, tastes; DAY = work, routines,
places; WORLD = interests, goals. Halo: `Bond.load` — URGENT (`PhoneReminders.calling`) pulsing in
`alert`; BUSY (an approval, an outgoing item, an unopened important email, or ≥ 3 unread messages) in
`wait`; else CALM in the palette's `halo`. State and load are also stated in text (panorama, labels).

**Reduced motion.** `stillness()` = `ANIMATOR_DURATION_SCALE == 0`, re-read on each `Ui.tick`: no
frame loop, no gaze, blinks or gestures, no listening rings, no voice waveform, wave amplitude 0.

**Splash (API 31+).** `values-v31/themes.xml`: background `@color/splash` (`#0C0D0F`), icon
`splash_mark`, duration 950 ms. `splash_mark.xml` (240×240 viewport, 1.6 dp per mark unit, the night
palette's ring colors): disc and rim, LED `k` lit at `60 + 24·k` ms for 180 ms, then the eye's glow and
core at `60 + 25·24` ms for 260 ms. `splash_mark_lit.xml` is the same, fully lit.

- `onCreate`: `Ui.splashing = savedInstanceState == null`; if so, `standIn()` adds over the decor view a
  plain view in the splash color with `splash_mark_lit` at 288 dp (the splash icon view is 192 dp and
  shows the central 160 of 240 units), removed after 5 s at the latest; `setOnExitAnimationListener(::fuse)`.
- `fuse`: removes the stand-in; waits for `Ui.markBounds` (set by the mark's `onGloballyPositioned`) up
  to 12 × 40 ms, else fades the splash in 250 ms. `wokeBare` = the icon animation had not finished;
  `wokeAt = now`. The background fades from 380 ms over 420 ms; the icon glides and scales over 560 ms
  (`PathInterpolator(0.2, 0, 0, 1)`), aiming each frame at the current `markBounds`, with scale
  `(to.width·94/120) / (icon.width·94·1.6/160)` so both discs match; then it fades in 200 ms,
  `splash.remove()`, `Ui.splashing = false`.
- Meanwhile the mark is RESTING and still, with ring and core colors interpolated from the default
  palette and all LEDs at full brightness; if `wokeBare` its LEDs light one by one (after 500 ms); full
  brightness eases off over 1200 ms after 600 ms; animation and the owner's palette return over 1000 ms.
  Ignored when `wokeAt` is more than 3 s old.
- Before API 31: no splash handling; the window background is `@color/background` (`#0C0D0F`).

**Floating mark** (`Floating.kt`). Shown when `Guard.setUp && Guard.bubble && !appVisible &&
Settings.canDrawOverlays`. A 56 dp `TYPE_APPLICATION_OVERLAY` window (`FLAG_NOT_FOCUSABLE |
FLAG_LAYOUT_NO_LIMITS`), x one sixth of its size from the chosen edge, y = `height·screen`.

- Tap (no movement, shorter than the long-press timeout): opens `MainActivity` (sealed intent,
  `NEW_TASK | SINGLE_TOP`, `listen=false`). Hold: haptic feedback, opens with `listen=true` (the
  activity waits up to 10 s for `Ui.ready`, then listens). Drag past touch slop: follows the finger; on
  release it snaps to the nearer side and keeps its height (`place`).
- `MarkView` (platform canvas, software layer, `u = side/120`): a dark disc and rim; 25 LEDs on a 34u
  ring (2.9u cores, `ledColor`/`ledLight`); a glass eye of 20u with an 8u core (`EyeLed`), slate when
  paused. While a reply waits in its undo window the LEDs are replaced by a `wait` countdown arc (7u
  stroke) over `undoSeconds`. A rose dot when `news() = (digest − seen) + pending approvals > 0`.
- A 1 s tick updates `Ink.hour`, checks for news and redraws only when the displayed state changed.
  When `news()` grows: a notice (`<sender> wrote to you` for a newest received digest entry, else
  `Something awaits your approval`, from string resources) and three pulses of 1600 ms.
- Notice pill: the source app's icon and one ellipsized line, on the inner side of the mark; it enters
  in 320 ms, stays 4.5 s, then dissolves following `Evanesce` timings (glass and edge fade first, then
  the content rises in thin strands, blurred on API 31+). With animations off it goes at once. A tap
  opens the app.

**Chime.** While a screen of the app is in front, what the assistant handles sounds as its chime
instead of a notification (another app's message notification is cancelled by
`MessageListener`). Channel sounds cannot change after creation, hence the `-k` channel ids.

## Invariants

- One geometry: `scripts/splash.py` uses `PresenceSymbol`'s proportions (disc 47, ring 40, LED 1.8,
  core 6.5, eye 16, 25 LEDs, 8.7° + 296.7°); changing one means regenerating the drawables.
- Listening rings appear only with sound (`heard > 0.03`), never as decoration.
- Color is never the only signal: every state and load is also stated in words.
- The floating mark never shows over the app's own screens (counted, not flagged) nor without the
  overlay permission.
- Reduced motion stops every motion of `PresenceSymbol` and `VoiceLine`.

## Failure modes

- No overlay permission: `Floating.update` hides the mark; `set_preference floating_mark on` answers
  that the owner must allow it; the Settings toggle opens the permission screen.
- `addView` / `updateViewLayout` failures are swallowed (`runCatching`); the mark is not shown.
- Splash never handed over: the stand-in removes itself after 5 s. No `markBounds` within ~480 ms:
  plain fade. Activity recreated (rotation): no splash, no landing.
- Chime file or `MediaPlayer` failure: swallowed, silent.
- Process death: `Ui` state, `Floating.seen` and gestures are lost; palette and floating place persist.

## Security

- No principal reaches the mark directly; the agent changes the palette and the floating mark only
  through `set_preference` (SETTINGS, owner only).
- Notices beside the floating mark, shown over any app, carry a sender's display name, a followed
  topic, a reminder's text, a card title or an action done under ACT_NOTIFY; never a message's or an
  email's body.
- The floating mark starts only the app's own `MainActivity`, with a sealed `listen` extra.

## Open issues

- The stand-in size (288 dp) and the landing scale rely on the platform showing the central 160 of
  240 units in a 192 dp icon view, measured on one emulator image.
- Debug-only overrides (`Ui.pose`, `shownBond`, `face`, `faceVideo`, `keepMark`) alter the drawing for
  automated captures; they are inert in release builds.
