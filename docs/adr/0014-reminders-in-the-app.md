# ADR-0014: Reminders are scheduled by the app

| | |
|---|---|
| Status | Accepted |
| Date | 2026-10-16 |
| Deciders | the project's maintainers |
| Related | [spec: reminders](../specs/reminders.md), [ADR-0010](0010-zeroclaw-first-voice-first.md), [ADR-0022](0022-what-stays-in-the-app.md) |

## Context and problem

ZeroClaw has cron, but a high-level reminder must ring on time through do-not-disturb, over the
lock screen, as a full-screen "call", and repeat until the owner responds. Only Android can do
that, and Doze can delay anything else.

## Decision

Reminders are exact alarms in the app (`AlarmManager`), with levels; health reminders are always
high, decided in code; a high reminder opens `CallActivity`. This is an exception to ZeroClaw
first. News briefings, which are agent turns, are not covered by this decision.

## Consequences

- Good: on time, loud when it matters, survives reboots.
- Bad: reachable by the model as an ACT tool, so its use is gated by the policy.
