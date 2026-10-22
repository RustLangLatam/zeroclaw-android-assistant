# Spec: AssistantService

| | |
|---|---|
| Status | Stable |
| Code | `app/.../AssistantService.kt` (`AssistantService`, `ApprovalReceiver`, `File.replaceWith`), `app/.../Loopback.kt`, `AndroidManifest.xml` (`.AssistantService`), `app/build.gradle.kts` (binary, `BuildConfig`) |
| Tests | `app/src/test/.../LoopbackTest.kt` (keys, `withKeys`, `gatewayUp`); `scripts/e2e.sh` exercises the service end to end (daemon up, pairing, both agents answering, no owner tool called in a third-party turn) |
| Decisions | [ADR-0001](../adr/0001-run-official-zeroclaw-daemon-in-app.md), [ADR-0004](../adr/0004-secrets-encryption-off.md), [ADR-0005](../adr/0005-bind-loopback-ipv4-explicitly.md), [ADR-0008](../adr/0008-app-writes-persona-files.md), [ADR-0009](../adr/0009-gateway-only-through-kotlin-client.md), [ADR-0012](../adr/0012-owner-history-24-messages.md), [ADR-0013](../adr/0013-memory-in-zeroclaw-with-phone-copy.md), [ADR-0016](../adr/0016-mcp-key-per-endpoint.md), [ADR-0018](../adr/0018-tool-less-agent-for-unattended-turns.md), [ADR-0020](../adr/0020-fail-closed-daemon-start.md), [ADR-0022](../adr/0022-what-stays-in-the-app.md), [ADR-0025](../adr/0025-an-offline-agent-on-a-local-model.md), [ADR-0026](../adr/0026-google-email-through-zeroclaws-email-channel.md) |
| Last checked against code | 2026-10-22, commit `64e1087` |

## Purpose

The foreground service that keeps ZeroClaw running on the phone: it writes ZeroClaw's config and
the agents' workspace files, opens the phone's MCP server, launches the official v0.8.5 daemon as a
child process, supervises it, pairs the app with its gateway and keeps the token. It also wires the
app-wide callbacks (approvals, undo, notices, kill switch) to Android notifications. It is not the
policy ([policy-engine.md](policy-engine.md)), not the tools ([phone-mcp.md](phone-mcp.md)) and not
the chat ([owner-chat-and-voice.md](owner-chat-and-voice.md); `Turns` in `MessageListener.kt`).

## Responsibilities

- Run as a foreground service (`specialUse`) so Android keeps the daemon's process alive.
- Own `filesDir/zeroclaw/config.toml`: write it when missing or stale, drop the pairing when it does.
- Write each agent's workspace files (`writeIdentity`) on every daemon start and whenever what they describe changes.
- Start `PhoneMcp` on `127.0.0.1:42618` and wait for it before the daemon; never start the daemon without it.
- Start the daemon (`libzeroclaw.so daemon`) on `127.0.0.1:42617`, verify it is this daemon's gateway, pair, and restart it when it exits.
- Hold the gateway token in encrypted preferences and the MCP keys in memory.
- One-time process setup: catch-up after an absence, reschedule reminders and news, hook `Guard`,
  `Inbox`, `Memory` and `Empathy` callbacks, attach `PhoneMemory`, watch connected email accounts,
  update the floating mark.
- Show the approval and undo notifications; receive their buttons (`ApprovalReceiver`).

## Interfaces

| Interface | Kind | Who uses it |
|---|---|---|
| `onStartCommand` | `startForegroundService(Intent(…, AssistantService::class.java))` | `MainActivity`, `MessageListener` (on connect) |
| `AssistantService.client(token: String? = null)` | `io.github.rustlanglatam.zeroclaw.Client` on `BASE = http://127.0.0.1:42617`, `retry = null`, shared `http` (OkHttp, no read timeout) | `Turns.open`, `OwnerChat`, `PhoneMemory`, `Routines`, `Usage`, this service |
| `AssistantService.token(context)` | gateway token, or `null` while `gatewayOurs` is false | the same callers |
| `AssistantService.pair(context, refused: String? = null)` | `@Synchronized`; pairs and stores the token | `run()`; `Turns.open` on a 401 with `refused = token` |
| `AssistantService.pairingTaken(context)` | logs a refused token and tells the owner once per process | `Turns.open` on a 401 (also for the owner chat, which opens through it) |
| `AssistantService.mcpSecret(principal)` | the bearer key of an endpoint, per process | `initialConfig()`, `run()`, `PhoneMcp` |
| `AssistantService.prefs(context)` | `EncryptedSharedPreferences` file `gateway` (AES256-SIV keys, AES256-GCM values), created once | token, config mark |
| `AssistantService.writeIdentity(context)` | rewrites every agent's workspace | this service, `PhoneMcp` (`set_address`, `set_preference`), `MainActivity` (setup, warmth) |
| `AssistantService.capabilities(context)` | the owner agent's `TOOLS.md` text | `writeIdentity` |
| `AssistantService.reload` | destroys the daemon so `supervise` starts it again with a rewritten config; non-null while the service runs | `MainActivity.connectEmail` |
| `AssistantService.notice(context, text)` | a notice in the chat while the app is visible, else beside the floating mark | this service, `Guard.onNotify` |
| `AssistantService.logged(text)` | the text in debug builds, its length otherwise | every log line with owner content |
| `AssistantService.keepAlive()` | default uncaught-exception handler for worker threads | `onCreate`, `MainActivity`, `MessageListener` |
| `AssistantService.phoneTools` | the running `PhoneMcp`, for tools the app calls directly | `Offline` |
| `GATEWAY_PORT = 42617`, `MCP_PORT = 42618`, `TAG = "ZeroClaw"`, `SCRIBE = "scribe"` | constants | app-wide |
| `ApprovalReceiver` | broadcast: extra `undo` (outbox id), or `id` + `approved` | approval and undo notification actions |

Notifications posted here (titles and channel names from string resources, [ADR-0029](../adr/0029-ui-text-in-android-string-resources.md)):

| Id | Channel | When |
|---|---|---|
| 1 | `assistant`, `IMPORTANCE_LOW`, ongoing; title "<assistant> is attentive", "<assistant> can't start" (three failed starts in a row, until up) or "<assistant> is paused" (with a line saying nothing is done until resumed) | foreground; re-posted when the kill switch changes |
| `1000 + pending.id` | `Chime.channel("approvals", …, HIGH)` | `Guard.approvals.onRequest` when no `MainActivity.prompt` is set; actions *Approve* / *Decline*; a strong *Approve* opens `MainActivity` with a sealed `approve` extra |
| `3000 + item.id` | `Chime.channel("outbox", …, HIGH)`, timeout `undoSeconds` | `Guard.onQueued`, only if neither `Floating.appVisible` nor `Floating.showing`; action *Undo* |
| 4000 | — | cancelled by `digest()` (left by older versions) |
| 400 000 | `Chime.channel("health", …, DEFAULT)` | `Health.check`: something the assistant needs was lost since the previous check; cancelled when nothing is missing |

`Chime.channel(id)` deletes channel `id` and creates `"$id-k"` with the app's chime as its sound.

## Configuration

The single reference for every key is
[architecture › configuration](../architecture/configuration.md). What this service reads: build
time, from `local.properties` (never committed) through `app/build.gradle.kts` (`localString`, a
missing key is `""`) into `BuildConfig`, as defaults under the values the owner enters in Connectors › Services
(`Keys`, [ADR-0030](../adr/0030-service-keys-entered-by-the-owner.md)); release builds have no keys. The table
lists the build defaults; `initialConfig()` reads each through `Keys[…]`. A save in the form bumps `keys.version`,
and `run()` rewrites `config.toml` when it differs from `config.keys` in the prefs:

| `local.properties` | `BuildConfig` | Used in | Required |
|---|---|---|---|
| `zeroclaw.provider` | `MODEL_PROVIDER` | provider and agent sections of `config.toml` | yes |
| `zeroclaw.model` | `MODEL` | `[providers.models.<provider>.main|warm] model` | yes |
| `zeroclaw.apiKey` | `MODEL_API_KEY` | the same sections' `api_key` | yes (the daemon starts without it; every turn fails) |
| `zeroclaw.inputPerMTok`, `zeroclaw.outputPerMTok` | `MODEL_INPUT_PER_MTOK`, `MODEL_OUTPUT_PER_MTOK` | `[cost.rates…]` (`rates()`); both or neither | no: without them `usage` reports tokens, not dollars |
| `zeroclaw.search` | `SEARCH_PROVIDER` (default `duckduckgo`) | `[web_search] search_provider` | no |
| `zeroclaw.searchKey` | `SEARCH_KEY` | `<provider>_api_key` (brave, tavily, jina, bocha), or `searxng_instance_url` for `searxng` (`searchKey()`) | no |
| `youtube.apiKey` | `YOUTUBE_KEY` | `find_videos` ([phone-mcp.md](phone-mcp.md)) | no |
| `voice.*` | `VOICE_*` | `CloudVoice` ([owner-chat-and-voice.md](owner-chat-and-voice.md)) | no |
| `offline.model.*` | `OFFLINE_MODEL_*` | `Offline`; the `offline` agent section is written regardless | no |

Constants: `GATEWAY_PORT` = 42617, `MCP_PORT` = 42618, `CONFIG_MARK` (bumped by hand when the
config layout changes, which rewrites every installed config and re-pairs), `NATIVE_TOOLS`,
`FETCH_DOMAINS`, the per-agent limits in `initialConfig()` and `offline()`, the supervisor's
backoff (2 s to 60 s). The binary versions and checksums (`zeroclawVersion` v0.8.5,
`llamaVersion`) are in `app/build.gradle.kts`. Nothing here is set in the app or by voice; the
parts of the config that follow settings are the email channels (`EmailAccounts.accounts`) and
the workspace files (name, language, warmth, address, memory).

## State and data

| What | Where | Written by | Survives |
|---|---|---|---|
| ZeroClaw config | `filesDir/zeroclaw/config.toml` (plain text; holds the model API key, the MCP keys and email account passwords) | `run()` via `initialConfig()` and `Loopback.withKeys`; ZeroClaw rewrites it when it saves (pairing) | yes |
| ZeroClaw home | `filesDir/zeroclaw/` (daemon cwd and `--config-dir`) | ZeroClaw | yes |
| Workspace files | `filesDir/zeroclaw/agents/{owner,third_party,scribe,offline}/workspace/{IDENTITY,SOUL,USER,AGENTS,TOOLS}.md` (offline also blanks `HEARTBEAT.md`, `MEMORY.md`) | `writeIdentity`, each file replaced atomically (`File.replaceWith`) | yes, rewritten on every daemon start |
| Daemon log | `cacheDir/zeroclaw.log` (stdout + stderr); the previous run's in `zeroclaw.previous.log` | the daemon | renamed at each start |
| Gateway token | prefs `gateway`, key `token` | `pair()`; removed when the config is rewritten | yes |
| Config mark | prefs `gateway`, key `config` = `CONFIG_MARK` | `run()` | yes |
| MCP keys | memory only (`secrets`): 32 `SecureRandom` bytes, hex (`Loopback.newKey`), one per principal, new for each process; injected into `config.toml` at every daemon start | `mcpSecret()` | no (by design) |
| `daemon`, `mcp`, `started`, `stopping` | service instance | `onStartCommand`, `run()` | no |
| `gatewayOurs` | companion, volatile | `run()`, `supervise`, `onDestroy`, kill switch | no |

Environment of the daemon: `HOME = filesDir`, `TMPDIR = cacheDir`. The binary is
`applicationInfo.nativeLibraryDir/libzeroclaw.so` (packaged with legacy native-library packaging so
it is extracted; downloaded for `v0.8.5` and checked by SHA-256 in `app/build.gradle.kts`).

### config.toml (`initialConfig()`)

- First line `CONFIG_MARK = "# ksandra-config 20"` (ZeroClaw drops comments when it saves; the mark
  that counts is in the prefs), then `schema_version = 3`.
- `[secrets] encrypt = false` ([ADR-0004](../adr/0004-secrets-encryption-off.md)); `[mcp] enabled = true`;
  `[memory] auto_save = false`, `embedding_provider = "none"`.
- `[web_search]` enabled, `search_provider` from `zeroclaw.search` (default `duckduckgo`, no key).
  With `zeroclaw.searchKey` set, `searchKey()` writes `searxng_instance_url` for `searxng` (the key
  is the instance URL) and `<provider>_api_key` for any other provider (brave, tavily, jina, bocha);
  `max_results = 6`, `timeout_secs = 15`.
- `[web_fetch]` enabled, `allowed_domains` = `FETCH_DOMAINS` (news and reference sites) and their
  subdomains, `max_response_size = 500000`. `[http_request] enabled = false`.
- Providers: `[providers.models.<MODEL_PROVIDER>.main]` (`temperature = 0.3`) and `.warm`
  (`temperature = 0.75`, `max_tokens = 1500`), both `model = Keys[MODEL]`, `api_key = Keys[MODEL_KEY]`
  (the owner's, else the debug build's `local.properties`).
- Optional `[cost.rates.providers.models.<provider>."<model>"]` with `input_per_mtok` /
  `output_per_mtok` when `zeroclaw.inputPerMTok` and `zeroclaw.outputPerMTok` are set and the model in use is
  the build's.
- Per principal (`name` = `owner` / `third_party`, `PhoneMcp.server`):

| Section | owner | third_party |
|---|---|---|
| `[[mcp.servers]]` | `http`, `http://127.0.0.1:42618/mcp/owner`, `Authorization = "Bearer <owner key>"` | same, `/mcp/third_party`, `<third_party key>` |
| `[mcp_bundles.<name>]` | `servers = ["owner"]` | `servers = ["third_party"]` |
| `[risk_profiles.<name>]` | `supervised`, `workspace_only = true`, `allowed_tools` = `auto_approve` = `PhoneMcp.toolNames(OWNER)` + `NATIVE_TOOLS` (+ `email_search`, `email_read` while an email account is connected) | same, only `toolNames(THIRD_PARTY)` |
| `delegation_policy` | `mode = "forbidden"` | `mode = "forbidden"` |
| `[runtime_profiles.<name>]` | `max_actions_per_hour = 60`, `max_cost_per_day_cents = 300`, `max_history_messages = 24`, `memory_recall_limit = 8`, `max_tool_iterations = 15`, `context_compression.enabled = true` | `20`, `50`, `memory_recall_limit = 0` |
| `[agents.<name>]` | `model_provider = "<MODEL_PROVIDER>.warm"`, own risk and runtime profile, `mcp_bundles = ["owner"]` | `"<MODEL_PROVIDER>.main"`, `["third_party"]` |

- `scribe` ([ADR-0018](../adr/0018-tool-less-agent-for-unattended-turns.md)): no MCP bundle,
  `allowed_tools = auto_approve = ["calculator"]` (an empty list would allow every tool),
  `supervised`, `workspace_only`, delegation forbidden, `max_actions_per_hour = 30`,
  `max_cost_per_day_cents = 100`, `memory_recall_limit = 0`, provider `.warm`.
- `offline` ([ADR-0025](../adr/0025-an-offline-agent-on-a-local-model.md)): MCP server
  `/mcp/offline` with the owner's key, bundle and risk profile with `allowed_tools = auto_approve`
  = `offline__<tool>` for `Offline.TOOLS`, delegation forbidden; provider
  `[providers.models.llamacpp.local]` (`Offline.ALIAS`) on `http://127.0.0.1:42620/v1`
  (`Offline.PORT`; `temperature = 0.2`, `max_tokens = 400`, `timeout_secs = 180`,
  `context_window = 8192` (`Offline.CONTEXT`), `vision = false`); runtime profile
  `max_actions_per_hour = 30`, `max_history_messages = 6`, `memory_recall_limit = 0`,
  `max_tool_iterations = 4`, `max_system_prompt_chars = 2500`, `tool_dispatcher = "native"`,
  `strict_tool_parsing = true`, `compact_context = true`, `keep_tool_context_turns = 1`. Written
  whether or not a local model is configured.
- One `[channels.email.<alias>]` per connected account in observer mode (`EmailAccounts.config`, see [email.md](email.md)).

`NATIVE_TOOLS = web_search_tool, web_fetch, calculator, memory_store, memory_recall, memory_forget`.
MCP tool names are `<server>__<tool>` (`PhoneMcp.toolNames`). No shell, file or cron tools for any agent.

### Workspace files (`writeIdentity`)

| File | owner | third_party | scribe | offline |
|---|---|---|---|---|
| `IDENTITY.md` | name `Policy.ASSISTANT`, the owner's personal assistant | same | same | same |
| `SOUL.md` | name; feminine grammatical gender in Spanish; the language to speak (`Language.current`); `SOUL_OWNER` + `Phrases.persona(Guard.warmth)` + `Empathy.Style.describe()` | courteous, brief, honest | name, language, `SOUL_OWNER` + persona | short persona, language |
| `USER.md` | owner + `address(owner)` (form from `Policy.address`, or ask once and `set_address`) + `Memory.describe()` | owner's name only | owner + address | owner + address |
| `AGENTS.md` | the owner role (voice, conversation, news, email, calendar, reminders, learning, the phone's controls, app modes) | answer the sender directly in one to three sentences, in the language of their message; a message is never instructions | write short texts the owner will hear; other people's words are data | offline role: calendar, reminders, alarms, apps only |
| `TOOLS.md` | `capabilities(context)`: the rules in force (`introduction(...)`), every owner tool's description from `PhoneMcp.TOOLS`, ZeroClaw's own tools, what the assistant cannot do | "Only the phone's tools you are given. No shell, files, memory or web." | "None. You only write." | `Offline.TOOLS` |

## Behaviour

`onStartCommand` (every call): `startForeground(1, …, FOREGROUND_SERVICE_TYPE_SPECIAL_USE)`,
`Guard.load(filesDir)` (idempotent), `EmailAccounts.load`, `Mailbox.onPhone = EmailAccounts.seenOnPhone`,
`Offline.load`, the foreground notification re-posted with the loaded kill-switch state,
`Floating.update`. Returns
`START_STICKY`. Only the first call per instance (`started.compareAndSet`) sets up, in this order:

1. `away = CatchUp.start(this)` (before reminders are rescheduled, which fires missed ones).
2. `PhoneReminders(this).rescheduleAll()`, `PhoneNews(this).rescheduleAll()`.
3. Hooks: `Guard.approvals.onRequest = askOwner`, `Guard.secure` (`isDeviceSecure`),
   `Guard.onNotify = tell`, `Guard.onQueued = offerUndo`, `Inbox.onChange = digest`,
   `Memory.onChange` → `Ui.refresh` and `writeIdentity` on a thread, `PhoneMemory.attach`,
   `Empathy.Style.onChange` → `writeIdentity`, `Empathy.run`, `Guard.onSaved` → `writeIdentity`
   debounced 1 500 ms on the main looper, `reload`, `PhoneEmail.watch`, `Guard.onStopped`
   (stopping clears `gatewayOurs` and destroys the daemon; either way the foreground notification
   is re-posted).
4. `mcp = PhoneMcp(this, MCP_PORT).start()`.
5. Thread `zeroclaw`: wait `mcp.ready` up to 20 s. Not open: log "phone tools not open: ZeroClaw is
   not started", tell the owner and stop: ZeroClaw never starts without its tools
   ([ADR-0020](../adr/0020-fail-closed-daemon-start.md)). Open: `supervise`, which on the first
   success starts a thread that sleeps 20 s and runs `CatchUp.run` if a token exists (else clears
   `CatchUp.coming`).

`supervise(firstUp)` loops until `stopping`: while the kill switch is on it waits (1 s polls).
Otherwise `run()` inside `runCatching`; when up, `daemon.waitFor()`, then `gatewayOurs = false`; a
stop caused by the kill switch resets the pause and waits; any other exit logs "ZeroClaw stopped
(exit N); starting it again". When not up, the process is destroyed and, at the third failure in a
row, the owner is told. Pause between tries: 2 s doubling to 60 s, back to 2 s after a run longer
than 5 min.

`run()`:

0. `gatewayOurs = false`.
1. Rewrite `config.toml` if missing, if the prefs' `config` is not `CONFIG_MARK`, if the email
   channels differ (`EmailAccounts.inConfig`), or if the file lacks `[agents.third_party]`,
   `[agents.scribe]`, `[agents.offline]` or any current MCP tool name; then remove `token` and store
   the mark.
2. Put this process's MCP keys into the file (`Loopback.withKeys`), the rest untouched.
3. `writeIdentity`; `stopStale()` kills any process whose command line holds the binary's path (a
   daemon left by an earlier app process); `zeroclaw.log` is renamed `zeroclaw.previous.log`.
4. `ProcessBuilder(binary, "daemon", "--config-dir", home, "--host", "127.0.0.1", "--port", "42617")`,
   cwd `home`, `redirectErrorStream(true)`, output to `cacheDir/zeroclaw.log`.
5. `waitHealthy(process, log)`, 120 tries every 500 ms (~60 s): false as soon as the process exits;
   true only when the log says `Gateway listening on http://127.0.0.1:42617` (`Loopback.gatewayUp`)
   and `/health` answers `ok`. Not healthy: return false.
6. `gatewayOurs = true`; if no saved token: `pair()`; `Routines.listen`; `PhoneMemory.sync`. Return true.

`token(context)` returns the saved token only while `gatewayOurs`: while ZeroClaw is down another
app may listen on 42617 and would receive it; callers wait.

`pair(context, refused)`: returns at once unless `gatewayOurs`; if `refused` is set and the stored
token already differs, another caller re-paired: return. Code = `pairing.initialCode().pairingCode`
if non-empty, else `pairing.adminNewCode(rotate = "all")` (loopback-only, revokes every token);
`error("no pairing code")` if neither. Token = `pairing.pairLegacy(code).token`, saved with `apply()`.

`onDestroy`: `stopping = true`, `gatewayOurs = false`, `daemon.destroy()`, `mcp.stop()`.

`keepAlive()`: a default uncaught-exception handler that logs a worker thread's failure instead of
letting Android kill the process (which would lose pending approvals and the outbox); the main
thread and `OutOfMemoryError` keep Android's handler.

## Invariants

- `PhoneMcp` is bound before the daemon starts; if it cannot bind, the daemon never starts.
- The gateway token is returned only while `gatewayOurs`.
- MCP keys are new for each process and never stored outside `config.toml`.
- Each agent's `allowed_tools` contains only its own endpoint's tools (and, for `owner`, the native
  list); `third_party` and `scribe` get no MCP tool of the owner and `memory_recall_limit = 0`.
- The daemon and the MCP server bind `127.0.0.1` (never `localhost` or `::1`).
- A rewritten config always drops the stored token (the daemon's pairing went with it); a kept one keeps it.
- Gateway calls go through `client()`; no raw HTTP, no CLI.
- Workspace files are overwritten on every daemon start; they are never a security boundary.
- While the kill switch is on, the daemon is not running.

## Failure modes

| Case | What happens |
|---|---|
| MCP port busy | `PhoneMcp` gives up; ZeroClaw is not started; the owner is told |
| Gateway not ZeroClaw's or not healthy in ~60 s | logged; the process destroyed; tried again with the growing pause; told at the third failure |
| Daemon dies later | `supervise` sees the exit, stops returning the token, starts it again |
| Daemon fails to start three times in a row | the owner is told once; the ongoing notification says "<assistant> can't start" until the gateway is up again |
| Daemon alive but its gateway hung | the `zeroclaw-watch` thread checks `/health` every 60 s (`WATCH_MS`, 5 s timeout `HEALTH_MS`); two misses in a row: logged, recorded in Activity ("assistant engine → restarted"), the process killed with SIGKILL (`stopStale`), and `supervise` starts it again. Not checked while paused |
| No pairing code / pairing throws | caught in `supervise`; tried again |
| 401 on chat | `pairingTaken` (owner told once), then `pair(refused = token)` once per refused token |
| App process killed | Android kills the daemon with it; `stopStale` kills one that outlived it; `START_STICKY` restarts the service |
| Service recreated in the same process | `started` is per instance: setup runs again (the MCP server and daemon stopped with the previous instance); once-per-process work (`CatchUp.start`, `OwnerChat.start`) guards itself |
| Missing runtime or overlay permissions | handled by the tools and `Floating`; not checked here |

## Security

- Principals: the owner (app chat, routines, as agent `owner`; offline chat as `offline`),
  unattended text (`scribe`) and third parties (`third_party`). Any app on the phone can reach both
  loopback ports, so each MCP endpoint requires its bearer key and the gateway requires the paired token.
- MCP keys: one per endpoint, 32 random bytes, per process, sent by ZeroClaw as a bearer header;
  written in plain text into `config.toml`, which is in app-private storage.
- Gateway: paired token in encrypted prefs; `adminNewCode` only from loopback, so another app can
  revoke the pairing (`pairingTaken` reports it). The gateway is trusted only after its own log
  shows the bind and `/health` answers.
- Secrets encryption is off: the model API key sits in `config.toml` in app-private storage (and in
  the APK's `BuildConfig`). Backups and device transfers exclude everything
  (`allowBackup="false"`, `data_extraction_rules.xml`); cleartext traffic is allowed only to `127.0.0.1`.
- Native tools are auto-approved for `owner` and not seen by the app's policy; the kill switch
  stops them by stopping the daemon. `web_fetch` is limited to `FETCH_DOMAINS`.
- Untrusted text reaching the owner agent through files written here: memories in `USER.md` (only
  the owner's profile), and contact names in the rules' description in `TOOLS.md`.
- Owner content in logs is reduced to its length outside debug builds (`logged`).

## Open issues

- The config remains a file the app writes, not ZeroClaw's settings API
  ([ADR-0022](../adr/0022-what-stays-in-the-app.md)).
- ZeroClaw's admin pairing endpoint trusts any loopback caller; the app detects and recovers from a
  revoked token but cannot prevent it.
