# ADR-0013: Memory in ZeroClaw, with a copy on the phone

| | |
|---|---|
| Status | Accepted |
| Date | 2026-10-17 |
| Deciders | the project's maintainers |
| Related | [spec: memory](../specs/memory.md), [ADR-0010](0010-zeroclaw-first-voice-first.md) |

## Context and problem

What the assistant knows about the owner must be a single memory: shown and deletable in the
Memory screen, recalled into turns, and available for a fast, offline start.

## Decision

The profile lives in ZeroClaw's memory (each fact under `profile-<id>`, with its area and whether
the owner stated it in the category), written and read through the Kotlin client, alongside what
the agent stores itself. `memory.json` on the phone is a copy used at start and offline.
Third-party turns recall nothing (`memory_recall_limit = 0`) and conversations are not auto-saved.

## Consequences

- Good: ZeroClaw's recall works on the profile; one place to delete from.
- Bad: two stores to keep in step; writes made while the gateway is down are lost.
