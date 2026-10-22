# ADR-0025: Without internet, an offline agent on a local model

| | |
|---|---|
| Status | Accepted |
| Date | 2026-10-18 |
| Deciders | the project's maintainers |
| Related | [spec: settings and setup](../specs/settings-and-setup.md), [ZeroClaw facts](../architecture/zeroclaw.md) |

## Context and problem

The assistant should still answer without internet, as an opt-in option whose setup states that a
model will be downloaded. ZeroClaw v0.8.5 can fall back from one provider alias to another
(`fallback = ["llamacpp.local"]`), connection errors included, and talks to a llama.cpp server
(`[providers.models.llamacpp.<alias>] uri = …`).

Measured on the emulator (llama.cpp b11381, a 1.7B-parameter model, Q4_K_M GGUF): the model loads
in 5 s and processes a prompt at 3 tokens a second through ARM translation; a phone is 20 to 50
times faster. The owner agent's prompt (persona, memories, dozens of tool schemas, recent history)
is many thousands of tokens: a minute or more on a phone before the first token.

## Considered options

1. ZeroClaw's fallback on the owner agent's alias: the same agent, its full prompt, sent to the
   local model when the cloud fails.
2. An `offline` agent in ZeroClaw on the local model, with a short persona and few or no tools; the
   app talks to it instead of `owner` while the phone is offline.

## Decision

Option 2. The app already knows when the phone is offline (`Absence`), and the owner's chat already
picks its agent (`Turns.open`). A short prompt keeps the first token within seconds on a phone, and
what the small model can do is decided in the config, not left to the model. ZeroClaw keeps the
memory, the sessions and the cost accounting of both agents.

- The engine, llama.cpp's `llama-server` (MIT), ships in the APK like ZeroClaw: a pinned release
  (`llamaVersion` in `app/build.gradle.kts`), checked by SHA-256, its libraries stripped by the
  build (the NDK) and found through `RUNPATH=$ORIGIN` (Android's linker under ARM translation
  ignores `LD_LIBRARY_PATH`).
- The model is configuration: `offline.model.name`, `offline.model.url`, `offline.model.sha256` and
  `offline.model.bytes` in `local.properties` (a GGUF file); without them the option is unavailable.
  It is downloaded only when the owner enables the option (Settings, or `set_preference`), and
  checked by SHA-256. Developed and measured with a 1.7B-parameter Q4_K_M model of 1.1 GB, Spanish
  and English, with tool calls.
- The server runs only while the phone is offline and the option is on: it holds about 1.5 GB of
  memory.
- Replies to other people are never written by the local model: they wait for the internet.
- The small model gets its own MCP endpoint (`/mcp/offline`) with six of the owner's tools
  (`Offline.TOOLS`: `remind`, `list_reminders`, `create_alarm`, `next_alarm`, `list_events`,
  `open_app`), thinking disabled (`/no_think`), and a new session per outage. Requests that must
  be exact (reminders, alarms, calendar entries, opening an app), when stated explicitly, are
  handled by the app itself (`Offline.direct`) rather than by the small model.

## Consequences

- The APK grows by about 13 MB; the build needs the NDK and `patchelf`.
- Offline answers are simpler and slower, and use more battery; the owner is told so before the
  download and sees, in Settings, the model, its size, how often it answered and what it used.
- Option 1 stays open for the briefings' writer later, where the prompt is short.

## Notes

- The engine keeps its prompt cache across runs. `llama-server` starts with `--cache-reuse 256` and
  `--slot-save-path`; after the first answer of a run, slot 0 (the processed persona and tool
  definitions) is saved through the `/slots` API, and restored on the next start, so only the
  differing suffix is processed again. Measured on a test device: the first answer after a restart
  is about 3× faster. The slot file takes a few hundred MB in the app's cache.
