# ADR-0028: A log of every connection that leaves the phone

| | |
|---|---|
| Status | Proposed (deferred, 2026-10-22) |
| Date | 2026-10-22 |
| Deciders | the project's maintainers |
| Related | [security](../architecture/security.md), [ADR-0020](0020-fail-closed-daemon-start.md) |

## Context and problem

*Permissions and privacy* describes in words what leaves the phone (to the model, the news feed,
the weather service, the speech model). Nothing lets the owner verify it. A trustworthy log must
cover both the app's own connections and the ZeroClaw daemon's, which runs as a separate process and
talks to the model provider, web search and email servers itself.

## Considered options

1. ZeroClaw's HTTP(S) traffic through a small proxy in the app on loopback (`HTTPS_PROXY` in the
   daemon's environment): each `CONNECT` logged (time, host, port, bytes each way), never the
   content; the app's own connections logged where they are made (one OkHttp interceptor, and the
   few `HttpURLConnection` and IMAP/SMTP call sites).
2. An Android VPN service that sees every packet of the app's uid.
3. Logging only the app's own connections.

## Decision

Option 1, when the work is taken up. It covers what ZeroClaw sends over HTTP(S) (the model, web
search) without reading it, and the app's own calls at their source. IMAP and SMTP from ZeroClaw's
email channel are not HTTP: they would be listed from its configuration (the accounts' servers)
rather than observed. Option 2 takes the phone's only VPN slot and shows an alarming system prompt
for little more coverage; option 3 leaves out the most important traffic, the model's.

## Consequences

- Good: the privacy page can show, per day, which hosts were reached, why, and how much; an
  unexpected host stands out.
- Bad / cost: a proxy on the path of every model request (latency, one more component that can
  fail: it must fail closed, never be bypassed); TLS stays end to end, so the log knows the host,
  not the content.
- Deferred: not built for now; this records the design so it is not re-derived.
