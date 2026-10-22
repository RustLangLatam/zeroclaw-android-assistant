# ADR-0010: ZeroClaw first, voice first

| | |
|---|---|
| Status | Accepted |
| Date | 2026-10-17 |
| Deciders | the project's maintainers |
| Related | [ADR-0009](0009-gateway-only-through-kotlin-client.md), [ADR-0022](0022-what-stays-in-the-app.md), [spec: settings](../specs/settings-and-setup.md) |

## Context and problem

Features that ZeroClaw already offers (memory, schedules, sessions) were being rebuilt in the app,
and some settings could only be changed through screens.

## Decision

1. **ZeroClaw first.** What ZeroClaw can manage (memory, sessions, schedules, persona, cost) is
   done there, through the Kotlin client; the app builds only what ZeroClaw cannot do. Permissions
   stay in the app's code ([ADR-0003](0003-permissions-enforced-in-code.md)).
2. **Voice first.** Every feature and setting is reachable by conversation with the assistant:
   through an owner tool (`set_preference`, `set_contact_group`, …) or an interface command
   ([ADR-0015](0015-interface-commands-in-code.md)).

## Consequences

- Good: one source of truth; the assistant is configurable by conversation.
- Bad: settings reachable by the model need limits enforced in code.
