# ADR-0004: ZeroClaw's secrets encryption is off

| | |
|---|---|
| Status | Accepted |
| Date | 2026-10-16 |
| Deciders | the project's maintainers |
| Related | upstream issue zeroclaw-labs/zeroclaw#11525, [spec: assistant service](../specs/assistant-service.md), [ADR-0023](0023-app-files-sealed-to-the-phone.md) |

## Context and problem

ZeroClaw publishes its secret key file with `link(2)`, which SELinux denies to apps. With
encryption on, pairing cannot save its token.

## Considered options

1. `[secrets] encrypt = false`, with the config in app-private storage.
2. Patch ZeroClaw.
3. Run without pairing.

## Decision

Option 1 until upstream fixes the issue.

## Consequences

- Good: pairing works with the official binary.
- Bad: `config.toml` holds the model key and the MCP keys in plain text in private storage; device
  backups would copy them.
- Follow-up: turn encryption on when the upstream fix ships; keep backups off meanwhile
  (`android:allowBackup="false"`).
