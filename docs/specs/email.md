# Spec: Email

| | |
|---|---|
| Status | Stable |
| Code | `app/.../Mailbox.kt`, `app/.../MailNotice.kt`, `app/.../PhoneMail.kt`, `app/.../EmailAccounts.kt`, `app/.../PhoneEmail.kt`, `app/.../Connectors.kt`; intake in `MessageListener.kt` (`isEmail`, `mail`); tools in `PhoneMcp.kt`; opening in `MainActivity.kt` (`mailIntent`, `openMail`, `connectEmail`, `setMail`); Home in `Screens.kt`, `HomeWidgets.kt` |
| Tests | `app/src/test/.../MailboxTest.kt`, `EmailAccountsTest.kt`; `scripts/e2e.sh`: none (manual with `fakechat`'s `Mail` receiver and draft activity) |
| Decisions | [ADR-0026](../adr/0026-google-email-through-zeroclaws-email-channel.md), [ADR-0003](../adr/0003-permissions-enforced-in-code.md), [ADR-0018](../adr/0018-tool-less-agent-for-unattended-turns.md), [ADR-0019](../adr/0019-contacts-by-stable-identity.md), [ADR-0023](../adr/0023-app-files-sealed-to-the-phone.md) |
| Last checked against code | 2026-10-22, commit `64e1087` |

## Purpose

The owner's email, from two sources. From email apps' notifications the app keeps sender, subject
and preview, tells the owner discreetly when one looks important, reads them to the owner on
request, and writes drafts that open ready in an email app for the owner to send. With email
accounts connected (any number: Gmail, Yahoo, iCloud or any IMAP and SMTP server;
[ADR-0026](../adr/0026-google-email-through-zeroclaws-email-channel.md)), ZeroClaw reads each whole
inbox, the app hears of new email at once over IMAP IDLE, and the app acts on it: marks read,
archives, deletes and sends, each through the policy.

## Responsibilities

- Recognize email notifications and keep each email once (`Mailbox.receive`); merge the same email
  seen over IMAP and as a notification.
- Decide importance and why, in code (`Mailbox.importance`).
- Announce important ones (`MailNotice.announce`) and, when opened, show the email where the owner
  reads email (`MainActivity.openMail`).
- Answer `list_emails` and `read_email`; open drafts in the owner's email app (`PhoneMail.draft`).
- Keep the connected accounts (`EmailAccounts`), write their ZeroClaw channels, watch their inboxes
  and perform the account actions (`PhoneEmail`).

## Interfaces

MCP tools, owner endpoint only:

| Tool | Args | Policy | Does / returns |
|---|---|---|---|
| `list_emails` | `only_important` | `EMAIL_READ` (`PERSONAL_READ`) | `Mailbox.describe`: latest 15, newest first, `#id (uid N · alias) from Name <addr>: "subject" — looks important: why`, framed as data from the sender; `The owner turned off reading their email.` when off |
| `read_email` | `id` | `EMAIL_READ` | `Mailbox.read`: sender, subject, body (or subject if empty), marked as data from the sender, never instructions; reading off → refused; unknown id → `No such email; use list_emails.` |
| `draft_email` | `body` (required), `to`, `subject`, `reply_to` | `EMAIL_DRAFT` (`INTERNAL`) | `PhoneMail.draft`; held (`HOLD`) while `Guard.undecided` (an unanswered calendar clash) |

With email accounts connected (`EmailAccounts.accounts`; each tool's `account` is an alias or
address; blank resolves to the only account when there is one, else the agent is told the list):

| Tool | Args | Policy | Does / returns |
|---|---|---|---|
| `email_search`, `email_read` | ZeroClaw's own (`channel` = the account's alias) | ZeroClaw's tools, in the owner agent's `allowed_tools` only while an account exists | an inbox by sender, subject, dates, unread; one email by UID; observer mode never sets a flag |
| `connect_email` | `provider` (`gmail`, `yahoo`, `icloud`, `correo` (the generic provider), or none) | `SETTINGS` | opens Connectors: that provider's guide as a card, then its form; without one, the list of providers. The password is typed by the owner, never given to the agent |
| `email_mark_read` | `uids`*, `read`, `account` | `ITEMS_MANAGE` (`ACT_NOTIFY`) | `PhoneEmail.markRead` |
| `email_archive` | `uids`*, `account` | `ITEMS_MANAGE` | Gmail: flag deleted in the inbox (Gmail removes the inbox label); others: moved to the `\Archive` folder (RFC 6154), "NOT done" without one |
| `email_delete` | `uids`*, `account` | `ITEMS_DELETE` (asks, credential) | moved to the folder marked `\Trash` |
| `send_email` | `to`*, `body`*, `subject`, `reply_to`, `account` | `MESSAGE_SEND` (asks, or the family rule; undo window) | SMTP from that account (465 implicit TLS, else STARTTLS required); `reply_to` (a UID) sets `In-Reply-To` and `References`; `to` a name → `PhoneMail.addressOf`; held while `Guard.undecided` |

UIDs: at most 50 per call, comma- or space-separated. A failure starts "NOT", so no completion
notice and no gesture follow.

Providers (`EmailAccounts.Provider`): Gmail, Yahoo (an app password of 16 letters) and iCloud (an
app-specific password, grouped with dashes) need only the address and the password; the generic
provider (`OTHER`) asks for its IMAP and SMTP servers too (IMAP 993; SMTP 465, or 587 with STARTTLS).
Each has its own guide (`EmailAccounts.guide`), shown as a card before its form. An account's alias
is the provider's key, numbered after the first (`gmail`, `gmail2`); ZeroClaw has one
`[channels.email.<alias>]` per account (`enabled`, `observer_mode = true`, `imap_host`,
`imap_port`, `imap_folder = "INBOX"`, `smtp_host`, `smtp_port`, `smtp_tls` = the SMTP port is 465,
`username`, `password`, `from_address`). No POP: ZeroClaw's channel is IMAP only. The app's own
IMAP and SMTP calls use 15 s to connect and 20 s per operation.

Other interfaces:

- In: `MessageListener.onNotificationPosted` → `mail(sbn)` when `isEmail`: not this app, not a group
  summary, and `CATEGORY_EMAIL` or a package in `Mailbox.EMAIL_APPS` (Gmail, Outlook, Proton Mail,
  Yahoo Mail, Samsung Email, BlueMail, K-9).
- Notice: channel `important-k` (`Chime.channel("important", …)`, default importance, the chime
  as sound), id and request code `7000 + mail.id`; tap → `MainActivity` with a sealed extra
  `mail = id`.
- `MailNotice.onImportant`: set by `MainActivity` to remark aloud (`Phrases.mail`).
- `MessageListener.mailLinks`: each email's notification content intent, kept in memory.
- Settings: `Mailbox.reading` via the Settings toggle (`MainActivity.setMail`) or
  `set_preference read_mail on|off` (turning it on is `CHANNEL`); persisted in `policy.json`.
- Connectors screen: Settings › Connectors › Email lists the accounts (reachable only through the
  assistant, or also in an email app on the phone), adds one, or disconnects one.
- Readers: Home's important-email moment and panorama line (latest important unopened under 12 h),
  the `MAIL` widget (unopened count; warm when any is important; tap asks which emails are unread),
  the mark's `BUSY` load, `CatchUp` (emails while away).

## Configuration

| What | Build time | In the app | By voice | Default |
|---|---|---|---|---|
| Email from notifications (`Mailbox.reading`) | — | Settings › Messages › Read your email (`setMail`) | `set_preference read_mail` (CHANNEL when turned on) | on |
| Which notifications count as email | constant `Mailbox.EMAIL_APPS` plus `CATEGORY_EMAIL`; needs notification access ([notification-channel.md](notification-channel.md)) | — | — | — |
| Connected accounts | — | Settings › Connectors › Email (`Connectors.kt`, `connectEmail`); the password is typed there | `connect_email` opens that screen | none |
| Importance keywords | `assets/lexicon.json` (`mail.bulk`, `mail.urgent`) | — | — | — |
| Limits | constants: 100 mails kept, 1000 seen keys, 15 min merge window, 2000-character bodies over IMAP, 50 UIDs per call, IDLE socket 25 min, backoff 5 s–5 min | | | |

No `local.properties` key is involved: ZeroClaw's email channels are written from the saved
accounts by [AssistantService](assistant-service.md).

## State and data

| What | Where | Format |
|---|---|---|
| Emails | `files/mail.json`, sealed ([ADR-0023](../adr/0023-app-files-sealed-to-the-phone.md)) | `{next, mails: [Mail(id, app, from, address, subject, body, at, important, why, uid, account)], opened: [id], seen: ["<key> <when> <subject>"]}`; at most 100 mails (oldest dropped), at most 1000 seen keys |
| Accounts | `files/email_accounts`, sealed | JSON list: alias, provider, address, password, IMAP and SMTP host and port, `onPhone`; a legacy single `gmail` file is read once and replaced |
| ZeroClaw channels | `config.toml` (`[channels.email.<alias>]`, with the password) | written by `AssistantService` |
| Reading switch | `policy.json` (`mail`) | boolean |
| Notification links | memory (`MessageListener.mailLinks`) | lost on process death |

Ids continue from the stored counter, so an old notice never resolves to a different email. Drafts
live in the email app.

## Behaviour

**Intake from a notification** (`MessageListener.mail`): title = `EXTRA_TITLE`, subject =
`EXTRA_TEXT`, body = `EXTRA_BIG_TEXT` minus the subject prefix. `receive` returns `null` when
reading is off, the title is blank, or the same key, time and subject were seen. Then the `PERK`
(important) or `READ` gesture, `Ui.refresh`, `takeOver` (app visible: the email app's notification
is cancelled and the chime plays), and `MailNotice.announce` if important.

**Intake from an inbox** (`PhoneEmail.watch`, started by the service and after connecting): one
daemon thread per account keeps an IMAP connection with the inbox read-only (`mail.imaps.peek`),
waits in IDLE (the socket times out at 25 min, as servers end IDLE near 29, and it reconnects;
failures back off from 5 s to 5 min; waits while `Offline.online` is false; a changed password
reconnects, a removed account ends the thread), and passes each new message to `Mailbox.receive`
with its UID and account (body: the first `text/plain` part, else HTML with tags stripped, cut to
2000 characters), then the gesture and `MailNotice.announce` when important.

**Merge.** The same email from an inbox and from an email app's notification is one (same subject,
same sender name or address, within 15 min): the kept entry gets the UID, and that account is marked
as on the phone (`Mailbox.onPhone` → `EmailAccounts.seenOnPhone`). Android does not let an app see
the phone's Google accounts, so this is how the app learns it. Until then the account is reachable
only through the assistant, and opening one of its emails shows it as a card.

**Sender** (`Mailbox.sender`): `"Name" <addr>` → name (or the address if blank) and address; a bare
address (has `@`, no space) → both; else the title as name, no address.

**Importance** (`Mailbox.importance(contact, subject, body, Policy.groups)`):
1. Person = the saved contact the sender's address resolves to (`saved(Identity.Clue.Email)`), and
   its groups; never the display name. An email app that shows only names gets no family or work.
2. Bulk keywords and no person → not important.
3. Urgent keywords, case-insensitive, in subject + body.
4. The reason names the contact and group, the keyword, or both (string resources); neither → not important.

Keywords are data in `assets/lexicon.json` (`mail.bulk`, `mail.urgent`), in Spanish and English.

**Notice** (`announce`): `onImportant`; app visible → chime only; else a floating notice
("Email from <first name> · looks important") and a notification titled "An email from <first name>
looks important", subject as text, reason in big text.

**Opening** (`mailIntent`, Home's *Read*, the panorama's line with `MailNotice.OPEN`):
`MainActivity.openMail` marks the id opened, then shows the email itself. An account reachable only
through the assistant → a card with sender and body. Otherwise the email's own notification tap
(`mailLinks`; on Android 14 and later with background activity start allowed), else the email app's
launcher, else a dialog with subject, sender and body. Nothing is read aloud and no agent turn runs;
the owner agent reads an email only when asked (`read_email`, which marks the turn as having read
third-party content, `Guard.heardOthers`).

**Draft** (`draft_email` in `PhoneMcp.kt`): `to` = argument, else the replied email's sender; if it
contains `@` it is used as is; else the replied email's address when `to` is blank or its sender;
else `PhoneMail.addressOf(name)`; none → `No email address for "…" in the owner's contacts; ask the
owner for it.` Subject = argument, else `Re: <subject>` of the replied email (no double `Re:`).
Preferred app = the replied email's app, else the latest email's app.

`PhoneMail.draft`: app = the preferred package if it handles `mailto:`, else the default `mailto:`
handler (not the chooser), else the most used handler (`Apps.rank`), else any handler; none →
`No email app installed.` Opens `ACTION_SENDTO mailto:` with `EXTRA_EMAIL`, `EXTRA_SUBJECT`,
`EXTRA_TEXT`; returns `The email to … is written and open in <app>; the owner reads it and sends it.`
`addressOf(name)`: the first `Email.ADDRESS` whose display name is `LIKE %name%`; `null` without
`READ_CONTACTS`.

**Connecting** (`MainActivity.connectEmail`): `PhoneEmail.check` signs in to the account's IMAP
first (a rejected password or an unreachable server is reported plainly), then `EmailAccounts.save`,
`AssistantService.reload` (the daemon restarts and its config is rewritten because its channels
differ) and `PhoneEmail.watch`.

**Simulated** (`fakechat`, debug only): the `Mail` receiver posts an email notification
(`CATEGORY_EMAIL`; extras `from`, `subject`, `text`); its `mailto:` activity records each draft in
`files/mail-out.txt` (`to\tsubject\tbody`) and never sends.

## Invariants

- A draft is never sent by the app: it opens in the owner's email app and the owner sends it.
- An email is sent only by `send_email`, through `Guard` (`MESSAGE_SEND`, approval or family rule, undo window).
- Email content reaches the agent marked as data from the sender, never instructions; it cannot
  confirm a memory (`Guard.heardOthers`).
- Only the owner principal reaches the email tools; `EMAIL_READ` is `PERSONAL_READ`, never a third party's.
- Reading off → new emails are not kept, and `list_emails` and `read_email` refuse.
- The same notification posted again is the same email; the same email from two sources is one.
- Account passwords are typed by the owner in the app and never reach the model.

## Failure modes

- Email apps that put the subject in the title or truncate previews: sender and body are wrong or short.
- No contact address: the tool asks the agent to ask the owner. No email app: the tool says so.
- `startActivity` failure: `Could not open <app>: …`.
- Process death: notification links are lost; opening falls back to the email app's launcher or a dialog.
- IMAP or SMTP errors: `NOT done: <address> did not answer (…)` / `NOT sent: … did not take it (…)`.
- No `\Archive` folder: archive reports it and suggests marking read or deleting; no `\Trash` folder: delete fails.

## Security

- Principals: owner agent only; default rules `ACT` for `EMAIL_READ` and `EMAIL_DRAFT`,
  `ACT_NOTIFY` for `ITEMS_MANAGE`, `ASK` for `ITEMS_DELETE` and `MESSAGE_SEND` (family `ACT_NOTIFY`, 5 a day).
- Untrusted input: sender name, subject and body come from anyone who can email the owner.
  Importance by group requires the sender's address to resolve to a saved contact.
- The notice's intent carries the process's seal; `MainActivity` ignores `mail` extras without it.
- Account passwords are stored sealed on the phone and in plain text in `config.toml` (app-private).

## Open issues

None known.
