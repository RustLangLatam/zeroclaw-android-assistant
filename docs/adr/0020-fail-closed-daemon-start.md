# ADR-0020: The daemon starts only on a safe loopback

| | |
|---|---|
| Status | Accepted (PR #35) |
| Date | 2026-10-17 |
| Deciders | the project's maintainers |
| Related | [ADR-0016](0016-mcp-key-per-endpoint.md), [security](../architecture/security.md) |

## Context and problem

Another app can bind the MCP port first (and receive the keys) or answer `/health` in the
gateway's place (and receive the owner's chat); a malformed request crashes the app and orphans the
daemon; a crashed daemon is never restarted.

## Decision

- If the MCP port cannot be bound, the daemon is not started and the owner is told.
- New MCP keys on every start, written into `config.toml` each time.
- Before pairing, the daemon must be alive and its log must show the gateway bound.
- Every server thread catches its errors; requests have a timeout and size caps.
- A watchdog restarts the daemon with backoff; a stale daemon is killed on start.
- The MCP server listens on a native IPv4 socket (`android.system.Os`): Java's `ServerSocket` uses
  IPv6 with 127.0.0.1 mapped, and Linux lets another app listen on IPv4 next to it and take the
  connections.
- The gateway token is sent only while the gateway belongs to the supervised ZeroClaw (its process
  alive, its log showing it bound the port): while ZeroClaw is down, another app may listen there.

## Consequences

- Good: no key leaks to a squatter; no crash from outside; recovery without a reboot.
- Cost: a start can now fail visibly instead of running half-protected.
