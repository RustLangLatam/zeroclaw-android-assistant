# ADR-0007: Third-party turns run one at a time

| | |
|---|---|
| Status | Accepted |
| Date | 2026-10-16 |
| Deciders | the project's maintainers |
| Related | [spec: notification channel](../specs/notification-channel.md) |

## Context and problem

In official ZeroClaw, an MCP call does not identify the turn (or the sender) it belongs to. With two
senders at once, `take_message` or `availability` could be attributed to the wrong person.

## Decision

`Turns` runs one third-party turn at a time; `Guard.thirdPartySender` names the sender of the turn
in progress; each turn uses a new session.

## Consequences

- Good: correct attribution without changing ZeroClaw.
- Bad: messages queue behind a slow turn; a turn cancelled by the app may continue in ZeroClaw.
