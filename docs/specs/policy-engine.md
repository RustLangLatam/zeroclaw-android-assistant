# Spec: Policy engine

| | |
|---|---|
| Status | Stable |
| Code | `app/.../Policy.kt` (`Principal`, `Autonomy`, `Risk`, `Capability`, `Scope`, `Rule`, `Decision`, `Policy`, `Disclosure`, `introduction`), `app/.../PhoneMcp.kt` (`object Guard`), `app/.../Approvals.kt` (`Approvals`, `Journal`, `Outbox`, `Saved`), `app/.../Act.kt`, `app/.../Labels.kt`; callers in `AssistantService.kt` (`askOwner`, `offerUndo`, `tell`, `ApprovalReceiver`) and `MainActivity.kt` (`answer`, `approveIntent`, `confirmOwner`, `setStopped`, `editRule`) |
| Tests | `app/src/test/.../PolicyTest.kt`, `GuardTest.kt`, `JournalTest.kt`, `LabelsTest.kt`, `InboxTest.kt` (saved rules, new defaults, channel settings); `scripts/e2e.sh` (ask with credential, act-and-notify, undo, kill switch, no owner tool reached from a third-party turn) |
| Decisions | [ADR-0003](../adr/0003-permissions-enforced-in-code.md), [ADR-0002](../adr/0002-phone-tools-over-loopback-mcp-per-principal.md), [ADR-0006](../adr/0006-kill-switch-in-the-app.md), [ADR-0007](../adr/0007-third-party-turns-one-at-a-time.md), [ADR-0016](../adr/0016-mcp-key-per-endpoint.md), [ADR-0019](../adr/0019-contacts-by-stable-identity.md), [ADR-0021](../adr/0021-process-state-with-funneled-writes.md), [ADR-0023](../adr/0023-app-files-sealed-to-the-phone.md) |
| Last checked against code | 2026-10-22, commit `64e1087` |

## Purpose

Decides in code how much autonomy each tool call has (never, ask the owner, act and notify, act)
and carries the call out only that far. It implements the [access model](access-model.md); prompts
never grant or deny anything. It is not the MCP transport ([phone-mcp.md](phone-mcp.md)) and not
the tools themselves ([phone-abilities.md](phone-abilities.md)).

## Responsibilities

- `Policy`: principals, capabilities with a fixed risk, rules, `decide()`, daily-limit counters,
  ceilings, the kill-switch flag, contact groups, owner name and form of address.
- `Guard`: the process-wide instance; `gated()`/`attempt()` around every phone tool and every
  notification reply; the outbox and undo; the activity log; persistence of rules and settings.
- `Approvals`: pending `ASK` requests; fail closed after 60 s, with an expired request told apart from a decline.
- `Outbox`: the undo window for outward irreversible actions.
- `Act`: each action's log text, owner-facing title and detail; `Labels`: the policy in the
  owner's words; `introduction()`: what the assistant states it may do, derived from the rules.

## Interfaces

| Name | Input → output | Called by |
|---|---|---|
| `Policy.decide(principal, capability, target)` | → `Decision(autonomy, reason, spends)` | `Guard.attempt`, `introduction` |
| `Policy.restore(saved)`, `Policy.edit(index, autonomy)` | rules clamped to `ceiling(rule)` | `Guard.load`; `MainActivity.editRule`, `set_rule` |
| `Policy.ceiling(rule)`, `Policy.choices(rule)` | highest autonomy; values the rules screen offers | `decide`, `Rules` screen |
| `Policy.spend(key)`, `refund(key)`, `counters()`, `restoreCounters()`, `resetCounters()` | daily-limit counters | `Guard` |
| `Policy.target(contact)` | `Target(contact, groups[contact])` | tools, `MessageListener` |
| `Policy.withNewDefaults(saved)` | saved rules plus default rules for capabilities with none saved | `Guard.load` |
| `Guard.gated(principal, capability, target, act, action)` | runs `action` as far as allowed; returns its text, a `Queued: …` text or a refusal text | every tool handler |
| `Guard.attempt(…)` | same, returning a typed `Outcome` (`Done`, `Queued`, `Refused(notApproved)`) | notification channel, `gated` |
| `Guard.undo(id)` | `Boolean`; refunds the daily use, logs `undone` | Home and floating-mark undo, `ApprovalReceiver` (`undo` extra) |
| `Guard.setStopped(stopped)` | kill switch; saves synchronously; `onStopped` | `MainActivity.setStopped`, debug `stop` extra |
| `Guard.load(dir)` / `save()` / `saveNow()` | `files/policy.json`, `files/activity.jsonl` | `AssistantService`, `MainActivity.onCreate`, `CallActivity` (`PhoneReminders.kt`), `MessageListener`; every setting change |
| `Approvals.request(act, strong)` / `answer(id, approved)` / `find(id)` / `count()` | blocks the caller up to 60 s → `Answer` (`APPROVED`, `DECLINED`, `EXPIRED`) | `Guard.attempt`; `MainActivity.answer`, `approveIntent`, `ApprovalReceiver` |
| Hooks `approvals.onRequest`, `Guard.onNotify`, `onQueued`, `onSaved`, `onStopped`, `secure`, `missing` | set in `AssistantService.onStartCommand` (`askOwner`, `tell`, `offerUndo`, identity rewrite debounced 1.5 s, daemon stop/start, keyguard check) and `MainActivity` (`missing`) | |
| `Labels.capability/group/status/scope/autonomy/rule/tool` | owner-facing strings (`t(R.string.…)`, [ADR-0029](../adr/0029-ui-text-in-android-string-resources.md)) | screens, notifications, approval prompts |
| `introduction(owner, rules, groups, apps, undoSeconds, groupChats, missing)` | self-introduction text built from string resources | `MainActivity.introduce`; `AssistantService` (`TOOLS.md`) |

Notifications (posted by `AssistantService`):

- Approval: channel `approvals-k` (created through `Chime.channel`, high importance), id
  `1000 + pending.id`, actions Approve and Decline. Approve on a strong request opens
  `MainActivity` with a sealed `approve` extra; the other actions go to `ApprovalReceiver` (not exported).
- Undo: channel `outbox-k` (high), id `3000 + item.id`, action Undo, `setTimeoutAfter(undoSeconds)`;
  posted only when neither the app (`Floating.appVisible`) nor the floating mark (`Floating.showing`)
  can show the countdown.

## Configuration

| What | Build time | In the app | By voice | Default |
|---|---|---|---|---|
| Rules (autonomy per rule) | — | Settings › Permissions and privacy › What I can do (`Rules`, credential) | `set_rule` (RULES, credential) | `Policy.DEFAULT_RULES` |
| Kill switch | — | Settings or Home *Pause* / *Resume* (resume: credential); menu | commands "pause" / "resume" ([owner-chat-and-voice.md](owner-chat-and-voice.md)) | off |
| Undo window | — | Settings › Messages › Time to undo (10 / 30 / 60 / 120 s) | `set_preference undo_seconds` (CHANNEL when shorter) | 30 s; floor `PhoneMcp.MIN_UNDO_SECONDS` = 10, cap 120 |
| Contact groups | — | setup and Settings › People (credential) | `set_contact_group` (CONTACT_GROUPS, credential) | none |
| Approval timeout, log size, counters | constants: `Approvals(timeoutMs = 60_000)` (a `var`, shortened only in tests), `Journal.MAX = 500` | — | — | — |

Debug builds only: `--ez stop`, `--ez clean` (counters), `--es contacts` on `MainActivity`.

## State and data

| What | Where | Survives restart |
|---|---|---|
| Rules, `stopped`, `undoSeconds` (default 30, clamped 10–120) | `Guard.policy`; `policy.json` | yes |
| Activity log (last `Journal.MAX` = 500 in memory) | `Guard.journal`; `activity.jsonl`, one sealed JSON line per entry, appended on the IO thread; trimmed to 500 lines when it exceeds 1000 | yes |
| Daily counters `"<date> <PRINCIPAL> <CAPABILITY> <contact>"` | `Policy`; today's in `policy.json` (`counters`) | yes (today's only) |
| `Policy.groups` (empty until setup), `Policy.owner` (default `Milton`), `Policy.address` | companion `@Volatile`; `policy.json` | yes |
| Pending approvals, outbox items | memory (`ConcurrentHashMap`) | no; a queued item never resolved is logged `lost` at the next load (`Journal.unresolved`) |
| `Guard.thirdPartySender`, `undecided`, `toldClash`, `heardOthers` | memory | no |

`policy.json` (`Saved.encode`/`decode`, `org.json`, sealed with the Keystore key per
[ADR-0023](../adr/0023-app-files-sealed-to-the-phone.md)) carries: `rules` (`principal`,
`capability`, `scope` as `all`/`group:x`/`contact:x`, `autonomy`, `perDay` with `-1` for none),
`stopped`, `undoSeconds`, `apps`, `groups` (group chats), `owner` (only once set up), `contacts`
(name → groups), `voice`, `bubble`, `bubblePlace`, `palette`, `address`, `warmth`, `mail`,
`openMic`, `widgets`, `appMode`, `language`, `offline`, `voiceName`, `voiceCeiling`, `counters`,
`version` (2). Version 1 kept the log inside the file; it is migrated once to `activity.jsonl`.
Missing keys take defaults; a rule that does not parse (unknown capability or principal) is
dropped alone. `Guard.load` runs once per process and also loads `seen.txt`, `mail.json`,
`memory.json`, `notes.json` and `style.json`. `save()` writes on one IO thread (`guard-io`) 150 ms
after the first change since the last write (changes within that window coalesce into one write);
`saveNow()` writes synchronously (kill switch). Each write calls `onSaved`.

## Behaviour

**Capabilities and default rules** (`Policy.DEFAULT_RULES`; scope `all` unless written). The
third-party column lists family / work / anyone else; "—" means no tool exposes it to that principal.

| Capability | Risk (ceiling) | Tools | Owner default | Third-party default |
|---|---|---|---|---|
| ALARM_SET | INTERNAL (ACT) | `create_alarm`, `cancel_alarm`, `next_alarm` | ACT | — |
| CONTACTS_READ | PERSONAL_READ (ACT) | `search_contacts` | ACT | — (NEVER in code) |
| CALENDAR_READ | PERSONAL_READ | `list_events`, `free_time` | ACT | — (NEVER) |
| APPS_READ | PERSONAL_READ | `list_apps` | ACT | — (NEVER) |
| EMAIL_READ | PERSONAL_READ | `list_emails`, `read_email` | ACT | — (NEVER) |
| PHOTOS_READ | PERSONAL_READ | `find_photos` | ACT | — (NEVER) |
| FILES_READ | PERSONAL_READ | `find_files` | ACT | — (NEVER) |
| MESSAGES_READ | PERSONAL_READ | `list_messages` | ACT | — (NEVER) |
| APP_OPEN | INTERNAL | `open_app`, `play_media` | ACT | — |
| MEDIA_CONTROL | INTERNAL | `media_control` | ACT | — |
| MEMORY | INTERNAL | `remember`, `forget`, `recall`, `how_well_i_know_you`, `set_address` | ACT | — |
| EMAIL_DRAFT | INTERNAL | `draft_email` | ACT | — |
| CALENDAR_WRITE | INTERNAL | `add_event`, `move_event` | ACT_NOTIFY | — |
| REMINDERS | INTERNAL | `remind`, `list_reminders`, `cancel_reminder`, `move_reminder` | ACT | — |
| NEWS | INTERNAL | `follow_topic`, `unfollow_topic`, `list_topics`, `news`, `find_videos` | ACT | — |
| INTERFACE | INTERNAL | `set_mode`, `show`, `show_on_home`, `remove_from_home`, `open_section` | ACT | — |
| DEVICE | INTERNAL | `device_setting`, `device_status`, `weather` | ACT | — |
| SETTINGS | INTERNAL | `set_preference` (non-widening), `my_settings`, `usage`, `list_rules`, `connect_email` | ACT | — |
| ROUTINES | INTERNAL | `add_routine`, `list_routines`, `remove_routine` | ACT | — |
| CONTACTS_WRITE | INTERNAL | `add_contact`, `edit_contact` | ACT | — |
| ITEMS_MANAGE | INTERNAL | `email_mark_read`, `email_archive`, `act_on` (non-delete) | ACT_NOTIFY | — |
| ITEMS_DELETE | COMMITS_OWNER (ASK) | `email_delete`, `delete_event`, `delete_contact`, `act_on` (delete) | ASK | — |
| CONTACT_GROUPS | COMMITS_OWNER | `set_contact_group` | ASK | — |
| CHANNEL | COMMITS_OWNER | `set_preference` when it widens access (below) | ASK | — |
| RULES | COMMITS_OWNER | `set_rule` | ASK | — |
| MESSAGE_SEND | OUTWARD_IRREVERSIBLE (ASK; ACT_NOTIFY under a limited group or contact rule) | `send_message`, `send_email` | ASK; group `family` ACT_NOTIFY 5/day | — |
| MESSAGE_TAKE | INTERNAL | `take_message` | — | ACT 10/day (scope all) |
| AVAILABILITY_SHARE | OUTWARD_REVERSIBLE (ACT_NOTIFY) | `availability` | — | NEVER / ACT / NEVER |
| NOTIFICATION_REPLY | OUTWARD_IRREVERSIBLE | none: the app's own reply (`MessageListener`) | — | ACT_NOTIFY 5/day / ASK / NEVER |
| CALENDAR_ACCEPT | COMMITS_OWNER | none | no rule: ASK | NEVER |
| CONTACTS_EXPORT | FORBIDDEN (NEVER) | none | NEVER | NEVER |

`set_preference` is gated by CHANNEL instead of SETTINGS (`PhoneMcp.widens`) when it turns on
group chats, email reading or the open microphone, adds a messaging app, lowers the undo window,
or raises or removes a non-zero voice spending ceiling.

`strongApproval` (credential, not a tap): OUTWARD_IRREVERSIBLE and COMMITS_OWNER. `undoable`
(outbox): OUTWARD_IRREVERSIBLE only.

**`decide()`**, under `@Synchronized`, in order:

1. `stopped` → NEVER, reason `kill switch`.
2. FORBIDDEN → NEVER.
3. PERSONAL_READ for a non-owner principal → NEVER, `personal data is the owner's only`.
4. Matching rules: same principal and capability, scope matches (`ALL`; `GROUP` when the group is in
   `target.groups`; `CONTACT` when equal to `target.contact`). Any matching NEVER wins.
5. Otherwise the most specific scope (CONTACT > GROUP > ALL), the most restrictive among equals.
   No matching rule → ASK for the owner, NEVER for a third party.
6. `perDay`: when today's counter for the key is used up → `min(default, rule.autonomy)`, reason
   `limit N/day used`; otherwise the decision carries `spends = key`. The counter is charged in
   `Guard.attempt` only when the action actually runs, and refunded on undo.
7. The result is clamped to `ceiling(rule)`: the risk ceiling, or ACT_NOTIFY for an
   OUTWARD_IRREVERSIBLE rule with a non-`all` scope and a daily limit.

**`Guard.attempt()`**: `decide` → NEVER: not run. ASK: `approvals.request(act, strongApproval)`;
runs if approved. ACT_NOTIFY: runs, then `onNotify(t(R.string.guard_done_x, act.title))` unless
the capability is undoable or the result starts with `NOT `. ACT: runs. Undoable capabilities run
through `queued()`: `outbox.queue(act)`, `onQueued(item)`, and the agent is told
`Queued: it goes out in <undoSeconds> s unless <owner> undoes it.` When the timer fires the action
runs unless `policy.stopped` (then the log says `held back`, reason `kill switch`). A performed,
non-failed, non-undoable, non-PERSONAL_READ action plays `Gesture.DONE` on the mark; a queued item
plays it when sent.

Refusal texts the agent receives:

- strong approval not given on a phone without a screen lock: `Not done: it needs the owner's fingerprint or PIN, and this phone has no screen lock, …`;
- not approved: `Not done: the owner did not approve it (they declined, or did not answer in time). …`;
- not allowed: `Not allowed (<label>). Tell the person it cannot be done now; do not retry.`

**Activity log outcomes**: `act`, `act_notify`, `ask` (approved), `refused`, `not approved`,
`queued (<label>)`, `sent: <result>`, `held back`, `undone` (reason `the owner undid it`), `lost`
(reason `the app stopped during the undo window`), `held` (an `add_event` clash). A tool outside the
caller's catalog is logged `refused`, reason `not a <principal> tool`. `at` is `HH:mm:ss`. Debug
builds also write each entry to logcat (tag `KsandraJournal`), since the file is sealed.

**Approvals**: ids from an `AtomicInteger`; `request` blocks on a `CompletableFuture` for 60 000 ms;
timeout → `EXPIRED`, any other exception → `DECLINED`. `Guard.attempt` turns `EXPIRED` into
`Refused(notApproved = true, expired = true)` and journals "no answer in time"; a decline journals "not approved".
Nothing runs in either case; only an expired request may be offered to the owner again. `askOwner`: with the app open (`MainActivity.prompt`) the request is
shown in-app (`Ui.pending`, the approval sheet, gesture EXPECT, a spoken line when answers are read
aloud); otherwise the notification. The prompt text always comes from the pending request
(`Approvals.find`), never from an intent extra.

**Owner credential** (`MainActivity.confirmOwner`): `BiometricPrompt` with
`BIOMETRIC_STRONG | DEVICE_CREDENTIAL` when a strong biometric is enrolled, `DEVICE_CREDENTIAL`
alone otherwise; success → `true`, error or cancel → `false`. Without a secure screen lock it returns
`true` at once (see Open issues). Used for approving strong requests, resuming, editing a rule and
saving the setup.

**Outbox**: one `ScheduledExecutorService` thread; delay `undoSeconds * 1000` read at queue time;
`undo` and the timer race on one `ConcurrentHashMap.remove`, so an undone item is never sent.

**Kill switch**: `MainActivity.setStopped(true)` is immediate; `false` needs `confirmOwner`.
`Guard.setStopped` saves synchronously and calls `onStopped`, which stops the ZeroClaw daemon
(built-in tools included) and restarts it on resume. Also checked by `PhoneNews` and `CatchUp`
before their turns, and shown as `Presence.PAUSED`. Debug builds: `--ez stop true|false`.

**Other gates in `Guard`**: `undecided` is set when `add_event` reports a clash; while set,
`send_message`, `send_email` and `draft_email` return `HOLD` without deciding. The owner's next
message (`MainActivity.send`) clears `undecided` and `heardOthers`. Groups change only through
`set_contact_group` (ASK, credential) or the setup (`saveSetup`, credential on a secured phone).
Rules are edited (not added or removed) in the rules screen or by `set_rule`, both with the credential.

## Invariants

- Every phone tool and every notification reply passes through `Guard.gated`/`attempt`; nothing in
  a tool handler acts before it (validation, `HOLD` and pre-check answers only).
- The principal comes from the MCP path and its key, never from the model; the third-party tool set
  is `take_message` and `availability` only.
- PERSONAL_READ is never granted to a third party; FORBIDDEN never; no rule exceeds its ceiling after
  `restore`, `edit` or `decide`.
- OUTWARD_IRREVERSIBLE and COMMITS_OWNER approvals require the credential; OUTWARD_IRREVERSIBLE
  always waits the undo window and is re-checked against the kill switch at send time.
- No answer is a no. An undone item is never sent. Refused and undone actions do not consume a
  daily limit.

## Failure modes

- Process death: pending approvals and outbox items are lost; outbox items are logged `lost` on the
  next load. Counters survive (today's).
- Unreadable `policy.json`: `decode` fails, defaults stay in memory, the next `save()` replaces the file.
- No enrolled biometric: the screen-lock credential is asked instead.
- An approval notification is not cancelled when its request times out; a late answer is a no-op.
- An MCP connection blocks up to 60 s per ASK; at most 16 connections at once (see [phone-mcp.md](phone-mcp.md)).

## Security

Principals: OWNER (the owner's agent, the offline agent and the app itself) and THIRD_PARTY (the
third-party agent; notification replies). Untrusted input: tool arguments from the model (contact
names, texts), sender names from notifications (used as `Target.contact`), and intents to the
exported `MainActivity` (only intents carrying the process's seal are honoured). Group membership is
keyed by the saved contact's display name; an incoming sender maps to it only when the
notification's number, address or contact link resolves to that contact, never by the name shown
([ADR-0019](../adr/0019-contacts-by-stable-identity.md)). For owner tools the target is the name the
model gives (`contactName` resolves a phone number to a contact; other text is used as given). The
kill switch covers the phone tools and stops the daemon.

## Screen lock

Without a secure screen lock (`Guard.secure` false) there is no credential to confirm a strong approval:
`Guard.attempt` refuses it before calling `Approvals.request`, and the agent is told a screen lock is
required (covered by `GuardTest`).

## Open issues

- Rules can be edited but not added or removed in the app.
- `Guard.onNotify` is called from inside a gated action, on the MCP thread.
