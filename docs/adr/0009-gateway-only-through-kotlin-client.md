# ADR-0009: The gateway only through the Kotlin client

| | |
|---|---|
| Status | Accepted |
| Date | 2026-10-17 |
| Deciders | the project's maintainers |
| Related | `io.github.rustlanglatam:zeroclaw-api-client` (version pinned in `app/build.gradle.kts`), [spec: assistant service](../specs/assistant-service.md), [ADR-0022](0022-what-stays-in-the-app.md) |

## Context and problem

Early code reached the gateway with raw HTTP and the ZeroClaw CLI (`get-paircode --rotate`). The
project maintains a Kotlin client for the gateway, and the app is its first user.

## Decision

Everything the app asks of ZeroClaw goes through the client (`AssistantService.client`,
`Turns.open`): health, pairing (`pairing.initialCode()`, or a new code with
`pairing.adminNewCode(rotate = "all")` on the loopback-only admin endpoint, then `pairLegacy`),
memory, cron and `/ws/chat`. No raw HTTP and no CLI calls. A refused chat handshake (401) makes
the app pair again (`AssistantService.pair`).

## Consequences

- Good: one typed API, tested once, shared with other apps.
- Follow-up: cron is done (`Routines`, the owner's routines as ZeroClaw cron jobs); the config
  stays a file the app writes, for the reasons in [ADR-0022](0022-what-stays-in-the-app.md).
