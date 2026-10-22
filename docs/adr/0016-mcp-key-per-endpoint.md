# ADR-0016: A key per MCP endpoint

| | |
|---|---|
| Status | Accepted (to be extended by [ADR-0020](0020-fail-closed-daemon-start.md)) |
| Date | 2026-10-17 |
| Deciders | the project's maintainers |
| Related | [spec: phone MCP](../specs/phone-mcp.md) |

## Context and problem

Every app on the phone can reach `127.0.0.1:42618`; without authentication any of them could call
the owner's tools.

## Decision

Each endpoint has its own Bearer key: 32 random bytes, kept in encrypted preferences, written into
ZeroClaw's MCP server entries, compared in constant time. Only ZeroClaw, with that endpoint's key,
is answered.

## Consequences

- Good: other apps get 401.
- Bad: the keys never rotate and are sent on every call: whoever holds the port receives them.
  [ADR-0020](0020-fail-closed-daemon-start.md) addresses both.
