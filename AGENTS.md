# AGENTS.md

Guidance for contributors and coding agents working in this repository.

## What this is

An on-device personal assistant for Android ("Ksandra"). The official ZeroClaw v0.8.5 daemon runs inside the app as
the agent runtime; the app is the owner's interface and the only component that acts on the phone, through tools
served on a loopback MCP server and checked by a policy engine in code.

Read before changing tools or policy: the [access model](docs/specs/access-model.md) (who may do what, with how much
autonomy) and [security](docs/architecture/security.md). Documentation map: [docs/README.md](docs/README.md);
architecture in [docs/architecture/](docs/architecture/README.md), one spec per component in
[docs/specs/](docs/specs/README.md), decisions in [docs/adr/](docs/adr/README.md). Roadmap and open work are tracked in
GitHub issues, by phase in milestones (Phase A: robustness and trust, current; Phase B: everyday value). Each PR
references its issue (`Closes #N`) and updates the specs it changes.

## Rules

- Commits follow Conventional Commits (`feat:`, `fix:`, `docs:`, `refactor:`, `test:`, `ci:`, `chore:`), plain
  messages, **no attribution trailers of any kind**.
- No AI vendor, model or assistant product names anywhere: code, comments, docs, commits, PRs. The model provider is
  configuration in `local.properties`.
- No real personal names. The owner in examples and tests is **Milton**; fake contacts are Laura (work), Ana (family)
  and Pat (unknown).
- Code, comments and docs in English.
- User-facing text goes to `app/src/main/res/values/strings.xml` (English) and `values-es/strings.xml` (Spanish); input
  keywords go to `app/src/main/assets/lexicon.json`, read by `Lexicon.kt`
  ([ADR-0029](docs/adr/0029-ui-text-in-android-string-resources.md)).
- No secrets in git: keys only in `local.properties` (ignored), and they reach debug builds only; release builds
  contain no keys, the owner enters theirs in Connectors › Services (`Keys.kt`, ADR-0030).
- Never send anything to real people or real apps, and never place calls. Test outgoing paths with the `fakechat`
  module on the emulator.
- Permissions are enforced in code (`Policy.kt`), never in prompts. Every new tool declares its `Capability`; a
  third-party turn must never reach an owner tool ([ADR-0003](docs/adr/0003-permissions-enforced-in-code.md)).
- Work on a branch and open a PR to `main`; keep `main` green (CI runs unit tests and a debug build).
- Documentation follows [ADR-0000](docs/adr/0000-documentation-standards.md): a PR that changes how a component works
  updates its spec in `docs/specs/`; a change of direction gets an ADR in `docs/adr/` (MADR).

## Layout

`app/...` is `app/src/main/java/org/rustlanglatam/zeroclaw/android/`.

| Path | What |
|---|---|
| `app/.../AssistantService.kt` | foreground service: writes ZeroClaw's config, runs the daemon, pairs |
| `app/.../PhoneMcp.kt` | MCP server on `127.0.0.1:42618`: `/mcp/owner` and `/mcp/third_party` |
| `app/.../Policy.kt` | policy engine (pure Kotlin, unit-tested): capabilities, ceilings, rules |
| `app/.../Approvals.kt` | ask flow (notification + credential), undo window, activity log |
| `app/.../MessageListener.kt`, `Inbox.kt` | notification channel: incoming messages, replies, digest |
| `app/.../MainActivity.kt` | the owner's chat (text and voice) and the Android services the screens need |
| `app/.../OwnerChat.kt` | the owner's `/ws/chat` socket, scoped to the process; `Promises` withholds unbacked claims |
| `app/.../Loopback.kt` | trust rules for loopback traffic: request limits, per-process MCP keys, gateway attribution (pure Kotlin) |
| `app/.../Act.kt` | an action under the policy: log line, localized title and detail |
| `app/.../Identity.kt` | a sender resolved against the owner's contacts, never by display name (ADR-0019) |
| `app/.../Language.kt` | the assistant's language, `t()` and `tn()` for string resources |
| `app/.../Routines.kt` | owner routines as ZeroClaw cron jobs; results from `/api/events` |
| `app/.../Notes.kt` | notes pinned to Home (`show_on_home`), stored in ZeroClaw memory with a sealed copy |
| `app/.../App.kt`, `Screens.kt` | the interface (Compose): setup, Home, Interactions, Activity, Settings, approvals |
| `app/.../Theme.kt`, `Labels.kt` | design tokens and the Presence Mark; the policy in plain words |
| `app/.../Lexicon.kt` | input keywords per language, loaded from `assets/lexicon.json` |
| `app/.../Share.kt`, `Shared.kt` | "share with Ksandra": text read on the phone (OCR, PDF), dates and amounts found in code, a card to confirm |
| `app/.../Assist.kt` | the system assist gesture (`ACTION_ASSIST`) opens the app listening |
| `app/.../Commands.kt`, `Floating.kt` | interface commands handled in code; the floating mark over other apps |
| `app/.../Cards.kt` | cards: small Markdown and buttons that answer the agent |
| `app/.../Videos.kt` | video options for cards from the YouTube search API; without a key, a search link |
| `app/.../Apps.kt`, `PhoneApps.kt` | the owner's apps: kinds, most used, open, play a search, media keys |
| `app/.../Memory.kt`, `PhoneMemory.kt` | owner memory (told, inferred, stored by the agent) in ZeroClaw through the client; a sealed copy on the phone |
| `app/.../Mailbox.kt`, `MailNotice.kt`, `PhoneMail.kt` | email from notifications: importance, notices, drafts in the owner's email app |
| `app/.../EmailAccounts.kt`, `PhoneEmail.kt` | connected email accounts (Gmail, Yahoo, iCloud, IMAP/SMTP) read through ZeroClaw's email channel as observers ([ADR-0026](docs/adr/0026-google-email-through-zeroclaws-email-channel.md)) |
| `app/.../Connectors.kt` | Connectors screen: services (model, search, videos, voice keys) and connected accounts by kind, each with its form |
| `app/.../Keys.kt` | service settings and keys entered by the owner, encrypted on the phone; the build's values as fallback (ADR-0030) |
| `app/.../Agenda.kt` | clashes and free time before any commitment |
| `app/.../Photos.kt`, `PhoneGallery.kt` | photo search: dates from MediaStore, on-device labels and faces |
| `app/.../Files.kt`, `PhoneFiles.kt` | file search: Downloads, Documents, WhatsApp, Telegram, plus emails and messages |
| `app/.../Health.kt` | permissions the assistant needs in the background, checked every minute; a notice when one is lost |
| `app/.../Absence.kt`, `CatchUp.kt` | away and back: the cause (phone off, app closed, offline) and a prioritized summary |
| `app/.../Bond.kt` | the mark's ring segments (profile coverage) and glow (load) |
| `app/.../Preferences.kt` | settings by voice (`set_preference`); contact groups by voice (`set_contact_group`, credential) |
| `app/.../Device.kt`, `PhoneDevice.kt` | device controls: brightness, volume, flashlight, do-not-disturb, rotation; system panels |
| `app/.../Busy.kt` | busy, meeting or driving mode: one fixed reply per family or work contact, do-not-disturb |
| `app/.../Lists.kt` | lists by voice (shopping, to-do), kept in ZeroClaw's memory; the Home lists card |
| `app/.../Scam.kt` | possible-scam warnings decided in code (link or phone, plus codes, or money with urgency) |
| `app/.../Unanswered.kt` | family and work messages still unanswered and unopened, important emails not opened; a daily quiet notice |
| `app/.../Budget.kt` | the owner's monthly model budget: no cloud turn past it, notices at 80 % and 100 % |
| `app/.../Usage.kt` | model cost from ZeroClaw's `/api/cost`, by voice (`usage`) and in Settings |
| `app/.../Offline.kt` | offline answers: opt-in, model download, `llama-server` engine, basics handled in code ([ADR-0025](docs/adr/0025-an-offline-agent-on-a-local-model.md)) |
| `app/.../Voice.kt` | platform text-to-speech voices and the owner's choice |
| `app/.../CloudVoice.kt` | natural voice: a realtime speech model reads the agent's text; platform voice as fallback; cached lines, cost and ceiling ([ADR-0027](docs/adr/0027-assistant-voice-from-a-cloud-speech-model.md)) |
| `app/.../Vault.kt` | the app's files sealed with a Keystore key ([ADR-0023](docs/adr/0023-app-files-sealed-to-the-phone.md)) |
| `app/.../Empathy.kt` | learned style, important dates, weekly reflection |
| `app/.../Panorama.kt` | Home's rotating line: state, news, messages, questions |
| `app/.../Chime.kt` | the assistant's notification sound |
| `app/.../Phrases.kt` | the assistant's remarks, by warmth |
| `app/.../Widgets.kt`, `HomeWidgets.kt`, `PhoneWidgets.kt` | Home widgets: two rows of four, drag to arrange; data sources (weather from Open-Meteo) |
| `app/.../News.kt`, `PhoneNews.kt` | followed topics, headlines (news RSS), briefings |
| `app/.../Reminders.kt`, `PhoneReminders.kt` | reminders by level; high level rings full screen (`CallActivity`, not telephony) |
| `app/src/main/assets/lexicon.json` | input keywords (ADR-0029) |
| `fakechat/` | debug-only fake messaging and email app: posts notifications, records replies and drafts |
| `scripts/e2e.sh` | end-to-end checks of the notification channel on the emulator, with `fakechat` |
| `scripts/screenshots.sh` | regenerates `docs/images/` from the emulator |
| `scripts/chime.py`, `scripts/splash.py` | generate the chime sound (`res/raw`) and the splash mark drawable |
| `.github/workflows/ci.yml` | CI: JDK 21, SDK packages, `patchelf`, unit tests, debug build |
| `docs/product/` | vision and scope, features with status |
| `docs/architecture/` | arc42 overview, C4 diagrams, runtime, storage, security, ZeroClaw facts |
| `docs/specs/` | one spec per component; the access model |
| `docs/adr/` | architecture decision records (MADR) |

## Build, run, test

Requirements: JDK 21, the Android SDK (platform 35, build-tools 35.0.0), NDK 29.0.14206865 (strips the local model's
engine) and `patchelf` in `/usr/bin`, `/usr/local/bin` or `~/.local/bin` (the build looks there, not on the `PATH`).
The first build downloads the ZeroClaw and llama.cpp Android binaries and checks their SHA-256 (`app/build.gradle.kts`).
`local.properties` (ignored by git); the first four lines are enough to run, the rest are optional; each key's
`BuildConfig` field and reader are in [docs/architecture/configuration.md](docs/architecture/configuration.md):

```properties
sdk.dir=/path/to/Android/Sdk
zeroclaw.provider=...
zeroclaw.model=...
zeroclaw.apiKey=...
# optional: the model's price, US$ per million tokens, so ZeroClaw counts the cost in US$
zeroclaw.inputPerMTok=...
zeroclaw.outputPerMTok=...
# optional: ZeroClaw's web search: brave, tavily, jina or bocha with an API key, searxng with its instance URL
# (default duckduckgo, no key, often blocked)
zeroclaw.search=...
zeroclaw.searchKey=...
# optional: YouTube search API key, for video options in cards
youtube.apiKey=...
# optional: the local model for offline answers: display name, GGUF download URL, SHA-256, size in bytes
offline.model.name=...
offline.model.url=...
offline.model.sha256=...
offline.model.bytes=...
# optional: natural voice: realtime speech model WebSocket URL, model, key, voice, US$ per minute
voice.url=...
voice.model=...
voice.apiKey=...
voice.name=...
voice.perMinute=...
# optional: a release signed with your own key (./gradlew assembleRelease: shrunk, optimized; unsigned without it)
release.storeFile=/path/to/release.jks
release.storePassword=...
release.keyAlias=...
release.keyPassword=...
```

```sh
./gradlew testDebugUnitTest          # unit tests (JVM; also what CI runs, with a debug build)
./gradlew assembleDebug              # downloads and verifies the ZeroClaw v0.8.5 and llama.cpp b11381 binaries on first build
emulator -avd zc34 -gpu host -no-audio -no-window &   # API 34 x86_64; runs the arm64 binaries through translation
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n org.rustlanglatam.zeroclaw.android/.MainActivity
scripts/e2e.sh                       # end-to-end checks, emulator only, with fakechat installed and a model key set
scripts/screenshots.sh en            # README images (emulator only, ANDROID_SERIAL=emulator-...)
```

Emulator notes:

- The local image `zc34` (not in the repository) holds fake contacts, calendar events and the device PIN `1234`.
- Unlock it after boot, before anything else (`adb shell input keyevent 82; adb shell input text 1234; adb shell input
  keyevent 66`): a locked user hides the app's activities ("Activity class does not exist").
- `-gpu host` (or `hw.gpu.enabled=yes` in the AVD's `config.ini`): without it rendering is software-only and
  animations stutter. `-no-audio`: voice, chime and reminder calls are not needed for the checks.
- On a laptop with two GPUs, stay on the integrated one and disable Vulkan (`-feature -Vulkan`): discrete-GPU offload
  lost its EGL context, and OpenGL on one GPU with Vulkan on the other crashed the emulator.
- The emulator needs `/dev/kvm`; on Linux, access can be dropped while the desktop session is inactive.
- Stop it when done (`adb emu kill`); it uses several GB of RAM.

## ZeroClaw facts that shaped the code

Listed in [docs/architecture/zeroclaw.md](docs/architecture/zeroclaw.md), each with the ADR it led to. The two that
matter most when coding: everything the app asks of ZeroClaw goes through the Kotlin client
`io.github.rustlanglatam:zeroclaw-api-client` ([ADR-0009](docs/adr/0009-gateway-only-through-kotlin-client.md)), and
permissions are enforced in code, never in prompts ([ADR-0003](docs/adr/0003-permissions-enforced-in-code.md)).
