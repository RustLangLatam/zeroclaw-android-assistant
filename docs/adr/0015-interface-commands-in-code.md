# ADR-0015: Interface commands are understood in code

| | |
|---|---|
| Status | Accepted |
| Date | 2026-10-16 |
| Deciders | the project's maintainers |
| Related | [spec: owner chat and voice](../specs/owner-chat-and-voice.md) |

## Context and problem

The app has no menu by design: navigation and control are spoken or typed ("open settings",
"pause", "app mode"). Sending these to the model is slow, costs a call, and a pause must never
depend on the model.

## Decision

`Commands.of` normalizes the text and matches whole phrases; a match is handled by the app and
never sent to the model. Questions that only mention a word are not commands. The model can still
switch modes (`set_mode`) when the request is phrased differently.

## Consequences

- Good: instant, free, reliable for pause and navigation.
- Bad: a fixed phrase list to maintain per language. The phrases now live in
  `assets/lexicon.json`, read through `Lexicon` ([ADR-0029](0029-ui-text-in-android-string-resources.md)).
