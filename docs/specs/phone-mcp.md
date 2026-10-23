# Spec: PhoneMcp

| | |
|---|---|
| Status | Stable |
| Code | `app/.../PhoneMcp.kt` (`PhoneMcp`, `TOOLS`, `HANDLERS`, `rpc`, `OTHERS_WORDS`, `Guard`), `app/.../Loopback.kt`; helpers it calls: `PhoneApps`, `PhoneMail`, `PhoneEmail`, `PhoneGallery`, `PhoneFiles`, `PhoneReminders`, `PhoneNews`, `PhoneDevice`, `PhoneWidgets`, `Mailbox`, `Inbox`, `Memory`, `Notes`, `Cards`, `Routines`, `Usage`, `Videos`, `Agenda`, `Preferences`, `Sections`, `contactName` (`MessageListener.kt`) |
| Tests | `app/src/test/.../LoopbackTest.kt` (head and body limits, keys, gateway check), `PhoneMcpTest.kt` (every listed tool has a handler and nothing else is served; the JSON-RPC protocol; a throwing tool), `GuardTest.kt`; unit tests of the pure helpers (`AgendaTest`, `AppsTest`, `FilesTest`, `PhotosTest`, `MailboxTest`, `MemoryTest`, `NewsTest`, `RemindersTest`, `DeviceTest`, `PreferencesTest`, `PolicyTest`, `CardsTest`, `NotesTest`, `RoutinesTest`, `VideosTest`); `scripts/e2e.sh` checks that no `tool owner` log line appears during a third-party injection |
| Decisions | [ADR-0002](../adr/0002-phone-tools-over-loopback-mcp-per-principal.md), [ADR-0003](../adr/0003-permissions-enforced-in-code.md), [ADR-0005](../adr/0005-bind-loopback-ipv4-explicitly.md), [ADR-0007](../adr/0007-third-party-turns-one-at-a-time.md), [ADR-0010](../adr/0010-zeroclaw-first-voice-first.md), [ADR-0016](../adr/0016-mcp-key-per-endpoint.md), [ADR-0020](../adr/0020-fail-closed-daemon-start.md), [ADR-0025](../adr/0025-an-offline-agent-on-a-local-model.md), [ADR-0019](../adr/0019-contacts-by-stable-identity.md) |
| Last checked against code | 2026-10-22, commit `64e1087` |

## Purpose

The phone's tools for ZeroClaw: a minimal MCP server (streamable HTTP, plain JSON responses) on
`127.0.0.1:42618` with one endpoint per principal. The principal comes from the endpoint ZeroClaw
was configured with, never from the model. Every tool runs through `Guard.gated`, which applies the
policy, approvals, undo window and activity log ([policy-engine.md](policy-engine.md)). It does not
start ZeroClaw or write its configuration ([assistant-service.md](assistant-service.md)).

## Responsibilities

- Bind `127.0.0.1:42618` and count down `ready`, so the daemon starts only after the port is open.
- Authenticate each request with its endpoint's bearer key.
- Answer JSON-RPC `initialize`, `tools/list` and `tools/call`; accept notifications.
- Expose each principal's tool catalog (`TOOLS`) and refuse the other principal's tools.
- Validate arguments, call the Android side under `Guard.gated`, return text for the agent.
- Publish the tool names for ZeroClaw's configuration (`toolNames`, `server`).
- Run an owner tool on the app's own behalf (`owner(name, args)`), still under the policy.

## Interfaces

| Interface | Input → output | Caller |
|---|---|---|
| `PhoneMcp(context, port).start()` | thread `phone-mcp` | `AssistantService.onStartCommand` |
| `ready: CountDownLatch(1)` | counted down once bound | `AssistantService` (waits 20 s; otherwise ZeroClaw is not started) |
| `stop()` | shuts down and closes the server socket | `AssistantService.onDestroy` |
| `POST /mcp/owner` | JSON-RPC, `Authorization: Bearer <owner key>` | ZeroClaw agent `owner` |
| `POST /mcp/offline` | JSON-RPC, owner key; principal OWNER, catalog restricted to `Offline.TOOLS` (`remind`, `list_reminders`, `create_alarm`, `next_alarm`, `list_events`, `open_app`) | ZeroClaw agent `offline` |
| `POST /mcp/third_party` | JSON-RPC, `Authorization: Bearer <third-party key>` | ZeroClaw agent `third_party` |
| `handle(principal, body, only)` (`internal`) | one JSON-RPC message → `(status, body)` | `serve`, tests |
| `rpc(principal, body, only, call)` (`internal`, pure) | JSON-RPC dispatch | `handle`, `PhoneMcpTest` |
| `owner(name, args)` | runs an owner tool for an explicit app command; logged `tool owner <name> (by the app)` | `Offline.direct` (explicit requests handled without the model) |
| `TOOLS: Map<Principal, Map<String, JSONObject>>` | MCP tool objects (`name`, `description`, `inputSchema`) | `rpc`, `AssistantService` |
| `HANDLERS: Map<String, PhoneMcp.(Principal, JSONObject) -> String>` | one implementation per tool | `call`, `owner` |
| `toolNames(principal)` | `"<server>__<tool>"` list | `AssistantService` (config, staleness check) |
| `server(principal)` | `owner` / `third_party` | config, workspace paths |
| `events(context, from, to)` | calendar instances `(title, begin, end)` sorted, or `null` without `READ_CALENDAR` | tools, Home widgets |
| `ADDRESS` | `masculine` / `feminine` / `neutral` → instruction text | `set_address`, `writeIdentity` |
| `OTHERS_WORDS` | tools whose results carry third-party text: `list_emails`, `read_email`, `email_search`, `email_read`, `news`, `find_files`, `web_fetch`, `web_search_tool` | `call`, `OwnerChat` |

### HTTP and JSON-RPC

- A native IPv4 socket (`android.system.Os`: `AF_INET`, `SO_REUSEADDR`, backlog 16) bound to
  `127.0.0.1`, not `java.net.ServerSocket` (which listens on IPv6 with the address mapped and lets
  another app bind IPv4 on the same port, [ADR-0005](../adr/0005-bind-loopback-ipv4-explicitly.md)).
  Up to 30 bind attempts 500 ms apart; on the last failure it logs `port … still busy` and ends the
  thread without counting down `ready`.
- One request per connection, one thread per connection, at most 16 at once (`MAX_CONNECTIONS`;
  extra connections are closed at once); 10 s read timeout (`SO_RCVTIMEO`); each connection thread
  catches its own errors.
- The head is read byte by byte up to 16 KiB (`Loopback.readHead`; over the limit the connection is
  closed with no answer). The body (`Content-Length`, no chunked bodies) is read only after the key
  matched, and only up to 1 MiB. Responses carry `Content-Type: application/json` and `Connection: close`.
- Status: not `POST` or unknown path → `405`; missing or wrong key (`MessageDigest.isEqual` against
  `AssistantService.mcpSecret(principal)`) → `401`, logged `refused a call to <path> without its key
  (none|wrong)`; `Content-Length` not a number or over 1 MiB → `413`; a message without `id`
  (notification) → `202`, empty body; otherwise `200`. A missing `Content-Length` reads as 0.
- Methods: `initialize` → `protocolVersion` echoed (default `2024-11-05`), `capabilities.tools = {}`,
  `serverInfo = {name: "phone", version: "0.1.0"}`; `tools/list` → the principal's `TOOLS`, filtered
  by `only`; `tools/call` → the handler. Unparseable JSON → `-32700`; no tool name → `-32602`;
  any other method → `-32601`.
- A tool result is `{"content": [{"type": "text", "text": <text>}]}`; a handler that throws yields
  `isError: true` with `The tool <name> failed: …` (logged), not a failed request.
- A tool not in the caller's catalog → activity-log entry `refused` (`not a <principal> tool`) and
  text `Unknown tool <name>.`; on the offline endpoint a tool outside `Offline.TOOLS` gets `Unknown tool`.
- Every call is logged as `tool <principal> <name>: <text>`; outside debug builds only the result's
  length is logged (`AssistantService.logged`).

## Tool catalog

`inputSchema` lists property types and `required`. The capability is what `Guard.gated` decides
on; the target is `Policy.OWNER_TARGET` unless noted. Pre-checks (bad dates, unknown app or
setting, missing arguments, missing permission) answer with guidance text without gating.
Behaviour of each group is specified where noted.

### Owner (`/mcp/owner`), 60 tools

| Group | Tools | Capability | Spec |
|---|---|---|---|
| Alarms | `create_alarm`, `cancel_alarm`, `next_alarm` | ALARM_SET | [phone-abilities.md](phone-abilities.md) |
| Calendar | `list_events`, `free_time` / `add_event`, `move_event` / `delete_event` | CALENDAR_READ / CALENDAR_WRITE / ITEMS_DELETE | [phone-abilities.md](phone-abilities.md) |
| Contacts | `search_contacts` / `add_contact`, `edit_contact` / `delete_contact` | CONTACTS_READ / CONTACTS_WRITE / ITEMS_DELETE | [phone-abilities.md](phone-abilities.md) |
| Messages | `send_message` (target `Policy.target(contactName(to))`) / `list_messages` / `act_on` | MESSAGE_SEND / MESSAGES_READ / ITEMS_MANAGE or ITEMS_DELETE | [phone-abilities.md](phone-abilities.md), [notification-channel.md](notification-channel.md) |
| Apps and media | `list_apps` / `open_app`, `play_media` / `media_control` | APPS_READ / APP_OPEN / MEDIA_CONTROL | [phone-abilities.md](phone-abilities.md) |
| Device | `device_setting`, `device_status`, `weather` | DEVICE | [phone-abilities.md](phone-abilities.md), [home-and-widgets.md](home-and-widgets.md) |
| Photos and files | `find_photos` / `find_files` | PHOTOS_READ / FILES_READ | [phone-abilities.md](phone-abilities.md) |
| Reminders | `remind`, `list_reminders`, `cancel_reminder`, `move_reminder` | REMINDERS | [reminders.md](reminders.md) |
| News and videos | `follow_topic`, `unfollow_topic`, `list_topics`, `news`, `find_videos` | NEWS | [news.md](news.md) |
| Email (notifications) | `list_emails`, `read_email` / `draft_email` | EMAIL_READ / EMAIL_DRAFT | [email.md](email.md) |
| Email (accounts) | `connect_email` / `email_mark_read`, `email_archive` / `email_delete` / `send_email` (target `Policy.target(name)`) | SETTINGS / ITEMS_MANAGE / ITEMS_DELETE / MESSAGE_SEND | [email.md](email.md) |
| Memory and lists | `remember`, `forget`, `recall`, `how_well_i_know_you`, `set_address`; `list_add`, `list_remove`, `list_show`, `list_clear` | MEMORY | [memory.md](memory.md) |
| Routines | `add_routine`, `list_routines`, `remove_routine` | ROUTINES | `Routines.kt` (ZeroClaw cron jobs run as owner turns) |
| Interface | `show`, `show_on_home`, `remove_from_home`, `open_section`, `set_mode` | INTERFACE | [owner-chat-and-voice.md](owner-chat-and-voice.md), [home-and-widgets.md](home-and-widgets.md), [settings-and-setup.md](settings-and-setup.md) |
| Settings and rules | `set_preference` (SETTINGS or CHANNEL), `my_settings`, `set_busy` (SETTINGS), `unanswered` (MESSAGES_READ), `usage`, `list_rules` / `set_contact_group` / `set_rule` | SETTINGS / CONTACT_GROUPS / RULES | [settings-and-setup.md](settings-and-setup.md), [policy-engine.md](policy-engine.md) |

Cross-cutting rules applied in the handlers:

- `send_message`, `send_email` and `draft_email` return `HOLD` while `Guard.undecided` (a calendar
  clash awaits the owner); email tools return `NO_EMAIL` when no account is connected.
- `create_alarm` and `remind` take a time stated in the owner's last message (`Reminders.spoken(OwnerChat.lastWords)`)
  over the model's arguments.
- Activity starts from the background (`create_alarm`, `open_app`, …) add a note that Android may
  have blocked the start unless the app is visible or holds the overlay permission (`unconfirmed`).
- A result starting with `NOT ` marks a failure: no completion notice and no DONE gesture.
- Calling an `OTHERS_WORDS` tool sets `Guard.heardOthers` for the rest of the owner's turn.

### Third party (`/mcp/third_party`)

| Tool | Capability | What it does | Arguments |
|---|---|---|---|
| `take_message` | MESSAGE_TAKE, target `Policy.target(Guard.thirdPartySender)` | logs the message for the owner | `text`* |
| `availability` | AVAILABILITY_SHARE, same target | `busy` or `free` at an hour today (`Disclosure.availability`), nothing else | `hour` 0–23 (default now) |

With no third-party turn in progress (`thirdPartySender == null`) both answer
`No conversation is in progress; nothing was done.`

## Configuration

| What | Where | Default |
|---|---|---|
| Port, limits | constants: `AssistantService.MCP_PORT` = 42618, `MAX_CONNECTIONS` = 16, `READ_TIMEOUT_MS` = 10 000, `Loopback.MAX_HEAD` = 16 KiB, `MAX_BODY` = 1 MiB, `MIN_UNDO_SECONDS` = 10 | — |
| Endpoint keys | generated per process (`AssistantService.mcpSecret`), written into `config.toml` | — |
| `find_videos` | `youtube.apiKey` → `BuildConfig.YOUTUBE_KEY`; blank → `Videos.fallback` (a search link) | blank |
| `set_preference` values | [settings-and-setup.md](settings-and-setup.md) | |
| Offline tool subset | constant `Offline.TOOLS` | |

Nothing else about the server is configurable; the tool behaviours' own settings are in their specs.

## State and data

- In memory: the server socket and per-connection threads; nothing per request survives.
- Helpers own their stores (reminders, topics, memory, mailbox, notes, photo index); see their specs.
- `Guard` (same file) holds the policy state and `policy.json` ([policy-engine.md](policy-engine.md)).
- Shared flags read here: `Guard.undecided`, `Guard.toldClash` (set by `add_event`), `Guard.heardOthers`,
  `Guard.thirdPartySender` (set by `Turns` around a third-party turn).
- `alarms` shared preferences: the alarms set by `create_alarm` and not cancelled.

## Invariants

- The principal is the path; the key must match that path's key.
- `tools/list` returns only the caller's catalog; a call to another principal's tool is refused and logged.
- Every tool that acts or reads personal data runs inside `Guard.gated`.
- Third-party tools never take a target from the model and return no personal data beyond busy/free.
- Every tool in `TOOLS` has an entry in `HANDLERS` and no other (`PhoneMcpTest`).

## Failure modes

| Case | What happens |
|---|---|
| Port held by another process | 30 attempts, then no server; ZeroClaw is not started |
| Bad or oversized `Content-Length`, head over 16 KiB, malformed JSON, missing method or name | `413`, connection closed, or a JSON-RPC error; the server continues |
| Slow or idle client | cut after 10 s |
| More than 16 connections | extras closed at once; a call awaiting approval holds one for up to 60 s |
| Missing Android permission | guidance text such as `No access to the calendar (permission not granted).` |
| Handler throws | `isError` result; worker threads also have an uncaught-exception handler that logs instead of killing the process |
| Policy refuses or owner does not approve | refusal text ([policy-engine.md](policy-engine.md)) |

## Security

- Reachable by any app on the phone (loopback); only bearer-key holders get past `401`.
- Keys: 32 random bytes per endpoint (`Loopback.newKey`), new for each process, compared in constant
  time, written only into ZeroClaw's `config.toml`.
- Third-party turns reach only `take_message` and `availability`, targeted at `thirdPartySender`;
  turns run one at a time ([ADR-0007](../adr/0007-third-party-turns-one-at-a-time.md)).
- The offline agent reaches only its six tools.
- Untrusted input: tool arguments come from the model, which reads message, email and web text.
  Arguments are parsed with `opt*` defaults and clamps, never executed; contact targets go through
  `contactName`.
- Logcat receives tool results in full only in debug builds.

## Open issues

- Owner-tool targets (`send_message`, `send_email`) are the name the model gives; `contactName`
  resolves only a phone number to a contact. [ADR-0019](../adr/0019-contacts-by-stable-identity.md)
  covers incoming senders, not these targets.
- `thirdPartySender` is process-global; it relies on third-party turns running one at a time.
- The HTTP reader supports only ZeroClaw's MCP client (no chunked bodies, one request per connection).
