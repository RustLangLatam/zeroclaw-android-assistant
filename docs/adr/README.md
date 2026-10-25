# Architecture decision records

One decision per file, in the MADR format ([template](_template.md)), numbered in order. Status
values: **Proposed** (written, not yet built), **Accepted**, **Superseded** (by a newer ADR),
**Deprecated**. An accepted ADR is not rewritten; a new ADR supersedes it. The rules for all
documentation are in [ADR-0000](0000-documentation-standards.md).

| ADR | Decision | Status |
|---|---|---|
| [0000](0000-documentation-standards.md) | Documentation standards | Accepted |
| [0001](0001-run-official-zeroclaw-daemon-in-app.md) | Run the official ZeroClaw daemon inside the app | Accepted |
| [0002](0002-phone-tools-over-loopback-mcp-per-principal.md) | Phone tools over a loopback MCP server, one endpoint per principal | Accepted |
| [0003](0003-permissions-enforced-in-code.md) | Permissions are enforced in code, never in prompts | Accepted |
| [0004](0004-secrets-encryption-off.md) | ZeroClaw's secrets encryption is off | Accepted |
| [0005](0005-bind-loopback-ipv4-explicitly.md) | Bind 127.0.0.1 explicitly | Accepted |
| [0006](0006-kill-switch-in-the-app.md) | The kill switch lives in the app | Accepted |
| [0007](0007-third-party-turns-one-at-a-time.md) | Third-party turns run one at a time | Accepted |
| [0008](0008-app-writes-persona-files.md) | The app writes the agents' persona files | Accepted |
| [0009](0009-gateway-only-through-kotlin-client.md) | The gateway only through the Kotlin client | Accepted |
| [0010](0010-zeroclaw-first-voice-first.md) | ZeroClaw first, voice first | Accepted |
| [0011](0011-notification-channel-for-messages.md) | Messages through notifications | Accepted |
| [0012](0012-owner-history-24-messages.md) | The owner's session keeps 24 messages of history | Accepted |
| [0013](0013-memory-in-zeroclaw-with-phone-copy.md) | Memory in ZeroClaw, with a copy on the phone | Accepted |
| [0014](0014-reminders-in-the-app.md) | Reminders are scheduled by the app | Accepted |
| [0015](0015-interface-commands-in-code.md) | Interface commands are understood in code | Accepted |
| [0016](0016-mcp-key-per-endpoint.md) | A key per MCP endpoint | Accepted |
| [0017](0017-splash-melts-into-the-button.md) | The system splash transitions into the presence mark button | Accepted |
| [0018](0018-tool-less-agent-for-unattended-turns.md) | A tool-less agent for turns the owner did not type | Accepted |
| [0019](0019-contacts-by-stable-identity.md) | Contacts by stable identity, never by display name | Accepted |
| [0020](0020-fail-closed-daemon-start.md) | The daemon starts only on a safe loopback | Accepted |
| [0021](0021-process-state-with-funneled-writes.md) | Process-wide state, with its writes funneled | Accepted |
| [0022](0022-what-stays-in-the-app.md) | What stays in the app, ZeroClaw first notwithstanding | Accepted |
| [0023](0023-app-files-sealed-to-the-phone.md) | The app's files sealed to the phone | Accepted |
| [0024](0024-languages-in-kotlin-pairs.md) | Languages as Kotlin pairs, not Android resources | Superseded by [0029](0029-ui-text-in-android-string-resources.md) |
| [0025](0025-an-offline-agent-on-a-local-model.md) | Without internet, an offline agent on a local model | Accepted |
| [0026](0026-google-email-through-zeroclaws-email-channel.md) | The owner's Google email through ZeroClaw's email channel, as an observer | Accepted |
| [0027](0027-assistant-voice-from-a-cloud-speech-model.md) | Assistant voice from a cloud speech model, platform TTS as fallback | Accepted |
| [0028](0028-a-log-of-what-leaves-the-phone.md) | A log of every connection that leaves the phone | Proposed (deferred) |
| [0029](0029-ui-text-in-android-string-resources.md) | UI text in Android string resources | Accepted |
| [0030](0030-service-keys-entered-by-the-owner.md) | Service keys entered by the owner, not built into the APK | Accepted |
| [0031](0031-voice-services-named-in-a-data-file.md) | Voice services named in a data file | Accepted |
