# Vision and scope

An on-device personal assistant for Android. The official ZeroClaw daemon runs inside the app as the
agent runtime; the app is the owner's interface to it and the only component that can act on the
phone. The feature list with status is in [features.md](features.md); the roadmap is tracked in the
repository's GitHub issues.

## Purpose

- Handle the routine work of a personal assistant (messages, email, calendar, reminders, finding
  photos and files, news) by voice or text.
- Act on the phone and answer other people only within limits the owner sets, enforced in code.
- Keep the owner's data on the device; send off the device only what a requested task needs.

## Principles

1. **Permissions in code, never in prompts.** Every tool call passes `Policy.kt` before it runs:
   capability, risk class, scope, autonomy, daily limit. Prompts and persona files are tone only. A
   third-party turn cannot reach an owner tool ([ADR-0003](../adr/0003-permissions-enforced-in-code.md),
   [access model](../specs/access-model.md)).
2. **Voice first.** Every function, settings included, is reachable by speaking; screens open on
   request. Buttons exist for those who prefer them ([ADR-0010](../adr/0010-zeroclaw-first-voice-first.md)).
3. **ZeroClaw first.** Conversation, agents, memory, sessions, scheduling, email channels and cost
   accounting are ZeroClaw's. The app adds only what requires the phone (notifications, the screen,
   Android permissions, the policy) and reaches ZeroClaw only through the Kotlin client
   ([ADR-0009](../adr/0009-gateway-only-through-kotlin-client.md),
   [ADR-0022](../adr/0022-what-stays-in-the-app.md)).
4. **Data on the device.** Conversations, memory and the app's files stay on the phone; the app's
   files are sealed with a Keystore key ([ADR-0023](../adr/0023-app-files-sealed-to-the-phone.md)).
   What leaves is listed in [storage.md › What leaves the phone](../architecture/storage.md#what-leaves-the-phone)
   and in the app under "Permissions and privacy".
5. **Fail closed.** No answer, no credential, no loopback guarantee or an unknown sender means no
   action.
6. **Disclosure.** When answering other people, the assistant always introduces itself as the
   owner's assistant and never speaks as the owner.

## Autonomy model

Each capability has a rule per principal and scope (all, a group, a contact):

| Level | Behavior | Examples |
|---|---|---|
| never | refused | anything for an unknown sender |
| ask | runs after the owner approves (device credential for outward or committing actions) | a reply to a work contact, deleting an email |
| act and notify | runs and tells the owner | a calendar event, a reply to family (with undo window) |
| act | runs | an alarm, a reminder, a device control |

Risk classes set ceilings that rules cannot exceed: actions that commit the owner stay at **ask**;
outward, irreversible actions go out only after an undo window.

## Scope

### Android reach

| Area | Android access | Use |
|---|---|---|
| Alarms, reminders, calendar, contacts | normal and runtime permissions | read and write |
| Notifications of any app | `NotificationListenerService`, enabled by the owner | read; reply through the notification's `RemoteInput` action |
| Email | ZeroClaw's email channel (IMAP/SMTP) and email notifications | read, mark, archive; delete and send with approval |
| Photos, files | runtime permissions, all-files access for documents | search on the device |
| Device settings | write-settings, notification-policy access; system panels otherwise | brightness, volume, flashlight, do-not-disturb, rotation |
| Overlay, full-screen intents | overlay permission, full-screen notifications | floating mark, high-level reminders |

### Model

| Option | Use |
|---|---|
| Cloud provider (any provider type ZeroClaw supports) | default for all agents; set in `local.properties` |
| On-device model through `llama-server` | optional `offline` agent when there is no connectivity ([ADR-0025](../adr/0025-an-offline-agent-on-a-local-model.md)) |

ZeroClaw sees both as chat-completions providers; the local one at `http://127.0.0.1:42620/v1`.

## Non-goals

- **Answering or speaking in regular phone calls.** Android does not let apps capture or inject call
  audio (`CAPTURE_AUDIO_OUTPUT` is reserved for system apps). The "call" for high reminders is an
  in-app full-screen screen, not telephony.
- **Being the default SMS app.** Required by Google Play to send SMS; it would replace the owner's
  messaging app.
- **Driving other apps through `AccessibilityService`.** Deferred: the riskiest option and heavily
  restricted; an ADR must first decide whether it can be made safe.
- **Speaking as the owner.** Replies always disclose the assistant.
- **Server components.** Everything runs on the phone; the only remote services are the configured
  model and speech providers, the connected email servers and the public data sources listed in
  storage.md.
- **Prompt-based permissions.** No capability is granted or restricted by instructions to the model.

## Risks

- **Experimental target:** ZeroClaw's Android build is marked experimental; the version is pinned
  (v0.8.5) and re-checked on upgrade ([zeroclaw.md](../architecture/zeroclaw.md)).
- **Battery and Doze:** the foreground service keeps the process alive, but Doze can delay inexact
  alarms and scheduled turns.
- **Memory:** the daemon is small; the optional local model needs about 1 GB on disk and more in RAM,
  which limits offline answers to mid- and high-end phones.
- **Distribution policy:** the foreground service type (`specialUse`), notification access, all-files
  access and full-screen intents need justification on app stores.
- **Secrets:** the model key is in the APK and in plain text in `config.toml`
  ([security.md › Known gaps](../architecture/security.md#known-gaps)).
