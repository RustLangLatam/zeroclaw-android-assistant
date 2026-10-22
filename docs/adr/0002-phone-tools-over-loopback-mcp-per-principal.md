# ADR-0002: Phone tools over a loopback MCP server, one endpoint per principal

| | |
|---|---|
| Status | Accepted |
| Date | 2026-10-16 |
| Deciders | the project's maintainers |
| Related | [spec: phone MCP](../specs/phone-mcp.md), [access model](../specs/access-model.md), [ADR-0003](0003-permissions-enforced-in-code.md), [ADR-0016](0016-mcp-key-per-endpoint.md) |

## Context and problem

Only the app can access the phone (Android permissions belong to the app), but the agents run in
ZeroClaw, which speaks MCP as a client. There are two kinds of turns: the owner's, and those of
third parties (people who write to the owner). Third-party turns must never reach the owner's
tools.

## Considered options

1. An MCP server in the app on `127.0.0.1:42618`, one endpoint per principal (`/mcp/owner`,
   `/mcp/third_party`), and one ZeroClaw agent per principal wired to its own endpoint.
2. One endpoint, with the principal passed as an argument or inferred from the turn.
3. ZeroClaw's own tools (shell, HTTP) calling Android.

## Decision

Option 1. The principal is fixed by the endpoint the agent is configured with, so the model cannot
claim another one; each endpoint lists only its principal's tools.

## Consequences

- Good: the boundary between owner and third party is structural, not a prompt.
- Bad: loopback is reachable by every app on the phone (addressed by
  [ADR-0016](0016-mcp-key-per-endpoint.md) and [ADR-0020](0020-fail-closed-daemon-start.md)); an
  MCP call does not identify the turn it belongs to ([ADR-0007](0007-third-party-turns-one-at-a-time.md)).

Note: since [ADR-0025](0025-an-offline-agent-on-a-local-model.md) a third endpoint, `/mcp/offline`,
serves the offline agent with the owner's key and principal and lists only the subset of owner
tools in `Offline.TOOLS`.
