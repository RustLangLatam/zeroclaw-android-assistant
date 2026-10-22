# ADR-0024: Languages as Kotlin pairs, not Android resources

| | |
|---|---|
| Status | Superseded by [ADR-0029](0029-ui-text-in-android-string-resources.md) |
| Date | 2026-10-18 |
| Deciders | the project's maintainers |
| Related | [ADR-0029](0029-ui-text-in-android-string-resources.md) |

## Context and problem

The app needed more than one language. The text the assistant shows and says was written in
Kotlin. Some of it is in Compose screens. Much of it is in pure Kotlin objects that are unit-tested
on the JVM: the policy's descriptions (`Labels`, `Policy`), the assistant's remarks (`Phrases`), the
panorama, the acts. The assistant follows the phone's language unless the owner chooses another,
by voice, without the phone's language changing.

## Considered options

1. Android string resources (`res/values-en/strings.xml`), read with `stringResource` and `getString`.
2. Kotlin pairs beside their use: `t(es = "…", en = "Settings")`, by `Language.current`.

## Decision

Option 2. Resources need a `Context`, which the pure objects and their JVM tests do not have. They
follow the phone's locale unless the app overrides the configuration on every component. With
pairs, each text stays where it is used, the tests stay plain JVM tests, and changing the language
is changing one value.

## Consequences

- Good: no `Context` in the pure code, and no configuration overrides. A change of language
  applies at the next recomposition.
- Bad: no translator tooling (no XML for a translation service). A third language means adding an
  argument to every pair; `t` would then take a map, with Spanish as the fallback.
