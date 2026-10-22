# Spec: Owner chat and voice

| | |
|---|---|
| Status | Stable |
| Code | `app/.../OwnerChat.kt` (`OwnerChat`, `Promises`), `app/.../MainActivity.kt` (`send`, `act`, `listen`, `barge`, `speak`, `confirmOwner`, intents), `app/.../Commands.kt`, `app/.../Lexicon.kt` (`assets/lexicon.json`), `app/.../Voice.kt` (`Voices`, `asKsandra`, `VoiceProbe`), `app/.../CloudVoice.kt`, `app/.../Offline.kt`, `app/.../Cards.kt`, `app/.../Phrases.kt`, `app/.../App.kt` (`presence`, `Sections`, `MenuSheet`, `CardSheet`), `app/.../Screens.kt` (`Home`, `Presence`, `Conversation`, `Composer`), `app/.../MessageListener.kt` (`Turns.open`) |
| Tests | `app/src/test/.../CommandsTest.kt`, `PromisesTest.kt`, `OfflineTest.kt`, `OfflineTalkTest.kt`, `CloudVoiceTest.kt`, `VoiceTest.kt`, `CardsTest.kt`, `LanguageTest.kt`, `PhrasesTest.kt`; `scripts/e2e.sh` drives the app through debug extras but checks the notification channel, not the chat |
| Decisions | [ADR-0009](../adr/0009-gateway-only-through-kotlin-client.md), [ADR-0010](../adr/0010-zeroclaw-first-voice-first.md), [ADR-0012](../adr/0012-owner-history-24-messages.md), [ADR-0015](../adr/0015-interface-commands-in-code.md), [ADR-0006](../adr/0006-kill-switch-in-the-app.md), [ADR-0021](../adr/0021-process-state-with-funneled-writes.md), [ADR-0025](../adr/0025-an-offline-agent-on-a-local-model.md), [ADR-0027](../adr/0027-assistant-voice-from-a-cloud-speech-model.md), [ADR-0029](../adr/0029-ui-text-in-android-string-resources.md) |
| Last checked against code | 2026-10-22, commit `64e1087` |

## Purpose

How the owner talks to the assistant: one long conversation with ZeroClaw's `owner` agent over
`/ws/chat`, by voice or text, with spoken answers. It also holds what the screens need from Android
(the owner's credential, permissions, settings screens, contacts). It is not where permissions are
decided (`Policy.kt`) nor where third-party turns run (`Turns.thirdParty`,
[notification-channel.md](notification-channel.md)).

## Responsibilities

- Keep the owner's conversation for the process (`OwnerChat`: socket, session id, reconnect,
  history restored from ZeroClaw).
- Route the owner's input: interface commands handled in code (`Commands.of`, Spanish and English
  regardless of the current language), everything else to the agent.
- Voice in (`SpeechRecognizer`, open microphone, barge-in) and voice out (`TextToSpeech`, or the
  natural voice through `CloudVoice` with fallback).
- Without internet: the offline agent on the local engine, or direct app actions, or a plain
  statement of what is unavailable ([ADR-0025](../adr/0025-an-offline-agent-on-a-local-model.md)).
- Withhold answers that claim an action no tool performed (`Promises`).
- App-written owner turns (greeting, introduction), cards (`show`), the owner credential
  (`confirmOwner`), assistant mode and app mode (`setAppMode`), and sealed intents.
- Debug-only intent extras for end-to-end runs and automated UI captures.

## Interfaces

| What | Input → output | Caller |
|---|---|---|
| `send(text, byVoice, from)` | text → command (`act`) or `OwnerChat.send` plus a `Line(ME)` marked `VOICE`, `TYPED` or `TAP` (`from` = the tapped element); `Ui.thinking = true` | `Composer`, recognizer, card buttons, widgets, intents |
| `listen()` / `listenFor(onHeard)` | starts recognition; `listenFor` hands the result to a callback | `Presence` tap, `Composer` mic, setup name field, `listen` extra |
| `remark(text)` / `say(text)` / `hush()` | speak (flush) / stop speech | screens, `CatchUp.onBack`, reminders, news, mail notices |
| `ask(question)` | has the agent ask the owner a question in its own words, then listens once for the answer | Home panorama (a question the assistant wants to ask) |
| `confirmOwner(what, done)` | `BiometricPrompt` → `done(Boolean)` (see Behaviour) | `answer`, `approveIntent`, `setStopped(false)`, `editRule`, `saveSetup` |
| `setAppMode`, `setOpenMic`, `setWarmth`, `setVoice`, `chooseVoice`, `setLanguage`, `setOffline`, `setStopped`, … | the owner's toggles | Settings, `MenuSheet`, `act` |
| `MainActivity.prompt`, `MainActivity.note` (companion) | in-app approval prompt and in-chat notices; set in `onStart`, cleared in `onStop` | `AssistantService.askOwner`, notices |
| `MainActivity.sealed(intent)`, `bringToFront(context)` | adds the process's seal to the app's own intents; brings the activity forward | notifications, floating mark, tools |
| Sealed intent extras | `approve` (pending id), `listen`, `mail` (email id), `place` (screen id) | notifications, floating mark |
| `/ws/chat` | `Turns.open(context, agent, session)` through the Kotlin client | `OwnerChat.connect` (`owner`, and `offline` on demand) |

Frames handled in `OwnerChat.connect` (the open activity hears answers through `OwnerChat.onAnswer`
and failures through `onError`):

| Frame | Effect |
|---|---|
| `SESSION_START` | store the session id (`session`, or `session-offline`) in `AssistantService.prefs`; `socket` set and `Ui.ready = true` for `owner`; a resumed session with an empty chat restores the last 30 user and assistant lines from `sessions.messages` |
| `TOOL_CALL` | count the call; a tool in `PhoneMcp.OTHERS_WORDS` (including ZeroClaw's web tools) sets `Guard.heardOthers`; `Line(TOOL, Labels.tool(name, args))` |
| `DONE` | `Promises` check (below); `Ui.thinking = false`; `Line(ASSISTANT, fullResponse)`; `onAnswer` speaks it when answers are read aloud (`Guard.voice`) or the question was spoken |
| `ERROR` | `Ui.thinking = false`; `Line(NOTE, t(R.string.ownerchat_something_failed) + message)`; `onError` (listen again); on the offline agent, one retry in a new session |

## Configuration

| What | Build time (`local.properties` → `BuildConfig`) | In the app | By voice (`set_preference`) | Default |
|---|---|---|---|---|
| Answers read aloud | — | Settings › Presence › Speak aloud; setup's last step | `voice` | off |
| Open microphone | — | Settings › Presence › Open microphone (asks `RECORD_AUDIO`) | `open_mic` (CHANNEL when turned on) | off |
| Warmth | — | Settings › Presence › How I talk to you | `warmth` | natural |
| Language | — | setup step 0; Settings | `language` | the phone's, Spanish if unsupported |
| Phone voice | — | Settings › Voice (`chooseVoice`) | `her_voice` | best offered (`Voices.offer`) |
| Natural voice | `voice.url` → `VOICE_URL`, `voice.model` → `VOICE_MODEL`, `voice.apiKey` → `VOICE_API_KEY`, `voice.name` → `VOICE_NAME`, `voice.perMinute` → `VOICE_PER_MINUTE` (US$, for the cost shown); offered only when URL and key are set (`CloudVoice.configured`) | Settings › Voice › Natural voice | `her_voice natural` | not chosen |
| Natural voice ceiling | — | Settings › What I cost (US$ 2 / 5 / 10 / none) | `voice_ceiling` (CHANNEL when raised or removed) | US$ 5 (`Guard.DEFAULT_VOICE_CEILING`) |
| Answers without internet | `offline.model.name` / `url` / `sha256` / `bytes` → `OFFLINE_MODEL_*`; the option exists only when URL, SHA-256 and size are set (`Offline.configured`) | Settings › Without internet | `offline` | off |
| App mode | — | menu; commands | `set_mode` tool | assistant mode |
| Constants | `FOLLOW_UP_MS` = 20 s, `FIRST_SOUND_MS` = 2.5 s, `IDLE_MS` = 60 s, `KEEP_CHARS` = 160, `KEEP_FILES` = 300, `HISTORY` = 30, `LOCAL_TURN_MS` = 180 s, greeting gap 3 h | | | |

## State and data

| State | Where | Survives |
|---|---|---|
| `Ui.chat` (`Line(who, text, at, via, from)`, `Who` = ME, ASSISTANT, TOOL, NOTE) | memory, `object Ui` | rotation and activity recreation, not process death (the history is restored from ZeroClaw) |
| `Ui.ready`, `thinking`, `speaking`, `listening`, `micLevel`, `word`, `menu`, `place`, `section`, `pending`, `draft` | memory | rotation |
| Session ids | `AssistantService.prefs`, keys `session`, `session-offline` | restarts; ZeroClaw keeps the history (the owner agent sends the last 24 messages to the model, `max_history_messages = 24`) |
| `OwnerChat.lastWords`, `offlineTalk` (lines said offline, last 12 forwarded) | memory | no |
| `Guard.voice`, `openMic`, `warmth`, `appMode`, `Voices.chosen`, `Language.chosen`, `voiceCeiling` | `policy.json` ([settings-and-setup.md](settings-and-setup.md)) | restarts |
| Natural voice cache | `cacheDir/voice/<sha-256 of voice, style and text>`, sealed, the 300 most recent | yes (cache) |
| Natural voice monthly seconds | shared preferences `cloud-voice` (`month`, `seconds`) | yes |
| `Cards.all` (last 30 cards) | memory | no |
| Last greeting time | shared preferences `greeting`, key `at` | yes |

## Behaviour

**Chat.** `onCreate` starts `AssistantService`; `OwnerChat.start` connects once per process.
`Turns.open` waits for a pairing token (polling), opens `client.chat(agent, session)`; a 401 pairs
again. On failure or end of the `owner` socket: `socket = null`, `Ui.ready = false`,
`Ui.thinking = false`, 1 s pause, connect again. The activity's `onDestroy` only detaches its
callbacks, so a rotation mid-turn keeps the turn and its answer. `send` drops empty text or text while
`OwnerChat.socket == null`; it clears `Guard.undecided` and `Guard.heardOthers` (a new owner turn);
typed text moves to `Place.TALK` (Interactions), spoken text stays on the current screen. A spoken
or read-aloud turn warms the natural voice's connection while the agent works.

**Commands** (`Commands.of`). Input is normalized (lower case, no diacritics or punctuation, without
the assistant's name, attention words such as "hey" and "please" forms); patterns are anchored on the
whole input, so a question that merely mentions a keyword goes to the agent. Keywords come from the
`commands.*` entries of `assets/lexicon.json` (Spanish and English).

| Command | Example inputs | Action (`act`) |
|---|---|---|
| MENU | "show me the menu", "options" | `Ui.menu = true`; speaks the menu prompt if spoken or `Guard.voice` |
| CLOSE | "close the menu", "close" | `Ui.menu = false` |
| HOME / TALK / ACTIVITY / CONTROL / RULES / MEMORY / PHOTOS / FILES / NEWS | `[verb] [article] noun`: "home", "open the conversation", "activity", "settings", "what can you do", "what do you know about me", "photos", "files", "news" | go to that place |
| WIDGETS | "arrange the widgets" | Home, `Ui.arranging = true` |
| PAUSE | "pause", "stop everything" | `setStopped(true)`; a spoken confirmation if spoken |
| RESUME | "resume", "continue" | `setStopped(false)` (asks the credential) |
| APP_MODE / ASSISTANT_MODE | "app mode" / "assistant mode" | `setAppMode(true/false)` |

**Two modes.** `Guard.appMode` (default `false`). Assistant mode: Home shows the widget rows and the
mark (tap = listen, long press of 0.8 s = menu). App mode: the widget rows plus a board of icons for
every place. Switched by command, by the menu, or by the agent's `set_mode` tool; `setAppMode` saves
and says how to switch back.

**Navigation by the agent.** `open_section` opens a place, a Settings section or sheet, the email
provider picker or the permissions step ([settings-and-setup.md](settings-and-setup.md));
`show_on_home`/`remove_from_home` manage the assistant's own card among Home's widgets
([home-and-widgets.md](home-and-widgets.md)).

**Voice in.** `listen(byItself)`: ignored while listening; stops speech; uses an in-app
`SpeechRecognizer` when `RECORD_AUDIO` is granted and recognition is available, else the system
dialog (`ACTION_RECOGNIZE_SPEECH`). `hearIntent`: the language tag includes the phone's country when
the languages match (a bare tag returned nothing on some phones); `EXTRA_PREFER_OFFLINE` only without
internet (always preferring it returned nothing on phones without the offline model).
`Ui.micLevel = ((rmsdB + 2) / 12).coerceIn(0, 1)` while listening; 0 at end of speech. A busy or
failed recognizer is recreated once and listening retried after 300 ms; a second failure shows a
toast with the error code. `ERROR_NO_MATCH` or `ERROR_SPEECH_TIMEOUT` shows a hint (manual listening)
or listens again (open microphone).

**Open microphone** (`Guard.openMic`, default off). `again()` (after 400 ms) listens by itself when
the open microphone is on (or an answer is awaited after `ask`), nothing is speaking, the activity is
RESUMED, not stopped, `Ui.ready`, not thinking, no setup draft. Speech heard this way is taken only
if `Commands.addressed`: it starts with the assistant's name or an attention word, is a command, or
comes within `FOLLOW_UP_MS` = 20 s of the last exchange. Turning it on requests `RECORD_AUDIO` first
and speaks a one-time explanation.

**Voice out.** `TextToSpeech` in `Language.locale`; text said before the engine is ready waits in
one slot. The voice (`asKsandra`): the owner's choice among `Voices.available`, else the best offered
voice (quality, then locale proximity, then offline availability); pitch 1.0; rate by warmth (QUIET
1.0, NATURAL 0.97, CLOSE 0.95). Each newly installed voice is probed once, muted (`VoiceProbe`), to
classify it. `onRangeStart` increments `Ui.word` (the mark pulses per word).

**Natural voice** ([ADR-0027](../adr/0027-assistant-voice-from-a-cloud-speech-model.md)). When
`Voices.chosen == CloudVoice.NAME`, `speak` and the reminder call go first to `CloudVoice.say`: a
realtime speech model over WebSocket (`voice.url`, `voice.model`, `voice.apiKey`, `voice.name` from
`local.properties`), an audio-only session (16-bit PCM, 24 kHz) and one response per line whose
instructions (`CloudVoice.style`) say to read the text verbatim and never answer it, with a fixed
voice character, a neutral accent for Spanish and a pace by warmth. The model gets no tools. The
connection is kept open across lines and closed after 60 s idle. Audio plays as it arrives
(`AudioTrack`, `USAGE_ASSISTANT`); each detected syllable onset (`CloudVoice.onsets`, 50 ms windows)
increments `Ui.word`; the end waits for the last frame. The phone's voice speaks instead when the
natural voice is not chosen, not configured, there is no internet (`Offline.online`), this month's
ceiling is reached (`Guard.voiceCeiling`, default US$ 5, 0 = none), or no audio arrives within
`FIRST_SOUND_MS` = 2.5 s; cut mid-line, the line just ends. Lines up to 160 characters are cached and
replayed without the network; generated seconds are counted per month (cached replays are free).

**Barge-in.** Only with headphones (on the loudspeaker the recognizer hears the assistant's own
output); tapping the mark stops speech otherwise. When speech starts, `barge()` opens the recognizer
with partial results (RESUMED, microphone granted, not stopped, not already listening).
`Commands.interrupts(heard, saying)` is true when the input starts with the assistant's name or an
attention word, its first word is a stop word (`commands.stop`: "stop", "wait", "enough", "quiet"
and Spanish equivalents), or it has at least 2 words with at least 60 % absent from the line being
spoken. Then `interrupted()`: speech stops and the final result becomes the next request. An echo
restarts barge-in listening (after 300 ms on error); speech done cancels it.

**Without internet** ([ADR-0025](../adr/0025-an-offline-agent-on-a-local-model.md)). When offline
answers are on and the model is ready (`Offline.now()`), the owner's words go to the `offline` agent
on the local engine (`Offline.ensure` starts it; it reuses a prompt cache saved to disk, so only the
changed suffix of the prompt is processed again). Explicit requests (a reminder, an alarm, the next
alarm, the day's agenda, the reminders list, opening an app, the weather) are done by the app itself
(`Offline.direct` through `PhoneMcp.owner`). Each turn tells the small model never to state a time,
date or phone fact no tool gave it. A failed turn or a tool call emitted as text is retried once in a
new session; a turn over 3 minutes is reported, not retried. A new session starts for each outage.
What was said offline (`offlineTalk`) is appended to the owner's next online message after
`OFFLINE_MARK` (stripped from displayed history by `OwnerChat.said`), so the online agent owns it.
With offline answers off, the answer is `Offline.limited` (what is unavailable and how to enable
it). App-written notes are not sent while offline. The owner's words are shown before any answer.

**Promises** (`Promises`). When an answer to the owner's message claims an action was or will be
done (`promises.claim` in the lexicon: done, set, sent, opened, "here it is", …) and no tool ran in
that turn, the answer is neither shown nor spoken: the agent gets `Promises.note` (nothing was done;
do it or answer again) and its next answer is the one the owner gets. Once per owner message.

**`send_message` and other messaging tools** are specified in [phone-abilities.md](phone-abilities.md).
A tool result starting with `NOT ` never produces a "Done" notice or the mark's DONE gesture.

**Presence** (`presence()` in `App.kt`, first match): `Ui.splashing` → RESTING; debug `Ui.pose`;
stopped → PAUSED; listening → LISTENING; speaking → SPEAKING; thinking → PROCESSING; `!Ui.ready` →
OFFLINE; else RESTING. Labels come from string resources (Available, Listening, Thinking, Speaking,
Paused, Connecting). The setup uses `setupPresence()` (speaking, listening, resting).

**App-written owner turns** (`prompt(text)`: sent on the open socket prefixed by `Phrases.NOTE`
(`Phrases.fromApp`), not shown, answer shown and spoken). Restored history skips user messages that
start with `Phrases.NOTE`, after removing ZeroClaw's `[CURRENT DATE & TIME: …]` prefix.

| Turn | When | Text |
|---|---|---|
| Greeting | `onStart`, set up, no catch-up coming, more than 3 h since the last greeting (`greeting.at`); waits up to 5 s for `Ui.ready` | connected and not stopped: `Phrases.welcome` (owner-authored context only: followed topics, the time of today's next event (never its title), reminders left today); else a local `Phrases.greeting` by warmth |
| Introduction | first `saveSetup` | local `introduction(...)` line; then, if `Policy.address == null`, a prompt asking how to address the owner, saved with `set_address` |
| Untold catch-up | `onStart` with `CatchUp.last` untold | spoken locally ([away-and-catch-up.md](away-and-catch-up.md)) |

**Email from a notification.** The sealed `mail=<id>` extra calls `openMail(mail)`: the email in its
own app (the notification's content intent, else the app's launcher, else a dialog); an account
connected only to the assistant is shown as a card. Nothing is read aloud or given to an agent.

**Cards.** `show` ([phone-mcp.md](phone-mcp.md)): `Cards.show` opens `CardSheet` over any place (not
over the setup or an approval); body in small Markdown; a `[title](https://…)` line is an option that
opens the link (http(s) only); up to 3 buttons whose touch is sent as the owner's words (`Via.TAP`).
In the background a floating notice appears and the card waits for the app. The last 30 cards stay in
`Cards.all`, so their line in Interactions reopens them.

**Owner credential** (`confirmOwner`). `BiometricPrompt` with `BIOMETRIC_STRONG | DEVICE_CREDENTIAL`
when a strong biometric is enrolled, `DEVICE_CREDENTIAL` alone otherwise (the combination fails
without an enrolled biometric); success → `true`; error or cancel → `false` with a toast. Without a secure screen lock it returns `false` at once (fail closed), with a toast saying a screen lock is needed, and
opens the security settings.

**Lifecycle.** `onStart`: sets `prompt` (approval sheet, gesture EXPECT, spoken line when answers are
read aloud), `note`, `MailNotice.onImportant`, `PhoneReminders.onDue`, `PhoneNews.onBriefing`,
`CatchUp.onBack` to speak; `Floating.shown(true)`. `onStop` clears them and records `Floating.seen`.
`onPause` cancels the recognizer (the microphone is never open in the background). `onDestroy`
shuts down TTS and the natural voice and destroys the recognizer; the socket is process-scoped.

**Private screens.** Interactions, Activity, Settings, Rules, Memory, Connectors, an open approval
and a connector sheet set `FLAG_SECURE` on the window (`Place.private`).

**Debug-only extras** (`debugIntent`, ignored unless `BuildConfig.DEBUG`), used by `scripts/e2e.sh`,
and `scripts/screenshots.sh`: `say` (+`spoken`), `incoming`+`from` (a third-party
turn), `apps`, `groups`, `stop`, `clean` (counters), `clearActivity`, `fresh` (new session), `owner`
(completes setup), `contacts` (e.g. `Laura:work,Ana:family`), `language`, `voice`, `news`/`newsAgain`/`watch`
(run a topic check), `call` (a high reminder in 3 s), and capture controls (`palette`, `appMode`,
`arrange`, `voiceName`, `section`, `card`/`cardBody`/`closeCard`, `narrate`, `narrateFile`,
`ringFull`, `pose`, `keepMark`, `face`, `faceVideo`, `quietRecorded`, `allowCapture`, `hideCosts`).

## Invariants

- A matched command is never sent to the model.
- Resuming, approving a strong request, editing a rule and saving the setup on a secured phone
  require `confirmOwner`; cancel or error → `false`.
- The microphone is closed whenever the activity is not RESUMED.
- Approve, listen, mail and place extras are honoured only from intents carrying the process's seal.
- Debug extras do nothing in release builds.
- The owner's chat uses the `owner` agent (or the `offline` agent offline) and goes through the
  Kotlin client.
- An answer claiming an action with no tool run is not shown as is.

## Failure modes

- Gateway down or restarting: reconnect every 1 s; presence OFFLINE; `send` drops text.
- No recognizer or no microphone permission: the system dialog; none at all → a toast.
- TTS initialization fails: nothing is spoken by the phone's voice; text still shows.
- Natural voice fails before any audio: the phone's voice says the line; mid-line: the line ends.
- `ERROR` frame: a NOTE line; thinking cleared.
- Monthly model budget reached (`Budget.reached`): no cloud turn; the basics handled in code answer
  (`Offline.direct`), anything else gets a line saying the budget is reached and can be raised in Settings.
- Cloud turn silent (no frame, approval waits excluded): after 25 s (`SLOW_MS`) a NOTE says it is still working;
  after 90 s (`SILENT_MS`) a NOTE asks the owner to try again, thinking is cleared, `onError` runs and the socket is
  closed so the next turn reconnects. Checked once a second by the `owner-chat-watch` thread.
- Offline engine fails or is too slow: a note or a spoken line saying it could not answer.

## Security

- Principal: the owner only; the `owner` agent holds every owner tool, gated per call by `Guard`.
- `MainActivity` is exported (launcher); only sealed intents trigger approve, listen, mail or place,
  and the approval prompt reads its text from the pending request, never from the intent.
- App-written prompts (greeting, introduction, `ask`) enter the owner agent's turn with all its tools;
  they carry only owner-authored data.
- The natural voice sends the text of each spoken line to the configured speech provider; it receives
  no tools.
- Chat lines and tool results are logged with content only in debug builds.

## Open issues

