# Contributing

Thanks for helping. This document covers how to build, the rules every change follows, and how changes are reviewed.

## Getting started

Requirements: JDK 21, the Android SDK (platform 35, build-tools 35.0.0), NDK 29.0.14206865, and `patchelf` in
`/usr/bin`, `/usr/local/bin` or `~/.local/bin`.

```sh
./gradlew testDebugUnitTest      # JVM unit tests (no device needed)
./gradlew assembleDebug          # fetches and verifies the ZeroClaw and llama.cpp release binaries on first build
```

The build works without `local.properties` (CI has none); the assistant then has no model to talk to. To run it, set
`sdk.dir`, `zeroclaw.provider`, `zeroclaw.model` and `zeroclaw.apiKey` in `local.properties` (never committed). Every
key, the minimal start-up path, the emulator setup (`zc34`, fake contacts, PIN `1234`) and the end-to-end checks
(`scripts/e2e.sh`, with the `fakechat` module) are in
[docs/architecture/configuration.md](docs/architecture/configuration.md).

## Ground rules

- **Permissions are enforced in code.** Every tool the agents can call is served by `PhoneMcp.kt` and passes
  `Policy.kt` before it runs. A new ability declares its `Capability`; a third-party turn must never reach an owner
  tool. Prompts never grant permissions ([ADR-0003](docs/adr/0003-permissions-enforced-in-code.md)).
- **ZeroClaw first.** What the agent runtime already provides is used through the official Kotlin client before an
  app-side mechanism is added ([ADR-0010](docs/adr/0010-zeroclaw-first-voice-first.md)).
- **Nothing leaves the device without a reason** that is documented in [docs/architecture/security.md](docs/architecture/security.md).
- **No real people or real messages.** Tests and examples use the owner "Milton" and the fake contacts Laura (work),
  Ana (family) and Pat (unknown). Outgoing paths are tested with `fakechat` on the emulator, never against real apps
  or contacts.
- **No secrets** in the repository: keys live in `local.properties`.
- **No AI vendor or model names** in code, docs or commit messages; providers are configuration.
- **User-facing text** goes to `app/src/main/res/values/strings.xml` (English) and `values-es/strings.xml` (Spanish);
  input keywords go to `app/src/main/assets/lexicon.json` ([ADR-0029](docs/adr/0029-ui-text-in-android-string-resources.md)).

## Code style

- Kotlin official code style; KDoc on public and non-trivial declarations, summaries in the third person present tense.
- Comments explain why, not what. English only.
- Pure logic stays free of Android types where possible, so it is covered by JVM unit tests.

## Documentation

Follows [ADR-0000](docs/adr/0000-documentation-standards.md): a change to a component updates its spec in
`docs/specs/`; a change of direction is recorded as an ADR in `docs/adr/` (MADR format).

## Pull requests

1. Open an issue first for anything beyond a small fix.
2. Branch from `main`; one topic per pull request.
3. Commits follow [Conventional Commits](https://www.conventionalcommits.org/) (`feat:`, `fix:`, `docs:`, `refactor:`,
   `test:`, `ci:`, `chore:`).
4. CI must pass (unit tests and a debug build). Fill in the pull request checklist.

## Reporting security issues

Do not open public issues for vulnerabilities. See [SECURITY.md](SECURITY.md).
