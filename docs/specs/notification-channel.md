# Spec: notification channel

| | |
|---|---|
| Status | Stable |
| Code | `app/.../MessageListener.kt` (`MessageListener`, `sender`, `saved`, `contactName`, `Turns`), `app/.../Inbox.kt`, `app/.../Identity.kt`; uses `Guard.attempt` (`PhoneMcp.kt`), `Outbox` (`Approvals.kt`), `AssistantService.digest`; debug extras in `MainActivity.debugIntent`; `fakechat/` |
| Tests | `app/src/test/.../InboxTest.kt` (`onlyNewMessagesFromOthersAreTaken`, `otherAppsAndGroupChatsAreIgnoredByDefault`, `theFirstReplyInAConversationIntroducesTheAssistant`, `anUndoneReplyIsMarkedInTheDigest`, `repliesFollowTheContactsRules`, `seenMessagesSurviveARestart`, `theHistoryOnThePhoneBeforeTheFirstStartIsNotAnswered`, `theIntroductionNamesTheOwner`), `IdentityTest.kt`, `BackOnlineTest.kt`; `scripts/e2e.sh` (all of it) |
| Decisions | [ADR-0011](../adr/0011-notification-channel-for-messages.md), [ADR-0007](../adr/0007-third-party-turns-one-at-a-time.md), [ADR-0003](../adr/0003-permissions-enforced-in-code.md), [ADR-0009](../adr/0009-gateway-only-through-kotlin-client.md), [ADR-0019](../adr/0019-contacts-by-stable-identity.md), [ADR-0021](../adr/0021-process-state-with-funneled-writes.md), [ADR-0023](../adr/0023-app-files-sealed-to-the-phone.md) |
| Last checked against code | 2026-10-22, commit `64e1087` |

## Purpose

Lets people who write to the owner through messaging apps reach the assistant: it reads their
messages from the apps' notifications, hands each to the `third_party` agent, and sends the agent's
answer back through the notification's own reply action, only as far as the policy allows. It is
the only channel for third parties ([access model › Notification channel](access-model.md#notification-channel)).
It does not start new conversations (the owner's `send_message` is described in
[access-model.md](access-model.md#as-built)), and email notifications are routed to `Mailbox`
([email.md](email.md)).

## Responsibilities

- Listen to notifications (`NotificationListenerService`), keep each conversation's reply action
  and its plain action buttons.
- Pick the new messages from other people in allowed apps (`Inbox.accept`), never twice.
- Resolve each sender against the owner's contacts (`sender`, `Identity`); never trust the display name.
- Run third-party turns one at a time, with a timeout (`Turns.thirdParty`).
- Gate the reply (`NOTIFICATION_REPLY`), introduce the assistant on the first reply in a
  conversation, fire the reply action, and keep the owner's digest (`Inbox.digest`) with each
  message's status.
- Take down the source app's notification while the app is on screen (`takeOver`).
- Retry messages left unanswered by a connection cut (`MessageListener.retry`).

## Interfaces

| Name | What | Who |
|---|---|---|
| `MessageListener` service | `BIND_NOTIFICATION_LISTENER_SERVICE`; enabled by the owner in Android's notification access settings | Android |
| `onListenerConnected()` | `Guard.load(filesDir)`, sets `retry`, starts `AssistantService`, replays `activeNotifications` through `onNotificationPosted` | Android |
| `onNotificationPosted(sbn)` | email → `mail()`; otherwise MessagingStyle → `Inbox.accept` → one turn per new message | Android |
| `onNotificationRemoved(sbn)` | drops the reply action unless the app took the notification down itself | Android |
| `Inbox.accept(app, conversation, group, raw)` | → new `Message`s | listener |
| `Inbox.received(message)` → `Entry`; `status(entry, s)`; `undone(act)`; `heldBack(act)`; `unread()`; `missed(since)` | the digest | listener; `Guard`; Activity, Home, widgets, floating mark, `CatchUp` |
| `Inbox.withIntro(conversation, text)`, `Inbox.english(text)` | prefixes the introduction in the answer's language | listener |
| `Turns.thirdParty(context, sender, text, done)` | thread; `done(answer or null)` | listener; `retry`; debug `--es incoming … --es from …` |
| `Turns.open(context, agent, session, onFailure, onFrame)` | `/ws/chat` through the client (`AssistantService.client(token).chat`) | `thirdParty`, `scribe`, the owner chat |
| `MessageListener.replyTo(context, conversation, text)` | sends through a conversation's reply action | `send_message` (owner) |
| `MessageListener.buttonsOf(key)`, `press(context, key, what)` | a notification's plain buttons (e.g. archive, mark as read) | owner tools |
| Third-party MCP tools | `take_message` (`MESSAGE_TAKE`), `availability` (`AVAILABILITY_SHARE`), target `Policy.target(Guard.thirdPartySender)` | `third_party` agent |
| `Inbox.onChange` | → `AssistantService.digest()`: cancels the old digest notification (id 4000), `Floating.update`, `Ui.refresh` | |

## Configuration

| What | Build time | In the app | By voice | Default |
|---|---|---|---|---|
| Notification access | — | Android's notification-listener settings; the setup's permissions step and Home's *Fix* (`openListener`) | — | off (Android) |
| Apps read (`Inbox.apps`) | — | setup step 2; Settings › Messages › Apps I read | `set_preference messaging_apps` ("add WhatsApp": CHANNEL; "remove …": SETTINGS) | none |
| Group chats (`Inbox.groups`) | — | the same picker | `set_preference group_chats` (CHANNEL when turned on) | off |
| Who is answered and how | — | rules and groups ([access-model.md](access-model.md)) | `set_rule`, `set_contact_group` | family ACT_NOTIFY 5/day, work ASK, others never |
| Undo window | — | Settings › Messages › Time to undo | `set_preference undo_seconds` | 30 s |
| Constants | `MAX_SEEN` = 2000, `MAX_DIGEST` = 200, `THIRD_PARTY_MS` = 180 000, `OWNER_MS` = 120 000 (scribe), echo window 20 s, `Inbox.DEFAULT_APPS` | | | |

No `local.properties` key affects this channel. Debug builds: `--es apps`, `--ez groups`,
`--es contacts`, `--es incoming --es from` on `MainActivity`.

## State and data

| What | Where | Survives restart |
|---|---|---|
| Seen keys `"<sbn.key> <message time>"`, ≤ `MAX_SEEN` = 2000 | memory + `files/seen.txt` (sealed; first line `since <ms>`, one key per line, appended; rewritten on load and after 2000 appends) | yes |
| `since`: when the file was created | first line of `seen.txt`; `now` if missing | yes |
| `Inbox.apps` (empty until the setup is saved), `Inbox.groups` (default `false`) | `policy.json` (`apps`, `groups`) | yes |
| Digest `Entry(id, at "HH:mm", sender, text, status, app, time, conversation)`, ≤ `MAX_DIGEST` = 200; `readUpTo` | `files/digest.json` (sealed), written on one writer thread | yes |
| Introduced conversations | `files/introduced.txt` (sealed) | yes |
| `replies` (key → `PendingIntent` + `RemoteInput`), `buttons`, `taken`, `answered` (key → last reply ms) | `MessageListener` companion, memory | no |
| `Guard.thirdPartySender` | memory | no |

`Entry.status` holds an `Inbox.Status` constant: `received`, `answered`, `waiting`, `queued`,
`paused`, `retrying`, `no_answer`, `could_not_answer`, `undone`, `not_approved`, `held_back`.
Values saved by earlier versions in Spanish are mapped to these on load (`Inbox.Status.migrate`).

`Inbox.DEFAULT_APPS` (offered in the setup): `com.whatsapp`, `com.whatsapp.w4b`,
`org.telegram.messenger`, `org.thoughtcrime.securesms`, `com.google.android.apps.messaging`,
`com.samsung.android.messaging`. The owner chooses apps and group chats in the setup or by voice
(`set_preference messaging_apps`, `group_chats`; widening them is `CHANNEL`, which asks).

## Behaviour

**Parsing** (`onNotificationPosted`): email first (`isEmail`: not this app, not a group summary,
`CATEGORY_EMAIL` or a package in `Mailbox.EMAIL_APPS`). Otherwise `EXTRA_MESSAGES` via
`MessagingStyle.Message.getMessagesFromBundleArray`; each becomes `Inbox.Raw(sender, text, time, who)`
with sender `null` when the message has no sender person or its name equals
`EXTRA_MESSAGING_PERSON` (the owner, as some apps name them), and `who` the sender person's URI or
key. No messages → ignored. `EXTRA_IS_GROUP_CONVERSATION` marks a group chat. The conversation is
`sbn.key`.

**Accept rules** (`Inbox.accept`, synchronized): app in `apps`; not a group chat unless `groups`;
sender not `null`; text not blank; `time >= since` (history from before the first start is never
answered); key not seen. New keys are appended to `seen.txt` before the turn runs (a crash loses a
message rather than answering it twice). A notification is reposted with the whole history, so
only the new tail passes.

**No new messages**: if the app replied in this conversation in the last 20 s (`answered`), the
notification is the source app echoing that reply: it is marked `taken` and cancelled.

**New messages**: the reply action is the first action with a `RemoteInput` that allows free-form
input (kept in `replies[sbn.key]`, removed if none); plain buttons are kept under `msg:<key>`;
`Gesture.NOTICE`; `takeOver` (only with `Floating.appVisible`: cancel the notification, keep its
reply action, `Chime.play`). For each message:
- `sender(context, name, who)`: `Identity.clue` extracts a number (`tel:`, WhatsApp's
  `<number>@s.whatsapp.net`, or a name that is a phone number), an address (`mailto:`) or a contacts
  URI; `saved` looks it up in the contacts (needs `READ_CONTACTS`). A match gives the contact's
  name; anyone else keeps the given name with the unverified suffix (`Identity.UNVERIFIED`, from
  string resources) and belongs to no group.
- `Inbox.received` (status `received`).
- Kill switch on: no turn; journal "kill switch: paused", status `paused`.
- Else `Turns.thirdParty`.

**Turns** (`Turns.thirdParty`): a new thread acquires `Semaphore(1)`, sets
`Guard.thirdPartySender`, opens the `third_party` agent in a new session (waits for a token,
polling every 500 ms) and on `SESSION_START` sends:
`"<sender> wrote to <owner>:\n<text>\n\n(Your answer goes to <sender>.)"`. `DONE` →
`fullResponse`; `ERROR`, a failed open, or a closed stream → `null`; after `THIRD_PARTY_MS` =
180 000 ms without an answer the job is cancelled → `null` (the timeout is scheduled on a shared
timer, not a sleeping thread). `finish` runs once per turn, clears `thirdPartySender` and releases the
semaphore before calling `done`, so a reply waiting for the owner's approval does not block the next
turn. A 401 at open makes the app pair again (`AssistantService.pair(refused = token)`).
`Turns.scribe` (unattended text) times out after 120 000 ms and does not take the semaphore.

**Reply** (`MessageListener.answer`):
1. Blank or `null` answer → status `no_answer`. Else `waiting`.
2. `Inbox.withIntro`: on the first reply in a conversation, prefix the introduction (`inbox_intro`:
   "Hi, I'm <assistant>, <owner>'s assistant.") unless the answer already contains the assistant's
   name or "<owner>'s assistant" in any language (`inbox_x_s_assistant`). The introduction's language
   follows the answer: English when `Inbox.english` counts more common English than Spanish words
   (`inbox.common_words.*` in `assets/lexicon.json`), else Spanish. The third-party agent itself is
   told to answer in the language of the message.
3. `Guard.attempt(THIRD_PARTY, NOTIFICATION_REPLY, Policy.target(sender), Act.reply(entry.id, sender, app label, text))`.
   Defaults: family `ACT_NOTIFY` 5 a day per contact, work `ASK` (strong: *Approve* then the
   device credential), anyone else `NEVER` (the message is taken, no reply).
4. Allowed → always queued in the `Outbox` (`OUTWARD_IRREVERSIBLE` is undoable): status `queued`;
   the owner sees the countdown with *Undo* (Home, beside the floating mark, or the `outbox`
   notification). After `undoSeconds` (default 30) the action fills the `RemoteInput`, sets
   `SOURCE_FREE_FORM_INPUT`, sends the `PendingIntent`, records `answered[key]`, and sets
   `answered` or `could_not_answer` (with `Guard.onNotify`). Undo → journal `undone`, status
   `undone`, the daily use refunded. Kill switch at send time → status `held_back`.
5. Refused → `not_approved` (an `ASK` not approved) or `no_answer`.

The reply goes to the conversation the message came from; the model never chooses the recipient.

**Retry** (`MessageListener.retry`, used by `CatchUp` after a connection cut): an entry with status
`no_answer` and a known conversation is set to `retrying` and run through `Turns.thirdParty` again.

**What `scripts/e2e.sh` checks** (emulator only; `fakechat` posts MessagingStyle notifications
with a reply action and a `tel:` person for known numbers, and records replies in its
`files/replies.txt`; notification access granted with `cmd notification allow_listener`; setup
through debug extras `--es owner Milton --es apps <fakechat> --ez groups false --ez stop false
--ez clean true --es language es --ez voice false --es contacts Laura:work,Ana:family`):

| Case | Check |
|---|---|
| Laura (work) asks | *Approve* + PIN, sent after the window; the reply starts with the introduction naming Milton |
| Ana (family) | answered without asking, after the window |
| Pat (unknown) | no reply |
| A stranger named "Ana" without a number | no reply; logged as unverified (the display name is not an identity) |
| Injection from Ana | no owner tool called (logcat); no calendar text in replies |
| Group chat | not handled |
| Undo within the window | never sent; journal has `undone` |
| Force-stop and restart | only the new message answered (seen keys survive) |
| Kill switch (`--ez stop true`) | no reply; journal reason `kill switch` |

## Invariants

- A message is handed to an agent at most once (`seen.txt` written before the turn), except an
  explicit retry of an unanswered one.
- Only the `third_party` agent handles messages; its tools are `take_message` and `availability`.
- Nothing is sent to a person in reply except through `Guard.attempt(NOTIFICATION_REPLY)` and the outbox.
- One third-party turn at a time; `thirdPartySender` is the sender of the turn holding the
  semaphore while it runs, and `null` otherwise (a late tool call does nothing).
- The owner's own messages, other apps, group chats (by default) and history before the first
  start are never answered.
- A sender's group comes only from a saved contact resolved from the notification's data.

## Failure modes

- No notification access: nothing is read; the setup's introduction lists it as missing (`MainActivity.missing`).
- Notification access turned off later (by the owner, a vendor update or a battery manager): `onListenerDisconnected`
  calls `Health.check`, which posts "I can't read your messages" within seconds; the notice opens the app, whose Home
  shows how to restore it. While access is still granted, the listener asks to be rebound (`requestRebind`).
- Notification removed by the user or the source app before the reply goes: no reply action →
  `could_not_answer`, and the owner is told.
- Gateway down or not paired: the turn waits for a token up to 180 s, then `no_answer`; after a
  connection cut it is retried while its notification still has a reply action.
- Process death: reply actions, queued replies and pending approvals are lost; items left in the undo
  window are logged as lost at the next start. The digest, read position and introduced
  conversations are kept; `seen.txt` keeps the messages from being answered again.
- Sender without a resolvable number, address or contact link: kept by display name, marked unverified.

## Security

Principal: `THIRD_PARTY` for the agent and for the reply. Untrusted input: message text (framed as
data in the prompt and in the third-party `AGENTS.md`; the agent has no owner tools, no shell,
files, memory or web), sender display names (never used for group membership), notification
extras. The reply target comes from the notification, not from the model. Logs keep message content
only in debug builds (`AssistantService.logged`). While paused, no turn starts.

## Open issues

- Without a screen lock, work replies (strong approvals) are refused before asking (see
  [access-model.md › Open issues](access-model.md#open-issues)).
- A stalled gateway costs a third-party turn up to its timeout (180 s) until the gateway watchdog restarts the daemon
  (at most about 2 minutes; [assistant-service.md](assistant-service.md#failure-modes)).
