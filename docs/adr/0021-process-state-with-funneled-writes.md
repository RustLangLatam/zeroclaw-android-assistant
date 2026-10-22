# ADR-0021: Process-wide state, with its writes funneled

| | |
|---|---|
| Status | Accepted |
| Date | 2026-10-17 |
| Deciders | the project's maintainers |
| Related | [storage](../architecture/storage.md) |

## Context and problem

About ten objects (`Ui`, `Guard`, `Inbox`, `Mailbox`, `Memory`, …) hold the app's state and were
written from any thread: the owner's chat lived in the activity (a rotation lost the answer), the
policy file was written on whichever thread changed something, chat lines and refreshes came from
background threads.

## Considered options

1. One state holder (a service-scoped object of `StateFlow`s, mutated on one thread) replacing the
   objects, with every screen collecting from it.
2. Keep the process-wide objects, and funnel each kind of write to one place.

## Decision

Option 2:

- The owner's conversation belongs to the process (`OwnerChat`), not to an activity.
- Chat lines (`Ui.add`, `Ui.prepend`) and refreshes (`Ui.refresh`) are posted to the main thread.
- The policy file and the activity log are written on one IO thread (`Guard.save`, debounced;
  `saveNow` for the kill switch); other files are written whole through a temp file
  (`File.replaceWith`).
- What screens read from files or providers is read off the main thread (`loaded`).

Compose state written from background threads is safe in Compose's snapshot system; what was
unsafe was the order and the place of the writes, which these rules fix.

## Consequences

- Good: the observed failures (lost answer on rotation, jank, lost writes) are fixed with small
  changes; no screen had to change how it reads state.
- Bad: the objects stay global and harder to test than an injected holder; a new kind of state must
  follow these rules by review, not by type.
- Follow-up: option 1 if the app gains a second process or screens that need isolated state.
