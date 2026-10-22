# ADR-0012: The owner's session keeps 24 messages of history

| | |
|---|---|
| Status | Accepted |
| Date | 2026-10-17 |
| Deciders | the project's maintainers |
| Related | PR #30, [spec: owner chat and voice](../specs/owner-chat-and-voice.md) |

## Context and problem

The owner's conversation is one ZeroClaw session that survives restarts. With 80 messages of
history (66–72 in practice) the model stopped calling its tools and reported actions it had not
taken, including an invented contact phone number; the trace showed the tools registered and zero
native tool calls.

## Decision

`max_history_messages = 24` with context compression for the owner agent; longer-lived facts come
from memory recall. The persona instructs the agent never to report an action a tool did not
confirm. Debug builds can start a fresh session (`--ez fresh true`).

## Consequences

- Good: tool calls resumed; answers are grounded in tool results.
- Bad: older turns are only available through memory and compression.
