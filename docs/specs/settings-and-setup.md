# Spec: Settings and setup

| | |
|---|---|
| Status | Stable |
| Code | `app/.../Preferences.kt`, `app/.../PhoneMcp.kt` (`set_preference`, `my_settings`, `set_contact_group`, `set_mode`, `set_address`, `open_section`, `list_rules`, `set_rule`, `usage`; `prefer`, `widens`, `settings`; `Guard.load`/`save`), `app/.../Approvals.kt` (`Saved`), `app/.../MainActivity.kt` (`Draft`, `saveSetup`, setters, `lacking`), `app/.../App.kt` (`Sections`, `Place`, `Setup`, `SetupName`, `SetupNeeds`, `SetupPeople`, `DraftPicker`), `app/.../Screens.kt` (`Control`, `Rules`, `NameSheet`), `app/.../Connectors.kt`, `app/.../Voice.kt`, `app/.../Language.kt`, `app/.../Usage.kt`, `app/.../Offline.kt`, `app/.../Lexicon.kt` |
| Tests | `app/src/test/.../PreferencesTest.kt` (setting names, values in words, floating place), `LanguageTest.kt`, `VoiceTest.kt`, `WidgetsTest.kt`, `PrivateScreensTest.kt`, `PolicyTest.kt`; `scripts/e2e.sh` sets owner, apps, groups, stop and contacts through debug extras |
| Decisions | [ADR-0010](../adr/0010-zeroclaw-first-voice-first.md), [ADR-0003](../adr/0003-permissions-enforced-in-code.md), [ADR-0008](../adr/0008-app-writes-persona-files.md), [ADR-0006](../adr/0006-kill-switch-in-the-app.md), [ADR-0025](../adr/0025-an-offline-agent-on-a-local-model.md), [ADR-0027](../adr/0027-assistant-voice-from-a-cloud-speech-model.md), [ADR-0029](../adr/0029-ui-text-in-android-string-resources.md) |
| Last checked against code | 2026-10-22, commit `64e1087` |

## Purpose

The assistant's own settings and how the owner changes them: by voice (the owner's agent calls
`set_preference`, `set_contact_group`, …) or on the Settings screen; and the first-run setup that
records the owner's name, language, permissions, the messaging apps read and who is family or work.
Not covered: the rules themselves ([policy-engine.md](policy-engine.md), [access model](access-model.md))
and the phone's own controls (`device_setting`, [phone-abilities.md](phone-abilities.md)).

## Responsibilities

- Parse a setting name and value said in words (`Preferences.Setting.named`, `warmth`, `palette`,
  `widget`, `floatingPlace`, `ceiling`, `natural`; `Language.named`; keywords in Spanish and English
  from `assets/lexicon.json` through `Lexicon`).
- Apply a setting under SETTINGS, or CHANNEL when it widens access, and report what changed (`prefer`).
- Report the current settings (`my_settings`), change contact groups (CONTACT_GROUPS) and rules (RULES).
- Navigate the app to a named part on request (`open_section`).
- Run the three-step first-run setup and save it, with the owner's credential on a secured phone.
- Persist everything in `policy.json` (learning in `memory.json`).

## Interfaces

| MCP tool (owner endpoint only) | Arguments | Capability / default | Result |
|---|---|---|---|
| `set_preference` | `setting`, `value` | SETTINGS (INTERNAL) ACT; CHANNEL (COMMITS_OWNER) ASK with credential when `widens` | what changed, `Unknown setting …` or `Did not understand …` |
| `my_settings` | — | SETTINGS, ACT | warmth, voice, open mic, colors, learning, email, floating mark, undo window, group chats, language, offline status, missing permissions, widgets, family, work |
| `set_contact_group` | `contact`, `group` = family \| work \| none | CONTACT_GROUPS, ASK, strong | `X is now family.` / `X is no longer family or work: …` |
| `set_mode` | `mode` = app \| assistant | INTERFACE, ACT | sets `Guard.appMode` |
| `set_address` | `form` = masculine \| feminine \| neutral | MEMORY, ACT | sets `Policy.address`, rewrites the persona files |
| `open_section` | `section` | INTERFACE, ACT | opens that part of the app (below); unknown → `NOT opened: no section "…". The sections: …` |
| `list_rules` | — | SETTINGS, ACT | numbered rules: principal, what, for whom, how |
| `set_rule` | `rule` (number), `autonomy` = never \| ask \| act_notify \| act | RULES, ASK, strong | the rule's new state; clamped to its ceiling (and says so) |
| `usage` | — | SETTINGS, ACT | ZeroClaw's `/api/cost` this month, today and per agent (`Usage.describe`), plus the natural voice's minutes and ceiling |

`widens` (CHANNEL instead of SETTINGS): `group_chats`, `read_mail` or `open_mic` turned on; a
messaging app added; `undo_seconds` lowered; `voice_ceiling` raised or removed while a ceiling is set.

**`open_section`** (`Sections.open`, run on the main thread, then `MainActivity.bringToFront`):

| Kind | Keys | Effect |
|---|---|---|
| Place | `home`, `interactions`, `activity`, `settings`, `rules`, `memory`, `photos`, `files`, `news`, `connectors`, `email` (= connectors) | `Ui.place` |
| Settings anchor (`Sections.ANCHORS`) | `presence`, `color`, `people`, `messages`, `voice`, `offline`, `cost`, `privacy` | Settings scrolled to that section (`Ui.section`; each section records its offset with `onGloballyPositioned`) |
| Settings sheet (`Sections.SHEETS`) | `name`, `family`, `work`, `apps` | Settings with that sheet open |
| Other | `add_email` | Connectors with the provider picker (`Ui.connector = Connectors.PICK`) |
| | `phone_permissions` | the setup's permissions step (`Draft.current()`, step 1) |

Connectors (`Place.CONNECTORS`, `Connectors.kt`) holds what the assistant is connected to, by kind:
email accounts now ([email.md](email.md)). `Ui.connector` opens a connector's sheet over any place,
after any open card (such as a provider guide) is closed.

App side (`MainActivity`): `saveSetup(d, what)`, `setLanguage`, `setVoice`, `chooseVoice`,
`setVoiceCeiling`, `setOpenMic`, `setWarmth`, `setPalette`, `setMail`, `setBubble`, `setUndoSeconds`,
`setOffline`, `setStopped`, `setAppMode`, `editRule`, `setLearning`; `Floating.place(context, right, height)`.

## Configuration

### Build time (`local.properties` → `BuildConfig`)

Every key, its `BuildConfig` field, the class that reads it and the build requirements are in
[architecture › configuration](../architecture/configuration.md), the single reference. In short:
required to run are `sdk.dir` (or `ANDROID_HOME`) and the model (`zeroclaw.provider`,
`zeroclaw.model`, `zeroclaw.apiKey`); everything else is optional and its absence removes or
degrades one feature: the natural voice and the offline option do not appear in Settings
(`CloudVoice.configured`, `Offline.configured`), `usage` counts tokens instead of dollars,
`find_videos` answers with a search link, web search uses DuckDuckGo without a key.

### Minimal start-up path

1. Build and install (`./gradlew assembleDebug`, `adb install -r -g …`; `-g` grants the runtime
   permissions, so on a device the setup asks for them instead).
2. First launch: `MainActivity.onCreate` finds `Guard.setUp` false and opens the setup
   (`Ui.draft = Draft.fresh()`); `AssistantService` starts in the background and brings up the
   daemon meanwhile (the mark shows *Connecting* until `/ws/chat` is open).
3. Step 0: pick the language, say or type the owner's name. Required: a non-blank name.
4. Step 1: permissions, each optional to continue (below).
5. Step 2: the messaging apps read (none by default), group chats (off), family and work (nobody),
   Speak aloud (off); *Start* saves (credential on a phone with a screen lock). Nothing is read
   and nobody is answered until this save.
6. The introduction appears in the chat, listing what is still missing; Home then shows one missing
   permission at a time with a *Fix* action (`lacking()`).

What each permission is for (step 1 and `lacking()`):

| Need | Mechanism | Why |
|---|---|---|
| Notifications, contacts, calendar, microphone, photos | runtime permissions (`PERMISSIONS`): `POST_NOTIFICATIONS` (33+), `READ_CONTACTS`, `WRITE_CONTACTS`, `READ_CALENDAR`, `WRITE_CALENDAR`, `RECORD_AUDIO`, `READ_MEDIA_IMAGES` (33+) or `READ_EXTERNAL_STORAGE` | approvals and notices; who is who, groups; agenda and events; voice in; photo search |
| Fingerprint (optional) | security settings | a strong biometric for approvals; a screen lock (PIN, pattern, password) is enough, and without any the strong approvals are refused ([access-model.md](access-model.md#open-issues)) |
| Stay active | `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Android would stop the service and the daemon |
| Autostart, lock-screen call (Xiaomi, Redmi, POCO only) | the vendor's permission screens | the vendor kills background apps and blocks full-screen activities |
| Call you when something matters | `USE_FULL_SCREEN_INTENT` (user-granted from Android 14) | a high reminder rings full screen (`CallActivity`) |
| Search your files (optional) | `MANAGE_EXTERNAL_STORAGE` | `find_files` beyond the app's own view |
| Know which apps you use most (optional) | `PACKAGE_USAGE_STATS` | `list_apps` ranks by use; `play_media` picks the most used app |
| Always at hand (optional) | `SYSTEM_ALERT_WINDOW` | the floating mark, undo countdown and notices over other apps; also lets tools start activities from the background |
| Read your notifications (optional) | notification-listener access (`MessageListener`) | the notification channel and email from notifications; nothing is read without it |

From Android 13 a sideloaded app cannot be granted notification access or overlay until the owner
allows restricted settings in the app's info page (`restricted()`, the *Fix* action opens it).

### In the app and by voice

The table under State and data gives, per setting, the `set_preference` key, the Settings screen
location, the `policy.json` key and the default.

## State and data

Every setting lives in `files/policy.json` (`Saved.State`, written by `Guard.save`, sealed), except
learning (`files/memory.json`).

| Setting (`set_preference` name) | Values | Default | Key | In the app |
|---|---|---|---|---|
| `language` | Spanish, English, or the phone's (`Language.named`) | the phone's (Spanish when the phone's language is not supported) | `language` | setup's first step (Spanish · English); the persona is rewritten and speech output and recognition follow at once (`Language.onChange`); following the phone, a change of the phone's language does the same |
| `offline` | on / off | off | `offline` | Settings › Without internet: on shows the explanation first (size, Wi-Fi, simpler and slower, battery) and downloads only after confirmation; status shows progress, then readiness and the answers given; off deletes the model. By voice the agent explains first and turns it on once the owner agrees ([ADR-0025](../adr/0025-an-offline-agent-on-a-local-model.md)) |
| `name` | the owner's name (1–40 characters) | from the setup | `owner` | Settings › You › Name (`NameSheet` → `saveSetup`) |
| `her_voice` | a phone voice by name or region, or `natural` (when configured); without a value, the list | the best offered voice | `voiceName` (`cloud` for the natural voice) | Settings › Voice: Natural voice on top when `voice.*` is configured (with its price per minute), then the phone's voices offered by `Voices.offer` (same quality or better than the best free offline one, of the assistant's language), each with a stable name, how it sounds, whether it is on the phone, needs internet or downloads; touching one plays a sample (`chooseVoice`) |
| `voice_ceiling` | US$ a month for the natural voice; `none` = 0 (no ceiling) | US$ 5 | `voiceCeiling` | Settings › What I cost › My natural voice: this month's minutes (and dollars with `voice.perMinute`), chips US$ 2 / 5 / 10 / no cap; at the ceiling the phone's voice speaks until the next month |
| `messaging_apps` | "add WhatsApp", "remove Telegram" | none until the setup | `apps` | Settings › Messages › Apps I read |
| `warmth` | quiet / natural / close | `NATURAL` | `warmth` | Settings › Presence › How I talk to you |
| `voice` | on / off (`Device.onOff`) | off | `voice` | Settings › Presence › Speak aloud; setup's last step |
| `open_mic` | on / off; on needs `RECORD_AUDIO` | off | `openMic` | Settings › Presence › Open microphone |
| `palette` | dusk (Sunrise), lavender, coral (Light coral), night | `night` (`Palettes.default`; unknown keys too) | `palette` | Settings › Colour |
| `learning` | on / off (`Memory.pause`) | on | `memory.json` `learning` | Memory › Learn about you |
| `read_mail` | on / off | on | `mail` | Settings › Messages › Read your email |
| `floating_mark` | on / off; on needs the overlay permission | on | `bubble` | Settings › Presence › Always at hand |
| `floating_place` | left / right and/or top (0.18) / middle (0.45) / bottom (0.72) | `right:0.62` | `bubblePlace` | chips under Always at hand; dragging the mark (height clamped 0.08–0.9) |
| `undo_seconds` | first number, clamped 10–120 (`MIN_UNDO_SECONDS`) | 30 | `undoSeconds` | Settings › Messages › Time to undo: 10 / 30 / 60 / 120 s |
| `group_chats` | on / off | off | `groups` | Settings › Messages › Apps I read (and their groups) |
| `widget_add` / `widget_remove` | weather, alarm, agenda, messages, mail, reminders, battery, news (`Preferences.widget` matches any `Widget` by the first four letters of its name or label, so the greeting, panorama and the assistant's card too) | `Widgets.DEFAULT`: greeting, panorama, weather, alarm, agenda above the mark; messages, mail, reminders below | `widgets` | Home arranging ("arrange the widgets") |

Other saved fields: `owner` (absent until set up; `Guard.setUp = owner != null`), `contacts`
(name → groups), `address`, `appMode` (default false), `stopped`, `rules`, `counters`. Read once
by `Guard.load(filesDir)`; a missing or unreadable file means defaults.

## Behaviour

**By voice.** `Setting.named` normalizes (no spaces or underscores) and matches by prefix against
lexicon words in either language (`voice_ceiling` is tested before `voice`, `floating_place` before
`floating_mark`). `prefer` applies the change, then `Guard.save()` and `Ui.refresh()`. Side effects:
`warmth`, `language` and `name` rewrite the persona files (`AssistantService.writeIdentity`);
`language` and `her_voice` call `Language.onChange` (speech output and recognition retune);
`floating_mark` calls `Floating.update`; `offline` on calls `Offline.turnOn` (download) and off
`Offline.turnOff` (deletes the model); every save triggers `Guard.onSaved`, which rewrites the
persona files 1.5 s after the last change. `widget_add` refuses a widget already present or with no
room. In the app, `setWarmth` also retunes speech output and speaks a confirmation.

**Contact groups.** The contact is matched to an existing key of `Policy.groups` with
`Commands.normalize`, else used as said. ASK with strong approval: `Approvals.request` shows the
approval (in-app sheet via `MainActivity.prompt`, else a notification) and approving calls
`confirmOwner`. No answer in 60 s → refused. The tool description tells the agent to call it at
once, without asking for confirmation in words first.

**First-run setup.** `onCreate`: `Guard.load`; if not set up, `Ui.draft = Draft.fresh()` (empty
name, no apps, no groups, voice off). `App` shows `Setup` while `Ui.draft != null`.

| Step | Screen | What happens |
|---|---|---|
| 0 | `SetupName` | a spoken greeting; language chips (Spanish · English); the name typed or heard (`listenFor` + `Commands.nameFrom`, which strips lead-ins such as "my name is" in either language and capitalizes) |
| 1 | `SetupNeeds` | each need with a button (the table under Configuration): runtime permissions; fingerprint (optional); battery unrestricted; on Xiaomi devices autostart and the lock-screen permission; full-screen intent; optional: all files, usage access, overlay, notification access. *Continue* is always enabled |
| 2 | `SetupPeople` | apps read (`messagingApps`: installed `Inbox.DEFAULT_APPS` plus picked, plus `fakechat` in debug builds) and group chats; family and work from contacts with a phone number (`DraftPicker`); anyone else (never answered); Speak aloud; Start (first time) or Save |

`saveSetup(d, what)`: blank name → toast; on a secured phone `confirmOwner(what)` first (cancel →
nothing saved), on an unsecured phone it saves directly. It sets `Policy.owner`, `Inbox.apps`,
`Inbox.groups`, `Policy.groups = groupsOf(family, work)`, `Guard.voice`, `Guard.setUp = true`,
saves, rewrites the persona files, clears the draft; the first time it calls `introduce()`
([owner-chat-and-voice.md](owner-chat-and-voice.md)). After setup, `onCreate` requests missing
runtime permissions on each launch, and Home lists what is missing (`lacking()`) with a Fix action.

**Settings screen** (`Control`, `Place.CONTROL`), top to bottom: pause/resume (resume needs the
credential); You (name); Presence (Always at hand and its place, Speak aloud, Open microphone, How I
talk to you); Colour; People (family, work); Messages (apps I read, Connectors, read your email, time
to undo); Voice (natural voice, the phone's voices); Without internet; What I cost (`Usage.read`,
`/api/cost` from the first of the month: the month, today and each agent, in US$ when
`local.properties` gives the model's rate, else in tokens; the natural voice's usage and ceiling);
Permissions and privacy (What I can do → `Rules`, each change via `editRule` with the credential and
choices up to `Policy.ceiling`; Phone permissions → the setup's permissions step; what leaves the
phone). App mode is switched by command, by the menu or by `set_mode`, not on this screen.

**Private screens.** `Place.PRIVATE` = Interactions, Activity, Settings, Rules, Memory, Connectors;
while one of them, an approval or a connector sheet is shown, the window has `FLAG_SECURE`
(`Place.private`), keeping it out of screenshots, recordings and the recent-apps view. Debug builds
can lift it for automated captures (`allowCapture` extra).

## Invariants

- These tools exist only on `/mcp/owner`; every call goes through `Guard.gated` and is logged.
- CONTACT_GROUPS, CHANNEL and RULES are COMMITS_OWNER: never changed by the agent without the owner's credential.
- Saving the setup, a name change or a people change on a secured phone needs `confirmOwner`.
- An unknown setting or unreadable value changes nothing and saves nothing.
- The undo window is never below 10 s, also when loaded from disk.
- Nobody is family or work and no app is read until the setup is saved.

## Failure modes

- Missing microphone or overlay permission: `open_mic`/`floating_mark` on is refused with a message
  for the owner; in the app the permission is requested first.
- `policy.json` unreadable: defaults are used and the next save overwrites it; a rule that does not
  parse is dropped, not the file.
- Approval timeout: the contact-group or rule change is refused.
- `open_section` waits up to 2 s for the main thread; an unknown key answers `NOT opened`.

## Security

- Principal: the owner's agent only; the third-party endpoint has none of these tools.
- Values come from the model's arguments, which may follow text the agent read; widening changes
  are therefore gated by CHANNEL and need the credential.
- Settings changed on the Settings screen itself (the owner's touch) need no credential, except
  resuming, rules and setup saves.

## Open issues

- Adding and removing rules in the app is not built; only autonomy changes.
- On a phone without a screen lock the credential steps pass without confirmation
  ([policy-engine.md](policy-engine.md)).
- The model key ships inside the APK (`BuildConfig.MODEL_API_KEY`) and in `config.toml`; a settings
  screen that stores it in the Keystore is not built (noted in `app/build.gradle.kts`).
