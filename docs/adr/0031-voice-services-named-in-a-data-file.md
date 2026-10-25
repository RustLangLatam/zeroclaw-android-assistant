# ADR-0031: Voice services named in a data file

| | |
|---|---|
| Status | Accepted |
| Date | 2026-10-25 |
| Deciders | the project's maintainers |
| Related | [ADR-0027](0027-assistant-voice-from-a-cloud-speech-model.md), [ADR-0030](0030-service-keys-entered-by-the-owner.md) |

## Context and problem

The services form lets the owner choose the model provider and model from ZeroClaw's catalog, so no provider or model
name is written in the repository (AGENTS.md). The natural voice can't follow it: the app speaks to the voice service
itself (ADR-0027), and ZeroClaw has no voice catalog or speech endpoint the app can use. Asking an ordinary owner for a
WebSocket URL, a speech model and a voice name, with nothing saying which service the key belongs to, is not usable.

## Considered options

1. A data file in the APK listing the voice services that speak the protocol the app implements, verified, each with
   its name, URL, speech model, price per minute and voices; the code stays neutral. Services not listed are entered
   by their URL.
2. Propose a speech and voices API in ZeroClaw and route the natural voice through the daemon.
3. Keep the URL-based form.

## Decision

Option 1. `app/src/main/assets/voices.json` is the only place in the repository where AI services and their voices are
named, as data. The form offers its services, then their voices, then the key ("API key of <service>"); "Other (my
own URL)" keeps any compatible service usable. A service is listed only after it was tried with this app's protocol.
Option 2 stays the long-term direction (ZeroClaw first) if the daemon gains such an API.

## Consequences

- AGENTS.md's no-vendor-names rule has one exception: this file.
- Adding a service is one entry; a service with another protocol also needs its client in `CloudVoice.kt`.
- Prices in the file are defaults; the owner can change them under Advanced.
