# ADR-0003: Permissions are enforced in code, never in prompts

| | |
|---|---|
| Status | Accepted |
| Date | 2026-10-16 |
| Deciders | the project's maintainers |
| Related | [access model](../specs/access-model.md), [spec: policy engine](../specs/policy-engine.md), PR #1 |

## Context and problem

The model decides which tool to call, and its input includes text from other people (messages,
emails, news). Any rule expressed only as instructions can be talked around.

## Considered options

1. Instructions in the agents' persona files.
2. ZeroClaw's risk profiles and approvals only.
3. A policy engine in the app that decides every tool call (principal, capability, group,
   ceiling, rule), with approvals, an undo window, a kill switch and a log.

## Decision

Option 3, as pure Kotlin (`Policy.kt`, unit-tested), applied by `Guard.gated` to every MCP call.
Persona text may explain the rules but never enforces them. Ceilings are clamped in code: personal
data never reaches a third party, and outward irreversible actions need the owner's strong approval
and the undo window.

## Consequences

- Good: a manipulated model can only make a request; code decides the outcome.
- Bad: ZeroClaw's native tools (web, memory) are outside the engine (see
  [ADR-0018](0018-tool-less-agent-for-unattended-turns.md) for turns the owner did not type).
