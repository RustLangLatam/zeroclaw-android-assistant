# ADR-0026: The owner's Google email through ZeroClaw's email channel, as an observer

| | |
|---|---|
| Status | Accepted |
| Date | 2026-10-19 |
| Deciders | the project's maintainers |
| Related | [spec: email](../specs/email.md), [ADR-0003](0003-permissions-enforced-in-code.md), [ADR-0010](0010-zeroclaw-first-voice-first.md) |

## Context and problem

The app knew email only from its notifications: what never produced one, or was dismissed, could
not be read, and acting was limited to the notification's own buttons. Full access to the owner's
Google email was required, with ZeroClaw first in every integration. ZeroClaw v0.8.5's official
binary has an email channel (IMAP/SMTP) and, when one is enabled, two read-only tools,
`email_search` and `email_read`. But a channel turns every email into an agent turn and answers it
by SMTP, and that answer is not a tool call: it would never pass `Policy.kt`'s approvals or undo
window, and a stranger's email would reach an agent.

## Considered options

1. ZeroClaw's email channel in observer mode, listening to nobody; its read tools for the owner's
   agent only; the app acts (mark read, archive, delete, send) through its own gated tools.
2. ZeroClaw's channel as a channel (answering emails).
3. Google's APIs from the app (OAuth, Gmail REST).

## Decision

Option 1. `[channels.email.<alias>]` (`EmailAccounts.config`) is enabled with `observer_mode = true`
(no flag ever changed), with no peer group or allowlist (no sender is let in) and bound to no agent
(so it is not even started); it exists so ZeroClaw registers `email_search` and `email_read`, which
`allowed_tools` gives to the owner's agent alone. Acting is the app's: `email_mark_read`,
`email_archive` (`ITEMS_MANAGE`), `email_delete` (`ITEMS_DELETE`, asks) and `send_email`
(`MESSAGE_SEND`, asks or the undo window), over IMAP and SMTP with the same account. Sign-in is a
Google app password (2-Step Verification), entered by the owner in the app, sealed on the phone
(Vault) and written into ZeroClaw's config; never seen by the model. Option 2 bypasses the policy;
option 3 needs a Google Cloud project, a 7-day sign-in in testing mode and Google's verification to
go further, and leaves ZeroClaw out. Accounts are connected in the Connectors screen
(`Connectors.kt`).

## Consequences

- Good: ZeroClaw reads the whole inbox with its own tools; nothing is answered or changed without
  the policy; no server.
- Bad / cost: an app password needs 2-Step Verification, and gives full access to the account until
  the owner revokes it; it sits in ZeroClaw's config in plain text in the app's private storage
  (secrets are not encrypted, see the config's `[secrets]` note), like the model's key. The mail
  library adds about 0.7 MB.
- Follow-ups: Outlook (app passwords are no longer available there: XOAUTH2, which ZeroClaw's
  channel supports). New mail as it arrives: ZeroClaw's observer listens only when bound to an
  agent, and then every email is a turn whose answer goes to the sender (observer mode only spares
  the flags), so the app watches the inbox itself, read-only (IMAP IDLE), into the same `Mailbox`
  as notifications (2026-10-19).

## Notes

- The same design now covers any number of accounts (Gmail, Yahoo, iCloud or any IMAP/SMTP server,
  `EmailAccounts`): one observer-mode `[channels.email.<alias>]` section per account, each signed in
  with an app password.
