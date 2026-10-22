# ADR-0006: The kill switch lives in the app

| | |
|---|---|
| Status | Accepted |
| Date | 2026-10-16 |
| Deciders | the project's maintainers |
| Related | [spec: policy engine](../specs/policy-engine.md) |

## Context and problem

The owner must be able to stop everything at once ("pause"). ZeroClaw's `estop` does not stop a
running agent in v0.8.5.

## Decision

`Guard` holds the switch: while it is on, every gated MCP call is refused; it is checked again when
an undo window ends; it survives restarts (`policy.json`); and resuming requires the owner's
credential.

## Consequences

- Good: immediate and persistent for every phone tool.
- Bad: as first built, ZeroClaw's own tools and a turn in flight kept running.
- Follow-up: pausing also stops the daemon.

Note: implemented; while paused, `AssistantService` stops the daemon, which aborts any turn in
flight.
