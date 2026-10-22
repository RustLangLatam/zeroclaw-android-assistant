# ADR-0027: Assistant voice from a cloud speech model, platform TTS as fallback

| | |
|---|---|
| Status | Accepted |
| Date | 2026-10-21 |
| Deciders | the project's maintainers |
| Related | [spec: owner chat and voice](../specs/owner-chat-and-voice.md), [spec: settings and setup](../specs/settings-and-setup.md), [ADR-0003](0003-permissions-enforced-in-code.md), [ADR-0010](0010-zeroclaw-first-voice-first.md) |

## Context and problem

The assistant speaks with the phone's text-to-speech (TTS) voices. They are free and work offline,
but sound read rather than spoken, and a phone may lack a voice for the owner's regional variant of
a language. Cloud speech models sound natural and accept a style instruction, but are billed by the
minute, need internet, and some are full conversational agents that could answer or act on their
own.

## Considered options

1. A realtime speech model in the cloud used only as a voice: the text ZeroClaw's agent wrote, read
   aloud verbatim, outside any conversation, with no tools; platform TTS as the fallback.
2. A plain text-to-speech endpoint (text in, a sound file out).
3. A speech-to-speech conversation model as both voice and agent.

## Decision

Option 1. The app opens the provider's realtime WebSocket per line (`CloudVoice`), sets an
audio-only session (16-bit PCM, 24 kHz, the configured voice) and requests one out-of-band response
(`conversation: none`) with the line and style instructions. The instructions are configuration of
the speech request: read the text verbatim, never answer it, a fixed voice style, a neutral native
accent for the assistant's language, and a pace set by the warmth setting.

- The audio plays as it arrives (`AudioTrack`); the presence mark pulses on each syllable onset.
- Platform TTS speaks instead when *Natural voice* is off, there is no internet or no
  configuration, the monthly ceiling is reached, or no audio arrives within 2.5 s.
- Lines up to 160 characters are cached as audio on the phone, sealed (Vault), and replayed without
  the cloud.
- Each second of cloud speech is counted; with `voice.perMinute` it is shown in dollars in
  *What I cost* and reported by `usage`. The owner sets a monthly ceiling (US$ 5 by default; raising
  it by voice requires the owner's credential).
- The provider is configuration (`voice.url`, `voice.model`, `voice.apiKey`, `voice.name`,
  `voice.perMinute` in `local.properties`).

Option 2: the known endpoints of this kind are being retired in favour of the realtime ones.
Option 3 would take the conversation, and with it the tools, out of ZeroClaw and `Policy.kt`; it
remains a possible later step only if every request is handed to ZeroClaw.

## Consequences

- Good: a natural voice with a consistent style and accent; ZeroClaw still writes every word and the
  policy still gates every act; never silent (platform TTS is always available).
- Bad / cost: a few cents a minute; the spoken text is sent to the voice provider (the privacy page
  must state this); a realtime model may deviate from the text (the instructions forbid it; not
  verified yet); one WebSocket per line adds its handshake to the first audio; the key ships in the
  APK, like the model's, until keys are moved out of the APK.
- Follow-ups: keep the socket open across consecutive lines if the first audio is slow; compare the
  spoken transcript with the text; speech input and a live conversation.
