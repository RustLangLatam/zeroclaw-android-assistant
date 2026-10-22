# ADR-0017: The system splash transitions into the presence mark button

| | |
|---|---|
| Status | Accepted |
| Date | 2026-10-17 |
| Deciders | the project's maintainers |
| Related | PRs #32, #33, [spec: presence mark](../specs/presence-mark.md) |

## Context and problem

The app opens with the presence mark, and the mark must become the main button with no jump,
shift or flash. Android 12+ draws the splash itself (an animated icon) and hands it to the app;
the app's first frames can show through before the handover.

## Considered options

1. A custom splash screen (an activity): a second screen, slower, against Android's guidance.
2. The system splash with an animated vector of the mark, and an exit animation that carries it to
   the button.

## Decision

Option 2, measured frame by frame on the emulator:

- `scripts/splash.py` draws the mark exactly as the button (disc gradient, rim light, 25 LEDs, the
  pearl with its gradient and soft light) as `splash_mark.xml`, and a fully lit copy
  `splash_mark_lit.xml`.
- Until the handover, the app shows that lit copy where the splash is (`standIn`).
- `fuse` glides and scales it to the button, targeting the button's bounds every frame; the
  button does not rise while the splash lands on it; the app's background fades in around it.
- While landing, the button is drawn as the splash (at rest, no breathing animation, the default
  palette, fully lit); then its own state, animation and colors ease in.

## Consequences

- Good: position within half a pixel and identical size measured; no flash.
- Since #45 the mark has a glass eye and splash-like LEDs; the splash draws the same, so the landing
  still matches.
- Bad: tied to Android's icon geometry (a 192 dp view showing the central 160 of 240 units); if the
  system freezes its own splash under load, the app cannot fix that.
