# ADR-0030: Service keys entered by the owner, not built into the APK

| | |
|---|---|
| Status | Accepted |
| Date | 2026-10-23 |
| Deciders | the project's maintainers |
| Related | [ADR-0004](0004-secrets-encryption-off.md) (secrets in `config.toml`), [ADR-0027](0027-assistant-voice-from-a-cloud-speech-model.md), issue #6 |

## Context and problem

The model provider, model and API key, the web search key, the YouTube key and the natural voice settings were read
from `local.properties` at build time and compiled into `BuildConfig`. Every APK carried the developer's keys:
no build could be given to anyone, and anyone holding the APK could extract the keys. An ordinary owner also had no
way to use their own account.

## Considered options

1. The owner enters the settings and keys in the app; they are stored encrypted on the phone; release builds compile
   no keys; debug builds keep `local.properties` as a developer fallback.
2. Keep keys in the build and obfuscate them (R8, string encryption).
3. A server that holds the keys and proxies the model calls.

## Decision

Option 1. `Keys.kt` holds each setting (`MODEL_PROVIDER`, `MODEL`, `MODEL_KEY`, `SEARCH_PROVIDER`, `SEARCH_KEY`,
`YOUTUBE_KEY`, `VOICE_URL`, `VOICE_MODEL`, `VOICE_KEY`, `VOICE_NAME`, `VOICE_PER_MINUTE`) in the app's
`EncryptedSharedPreferences` (`gateway`, Keystore master key), prefixed `key.`; a value not entered falls back to the
build's `BuildConfig` value. The `release` build type overrides `MODEL_API_KEY`, `SEARCH_KEY`, `YOUTUBE_KEY` and
`VOICE_API_KEY` with empty strings, so no release APK contains a key; provider, model and voice names remain as
non-secret defaults.

The owner enters them in Connectors › Services. A key is never shown back; a key field left empty keeps the saved
key. Values are validated before they are stored (`Keys.valid`): they are written into ZeroClaw's TOML config, so
quotes, backslashes, control and non-ASCII characters are refused, provider names must be a single word (they name a
TOML table), the voice URL must start with `wss://` and the price must be a non-negative number. Saving the first
settings needs no credential (there is nothing to protect yet); replacing settings already in use needs the device
credential (`confirmOwner`), and without a screen lock it is refused, since it changes where the owner's data goes.
Each save increments `keys.version`; `AssistantService` rewrites `config.toml` when the version differs from the one
the config was written with, and the daemon is restarted (`reload`). While the model is not set, Home says so with a
button to the form.

Option 2 only delays extraction. Option 3 adds a server that sees every request, contrary to the project's scope.

## Consequences

- A release APK can be distributed; each owner uses their own account and pays their own usage.
- The first run of a release build needs the owner to obtain and paste a key: a real step for an ordinary user,
  explained on Home and in Connectors.
- The key still reaches `config.toml` in plain text inside app-private storage, because ZeroClaw reads it from there
  (ADR-0004); a copy of the app's private files would expose it. Sealing that file depends on ZeroClaw.
- The build's price (`zeroclaw.inputPerMTok`, `zeroclaw.outputPerMTok`) applies only while the model is the build's;
  for another model the cost is counted without a price.
- `local.properties` keeps working for development and the end-to-end checks (debug builds).
