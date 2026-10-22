## What and why

<!-- What this changes and the reason. Link the issue. -->

## Checklist

- [ ] `./gradlew testDebugUnitTest` passes; new logic has unit tests.
- [ ] Behavior checked on the emulator (`scripts/e2e.sh` for the notification channel).
- [ ] The component's spec in `docs/specs/` is updated; a change of direction has an ADR in `docs/adr/`.
- [ ] New abilities go through `Policy.kt` with an explicit capability; a third-party turn cannot reach them.
- [ ] User-facing text is in `res/values/strings.xml` and `res/values-es/strings.xml`.
- [ ] No secrets, personal data, or AI vendor/model names in code, docs or commits.
