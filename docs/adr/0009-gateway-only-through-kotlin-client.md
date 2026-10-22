# ADR-0009: The gateway only through the Kotlin client

| | |
|---|---|
| Status | Accepted |
| Date | 2026-10-17 |
| Deciders | the project's maintainers |
| Related | `io.github.rustlanglatam:zeroclaw-api-client`, [spec: assistant service](../specs/assistant-service.md) |

## Context and problem

Early code reached the gateway with raw HTTP and the ZeroClaw CLI (`get-paircode --rotate`). The
project maintains a Kotlin client for the gateway, and the app is its first user.

## Decision

Everything the app asks of ZeroClaw goes through the client (`AssistantService.client`,
`Turns.open`): health, pairing (a new code with `pairing.adminNewCode(rotate = "all")`, loopback
only), memory and `/ws/chat`. No raw HTTP and no CLI calls. A refused chat handshake (401) makes
the app pair again.

## Consequences

- Good: one typed API, tested once, shared with other apps.
- Follow-up: config and cron through the client as well.
