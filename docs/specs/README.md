# Specs

One spec per component, in a fixed format ([template](_template.md)): purpose, responsibilities,
interfaces, state and data, behaviour, invariants, failure modes, security, open issues. Each spec
records the commit it was last checked against; a pull request that changes how a component works
updates its spec ([ADR-0000](../adr/0000-documentation-standards.md)). Status values: **Draft**,
**Stable**, **Changing** (work in progress is changing it).

| Spec | Component | Code |
|---|---|---|
| [access-model.md](access-model.md) | the access model: principals, capabilities, rules, disclosure | `Policy.kt` |
| [policy-engine.md](policy-engine.md) | how the policy is applied: decide, gated, approvals, outbox, undo, log, kill switch | `Policy.kt`, `PhoneMcp.kt` (`Guard`), `Approvals.kt`, `Labels.kt` |
| [assistant-service.md](assistant-service.md) | the foreground service and the daemon: config, workspace files, supervision, pairing, keys | `AssistantService.kt` |
| [phone-mcp.md](phone-mcp.md) | the MCP server and the catalog of every tool | `PhoneMcp.kt` |
| [notification-channel.md](notification-channel.md) | messages from people: listener, inbox, turns, replies, digest | `MessageListener.kt`, `Inbox.kt` |
| [owner-chat-and-voice.md](owner-chat-and-voice.md) | the owner's chat, voice in and out, barge-in, commands, modes | `MainActivity.kt`, `OwnerChat.kt`, `Commands.kt`, `App.kt` |
| [settings-and-setup.md](settings-and-setup.md) | settings by voice, contact groups, the first-run setup | `Preferences.kt`, `MainActivity.kt`, `Screens.kt` |
| [away-and-catch-up.md](away-and-catch-up.md) | detecting an absence and reporting what happened meanwhile | `Absence.kt`, `CatchUp.kt`, `Panorama.kt` |
| [memory.md](memory.md) | the owner's profile, in ZeroClaw and on the phone; the ring's meaning | `Memory.kt`, `PhoneMemory.kt`, `Bond.kt` |
| [reminders.md](reminders.md) | reminders by level; the full-screen call | `Reminders.kt`, `PhoneReminders.kt` |
| [news.md](news.md) | followed topics, headlines, briefings | `News.kt`, `PhoneNews.kt` |
| [email.md](email.md) | email from notifications and connected accounts, importance, drafts, account actions | `Mailbox.kt`, `MailNotice.kt`, `PhoneMail.kt`, `EmailAccounts.kt`, `PhoneEmail.kt` |
| [phone-abilities.md](phone-abilities.md) | apps, device controls, calendar, contacts, alarms, photos, files | `Apps.kt`, `Device.kt`, `Agenda.kt`, `Photos.kt`, `Files.kt` and their `Phone*` |
| [presence-mark.md](presence-mark.md) | the Presence Mark, the splash, the floating mark, the chime | `Theme.kt`, `MainActivity.kt` (`fuse`), `Floating.kt`, `Chime.kt`, `scripts/splash.py` |
| [home-and-widgets.md](home-and-widgets.md) | navigation, Home, widgets, panorama, weather | `App.kt`, `Screens.kt`, `Widgets.kt`, `HomeWidgets.kt`, `PhoneWidgets.kt` |
