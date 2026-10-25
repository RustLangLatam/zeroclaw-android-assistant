# Configuration and start-up

Every setting the build and the app read, where it comes from and what reads it, checked against
`app/build.gradle.kts`, `AssistantService.kt`, `CloudVoice.kt`, `Offline.kt`, `PhoneMcp.kt` and
`.github/workflows/ci.yml`. The shortest path from a clone to a running app is at the end
([Minimal start-up](#minimal-start-up)).

## Build requirements

| Requirement | Value | Why |
|---|---|---|
| JDK | 21 | `actions/setup-java` in CI; Gradle and AGP 9.4.1 |
| Android SDK | platform 35, build-tools 35.0.0 | `compileSdk = 35`, `targetSdk = 35` |
| NDK | 29.0.14206865 | strips the native libraries (llama.cpp ships with debug symbols: 245 MB, 26 MB stripped) |
| `patchelf` | found at `~/.local/bin/patchelf`, `/usr/bin/patchelf` or `/usr/local/bin/patchelf` (not the `PATH`) | sets `RUNPATH $ORIGIN` on llama.cpp's libraries (`fetchLlama`); the build fails without it |
| Network on the first build | GitHub releases | downloads the two native archives below |
| Kotlin | 2.2.10 (`org.jetbrains.kotlin.plugin.compose`) | the version AGP 9.4.1 builds with |

The SDK location comes from `sdk.dir` in `local.properties` or the `ANDROID_HOME` environment
variable (CI uses the latter and has no `local.properties`).

### Downloaded binaries

Fetched before `preBuild` (`fetchZeroclaw`, `fetchLlama`), cached in `.zeroclaw/` and extracted into
`app/src/main/jniLibs/arm64-v8a/`; both folders are ignored by git. The build fails on a checksum
mismatch.

| Archive | Version | SHA-256 | Extracted as |
|---|---|---|---|
| `zeroclaw-aarch64-linux-android.tar.gz` (zeroclaw-labs/zeroclaw releases) | v0.8.5 | `5664f9c9161cb5946a5f852c72620d2f87d6fd9cc27b930dea1f8effaf91b04b` | `libzeroclaw.so` (the daemon, run from the app's native library directory) |
| `llama-b11381-bin-android-arm64.tar.gz` (ggml-org/llama.cpp releases) | b11381 | `457554cd1901b88c734d0be75c90fff8d56faab853888cac26435448cb1f09d0` | `libllama-server.so` (the `llama-server` executable) and its libraries `libllama*.so`, `libggml*.so`, `libmtmd.so`, each with `RUNPATH $ORIGIN` |

The APK targets `arm64-v8a` only (`abiFilters`); `minSdk = 30` (Android 11), because the
notification channel uses `MessagingStyle.Message.getMessagesFromBundleArray`. Native libraries are
packaged uncompressed and extracted on install (`useLegacyPackaging`, `extractNativeLibs`) so the
daemon can be executed.

## `local.properties`

Read by `app/build.gradle.kts` at build time; never committed (`.gitignore`). Every value becomes a
`String` field of `BuildConfig` (empty string when absent, unless a default is listed). These are **developer
defaults**: the owner's own values, entered in Connectors › Services and kept encrypted on the phone (`Keys.kt`),
take precedence ([ADR-0030](../adr/0030-service-keys-entered-by-the-owner.md)). **Release builds compile no keys**:
the `release` build type empties `MODEL_API_KEY`, `SEARCH_KEY`, `YOUTUBE_KEY` and `VOICE_API_KEY`; debug builds
contain every key in the file, so do not distribute a debug build made with your keys. CI builds without the file
and the build accepts every key empty.

| Key | `BuildConfig` field | Needed for | Default | Read by |
|---|---|---|---|---|
| `sdk.dir` | — | the build, unless `ANDROID_HOME` is set | — | Gradle |
| `zeroclaw.provider` | `MODEL_PROVIDER` | **running at all**: the provider type for `[providers.models.<provider>.main]` and `.warm` and every agent's `model_provider` | — | `AssistantService.initialConfig` |
| `zeroclaw.model` | `MODEL` | **running at all**: the `model` of both profiles | — | `AssistantService.initialConfig` |
| `zeroclaw.apiKey` | `MODEL_API_KEY` | **running at all** with any provider that needs a key: `api_key` of both profiles | — | `AssistantService.initialConfig` |
| — (Services only) | `MODEL_URL` | the `custom` provider (any compatible gateway): `uri` of both profiles, the full endpoint URL; optional for other providers | the family's default endpoint | `AssistantService.initialConfig` |
| `zeroclaw.inputPerMTok`, `zeroclaw.outputPerMTok` | `MODEL_INPUT_PER_MTOK`, `MODEL_OUTPUT_PER_MTOK` | cost in US$ ("What I cost"); both must parse as numbers or no `[cost.rates…]` section is written and ZeroClaw counts tokens only | — | `AssistantService.rates` |
| `zeroclaw.vision.provider`, `zeroclaw.vision.model`, `zeroclaw.vision.apiKey` | `VISION_PROVIDER`, `VISION_MODEL`, `VISION_API_KEY` | photo explanations when the main model cannot see pictures: `[providers.models.<provider>.vision]` and `[multimodal] vision_model_provider`, only when all three are set | — | `AssistantService.vision` |
| `zeroclaw.search` | `SEARCH_PROVIDER` | ZeroClaw's `web_search_tool` | `duckduckgo` (no key; often blocks requests it takes for a robot) | `AssistantService.initialConfig` |
| `zeroclaw.searchKey` | `SEARCH_KEY` | the search provider: written as `<provider>_api_key` for `brave`, `tavily`, `jina` or `bocha`; as `searxng_instance_url` (the instance URL) for `searxng` | — | `AssistantService.searchKey` |
| `youtube.apiKey` | `YOUTUBE_KEY` | `find_videos`: video options in cards; without it the tool answers with a YouTube search link | — | `PhoneMcp` (`find_videos`), `Videos.kt` |
| `voice.url`, `voice.apiKey` | `VOICE_URL`, `VOICE_API_KEY` | the natural voice ([ADR-0027](../adr/0027-assistant-voice-from-a-cloud-speech-model.md)); both non-blank make `CloudVoice.configured` true and the option appears in Settings › Voice | — | `CloudVoice` |
| `voice.model` | `VOICE_MODEL` | appended to the WebSocket URL as `model=` | — | `CloudVoice.connection` |
| `voice.name` | `VOICE_NAME` | the voice requested in the session | — | `CloudVoice.session` |
| `voice.perMinute` | `VOICE_PER_MINUTE` | US$ per minute of audio, for "What I cost" and the monthly ceiling; without it minutes are counted but not priced | — | `CloudVoice.perMinute` |
| `offline.model.url`, `offline.model.sha256`, `offline.model.bytes` | `OFFLINE_MODEL_URL`, `OFFLINE_MODEL_SHA256`, `OFFLINE_MODEL_BYTES` | offline answers ([ADR-0025](../adr/0025-an-offline-agent-on-a-local-model.md)): a GGUF file's download URL, SHA-256 and size; all three make `Offline.configured` true, otherwise the Settings option says it is not configured | — | `Offline` |
| `offline.model.name` | `OFFLINE_MODEL_NAME` | the model's display name in Settings | — | `Offline.MODEL` |
| `release.storeFile`, `release.storePassword`, `release.keyAlias`, `release.keyPassword` | — | `./gradlew assembleRelease` signed with your own key (shrunk and optimized with R8); without `release.storeFile` the release build is unsigned | — | `android.signingConfigs.release` |

Sample (the first four lines are enough to run):

```properties
sdk.dir=/path/to/Android/Sdk
zeroclaw.provider=...
zeroclaw.model=...
zeroclaw.apiKey=...
# optional: the model's price, US$ per million tokens, so ZeroClaw counts the cost in US$
zeroclaw.inputPerMTok=...
zeroclaw.outputPerMTok=...
# optional: web search: brave, tavily, jina or bocha with an API key; searxng with its instance URL
zeroclaw.search=...
zeroclaw.searchKey=...
# optional: YouTube Data API key, for video options in cards
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
# optional: a release signed with your own key
release.storeFile=/path/to/release.jks
release.storePassword=...
release.keyAlias=...
release.keyPassword=...
```

## What the app writes at runtime

The app configures ZeroClaw itself; nothing is edited on the phone by hand.

| Written | Where | By | Details |
|---|---|---|---|
| `config.toml` | `files/zeroclaw/config.toml` | `AssistantService.run` → `initialConfig()` | the model profiles `main` and `warm` with the key, the local model profile, web search and fetch, the three MCP servers with per-process bearer keys, risk and runtime profiles for the `owner`, `third_party`, `scribe` and `offline` agents, one email channel per connected account. Rewritten only when missing, when its version mark (`# ksandra-config 20`, kept in the app's prefs) differs, or when it lacks an agent, a tool name or the current email channels; the saved pairing is dropped then. The MCP keys are injected at every start. Section by section: [assistant-service.md](../specs/assistant-service.md#configtoml-initialconfig) |
| Persona files | `files/zeroclaw/agents/<agent>/workspace/*.md` | `AssistantService.writeIdentity` | `IDENTITY.md`, `SOUL.md`, `USER.md`, `AGENTS.md`, `TOOLS.md` per agent; rewritten at every start and after memory, style, warmth, address or policy changes ([model-and-code.md](model-and-code.md#persona-files)) |
| Daemon command line | — | `AssistantService.run` | `libzeroclaw.so daemon --config-dir files/zeroclaw --host 127.0.0.1 --port 42617`, with `HOME=files` and `TMPDIR=cache`; output to `cache/zeroclaw.log` |
| Local engine command line | — | `Offline.ensure` | `libllama-server.so -m <model> --jinja -c 8192 -np 1 --host 127.0.0.1 --port 42620 --alias local -t 2…4 --cache-reuse 256 --slot-save-path cache/slots/`, output to `cache/llama.log`; only with offline answers on and the model downloaded |
| Gateway token and session ids | `shared_prefs/gateway.xml` (`EncryptedSharedPreferences`) | `AssistantService.pair`, `OwnerChat` | the pairing token, the owner's and the offline agent's session ids, the config version mark |

Ports: gateway `127.0.0.1:42617`, the app's MCP server `127.0.0.1:42618` (`/mcp/owner`,
`/mcp/third_party`, `/mcp/offline`), `llama-server` `127.0.0.1:42620`
([README › Ports](README.md#ports)). Files and their contents: [storage.md](storage.md).

## Settings in the app

Everything the owner can change lives in `files/policy.json` (sealed) and is set either by voice
(`set_preference`, `set_contact_group`, `set_rule`) or on the Settings screen; the full table of
names, values, defaults and keys is in
[settings-and-setup.md › State and data](../specs/settings-and-setup.md#state-and-data). In short:

| Group | Settings |
|---|---|
| Owner | name, form of address, language (Spanish, English or the phone's) |
| Messages | messaging apps read (none by default), group chats (off), family and work contacts, undo window (10, 30, 60 or 120 s; default 30), read email (on) |
| Presence | floating mark (on) and its place, speak aloud (off), open microphone (off), warmth (natural), palette (night), assistant or app mode, Home widgets |
| Voice | the phone voice chosen (`Voice.kt`, stored as `voiceName`; the measured pitch of each probed voice in `shared_prefs/voices.xml`), or the natural voice when `voice.*` is configured; its monthly ceiling (`voiceCeiling`, default US$ 5; 0 means none) |
| Offline | opt-in (`offline`, off); the download state in `shared_prefs/offline.xml` |
| Memory | learning on or off (`memory.json`) |
| Monthly model budget | US$ a month for the model (none by default); by voice (`model_budget`) or Settings › What I cost |
| Rules | one autonomy per capability, principal and scope, editable up to the risk class's ceiling; changes need the device credential |
| Services (Connectors: *AI models* — language model, photos, natural voice — apart from *Integrations* with other apps and services — web search, YouTube) | model provider and model, chosen from ZeroClaw's catalog (`/api/config/catalog`: the providers this build knows, most used first, and each provider's models, live from the provider when it can), with a search and "use what I typed"; a custom gateway's URL (`MODEL_URL`, written as the provider's `uri`; `https://`, or `http://` only to this phone); the model proposed for the chosen provider from `assets/models.json` ([ADR-0033](../adr/0033-recommended-models-in-a-data-file.md)); key; price per million tokens, filled from the catalog when the provider publishes it (under Advanced). Web search provider and key; YouTube key; natural voice: the service and its voice chosen from `assets/voices.json` ([ADR-0031](../adr/0031-voice-services-named-in-a-data-file.md)), or "Other (my own URL)" with its WebSocket URL; then its key ("API key of <service>"); its speech model and price under Advanced, filled from the file. Photos: provider (those with a model for pictures in `models.json`), model and key. Each group being changed marks what it still needs, the key in red, and saving waits for it; new model and voice keys are then checked with their provider, and a refused one is discarded with its group's changes (`KeyCheck`). A saved key shows as saved, with *Change*. A group whose saved settings are complete (main model, photos, natural voice) shows "Active" in green beside its title. Encrypted on the phone (`Keys.kt`), over the build's defaults; replacing them needs the device credential ([ADR-0030](../adr/0030-service-keys-entered-by-the-owner.md)). Model provider and model names are not written in the app: they come from the daemon |

## Minimal start-up

From a clone to a working assistant, with nothing optional:

1. Install JDK 21, the Android SDK (platform 35, build-tools 35.0.0, NDK 29.0.14206865) and
   `patchelf` (`apt install patchelf`, or `pip install patchelf`, which puts it in `~/.local/bin`).
2. Create `local.properties` at the repository root with `sdk.dir` and, for a debug build that works at once,
   `zeroclaw.provider`, `zeroclaw.model` and `zeroclaw.apiKey`. Without them, enter the provider, model and key in
   the app (Connectors › Services) after installing; a release build always takes this path.
3. `./gradlew assembleDebug` (the first build downloads and verifies the two native archives).
   `./gradlew testDebugUnitTest` runs the JVM tests and needs no device.
4. Install on an arm64 phone with Android 11 or later, or on the emulator:
   `adb install -r -g app/build/outputs/apk/debug/app-debug.apk` (`-g` grants the runtime
   permissions, so the setup shows them as granted).
5. Open *Ksandra* (`adb shell am start -n org.rustlanglatam.zeroclaw.android/.MainActivity`). The
   foreground service writes `config.toml`, starts the daemon, waits for `/health` and pairs; the
   mark goes from grey (offline) to resting when the gateway is up.
6. First-run setup, three steps, saved with the device credential when the phone has a screen lock:
   1. name and language;
   2. what the app needs, each with its state and a button to the system setting: notifications,
      contacts, calendar and photos (runtime permissions); a fingerprint (optional); staying active
      (battery optimization off); full-screen calls for high reminders; and, optional, all-files
      access for file search, usage access to know the most used apps, the overlay for the floating
      mark, and notification access to read messages;
   3. who the assistant talks to: the messaging apps read (none by default) and whether group chats
      too, the family and work contacts, and whether answers are spoken.
7. Tap the mark and speak, or type in Interactions.

Without a model (neither the three `zeroclaw.*` keys nor values entered in the app) the app builds and installs,
Home says it has no model to think with and opens Connectors › Services; until then every turn fails. Each optional
key above adds its feature and nothing else depends on it; each can also be entered in the same form.

### Emulator

- `emulator -avd <name> -gpu host -no-audio -no-window &`: an API 34 x86_64 image runs the arm64
  binaries through translation. The maintainers' image `zc34` holds fake contacts (Laura, Ana,
  Pat), calendar events and the PIN `1234`; it is not in the repository.
- Unlock after boot, before anything else (`adb shell input keyevent 82; adb shell input text 1234;
  adb shell input keyevent 66`): a locked user hides the app's activities ("Activity class does not
  exist").
- `-gpu host` (or `hw.gpu.enabled=yes` in the AVD's `config.ini`): without it rendering is software
  only and animations stutter. `-no-audio`: voice, chime and reminder calls are not needed for the
  checks.
- On a laptop with two GPUs, stay on the integrated one and disable Vulkan (`-feature -Vulkan`).
- The emulator needs `/dev/kvm`; on Linux, access can be dropped while the desktop session is
  inactive. Stop it when done (`adb emu kill`); it uses several GB of RAM.
- `scripts/e2e.sh` (end-to-end checks of the notification channel) refuses to run on anything but
  an emulator, needs both debug APKs built and a model key in `local.properties`, and installs the
  `fakechat` module, a fake messaging app that posts notifications and records replies.
  `scripts/screenshots.sh en` regenerates `docs/images/` (`ANDROID_SERIAL=emulator-…`).

### Real device

- arm64, Android 11 or later. Sideloaded builds: the setup asks to exclude the app from battery
  optimization (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`), which app stores restrict; on Xiaomi
  phones the setup also points to autostart and the lock-screen permission.
- Never point a test at real people: the notification channel replies to whoever wrote. Keep
  notification access off, or select no messaging app, unless the contacts are fake.
- A release for daily use: `./gradlew assembleRelease` with the `release.*` keys.
