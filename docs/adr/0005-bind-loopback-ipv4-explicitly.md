# ADR-0005: Bind 127.0.0.1 explicitly

| | |
|---|---|
| Status | Accepted |
| Date | 2026-10-16 |
| Deciders | the project's maintainers |
| Related | [spec: phone MCP](../specs/phone-mcp.md) |

## Context and problem

On Android, `InetAddress.getLoopbackAddress()` can return `::1`, while ZeroClaw connects to
`127.0.0.1`; the MCP server was unreachable.

## Decision

Both the gateway (`--host 127.0.0.1`) and `PhoneMcp` bind `127.0.0.1` by literal address.

## Consequences

- Good: works on every device tested; never exposed to the network.
- Bad: none known; loopback is still shared with other apps (see [ADR-0016](0016-mcp-key-per-endpoint.md)).
