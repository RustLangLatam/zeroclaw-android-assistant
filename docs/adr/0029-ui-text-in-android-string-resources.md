# ADR-0029: UI text in Android string resources

| | |
|---|---|
| Status | Accepted |
| Date | 2026-10-22 |
| Deciders | the project's maintainers |
| Related | supersedes [ADR-0024](0024-languages-in-kotlin-pairs.md) |

## Context and problem

User-facing text was written in the code as pairs, `t("es", "en")` (ADR-0024). It kept both
languages side by side and needed no `Context`, but it put Spanish and English prose throughout the
sources, made translation impossible without editing Kotlin, and is not what Android contributors
expect. The project is being opened for collaboration.

## Considered options

1. Android string resources (`res/values/strings.xml` for English, `res/values-es/strings.xml` for
   Spanish), resolved in the assistant's language rather than the device locale.
2. Keep the Kotlin pairs.
3. A third-party localization library.

## Decision

Option 1. `t(R.string.key, args…)` and `tn(R.plurals.key, count, args…)` (`Language.kt`) read the
resources of a configuration context created for `Language.current`, cached per language, so the
assistant's language can differ from the device locale and changes at once. English is the default
resource set. Format arguments are positional (`%1$s`); quantities use `<plurals>`. JVM unit tests
have no `Resources`: when no `Context` is bound, `Strings` reads the same XML files from the
directory given by the `strings.dir` system property, which the Gradle test task sets, so the tests
stay plain JUnit on the JVM.

## Consequences

- Good: no user-facing prose in Kotlin; translators work on XML; standard tooling (lint, translation
  editors).
- Bad / cost: keys must be kept unique and meaningful; the JVM fallback implements only the subset
  of aapt's escaping and the plural rule (one/other) that Spanish and English need.
- Follow-up (done): the keyword lexicons used to understand Spanish and English input (commands,
  memory areas and other patterns) moved out of the code into `assets/lexicon.json`, read through
  `Lexicon`.
