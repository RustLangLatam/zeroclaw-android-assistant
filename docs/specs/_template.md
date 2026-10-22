# Spec: <component>

| | |
|---|---|
| Status | Draft / Stable / Changing |
| Code | `app/.../File.kt`, … |
| Tests | `app/src/test/.../FileTest.kt`, `scripts/e2e.sh` (which checks) — or "none" |
| Decisions | [ADR-NNNN](../adr/NNNN-….md) |
| Last checked against code | YYYY-MM-DD, commit `abcdef0` |

## Purpose

One paragraph: what this component is for and what it is not for.

## Responsibilities

- What it owns and does (bullets).

## Interfaces

What it offers and what it uses: functions, MCP tools, intents, notifications, files, ports,
events. Name, input, output, who calls it.

## State and data

What it keeps, where (memory, file, ZeroClaw), its format, who writes and reads it, what survives a
restart or process death.

## Behaviour

The rules it follows, as they are in code: flows, decisions, timings, limits.

## Invariants

What must always hold (the things a test or a review checks).

## Failure modes

What happens when a dependency fails, a permission is missing, the process dies, input is bad.

## Security

Principals that reach it, capabilities and policy checks, untrusted input it handles.

## Open issues

Known gaps and bugs, each linked to the issue or ADR that tracks it.
