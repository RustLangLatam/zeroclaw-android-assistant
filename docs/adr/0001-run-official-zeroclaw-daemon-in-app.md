# ADR-0001: Run the official ZeroClaw daemon inside the app

| | |
|---|---|
| Status | Accepted |
| Date | 2026-10-16 |
| Deciders | the project's maintainers |
| Related | [spec: assistant service](../specs/assistant-service.md) |

## Context and problem

The assistant needs an agent runtime on the phone: conversations, sessions, memory, model calls and
tool calls. ZeroClaw publishes an official `aarch64-linux-android` build (v0.8.5, experimental).

## Considered options

1. The official binary as a child process of the app, shipped in the APK.
2. ZeroClaw linked as a library through JNI.
3. A custom agent loop in Kotlin.
4. ZeroClaw on a server, with the phone as a client.

## Decision

Option 1. The binary is downloaded at build time, checked against a pinned SHA-256, packaged as
`jniLibs/arm64-v8a/libzeroclaw.so` and started with `ProcessBuilder` from the native library
directory by a foreground service. Config and workspace live in the app's private storage. Google
Play allows running code packaged in the APK.

## Consequences

- Good: the official runtime, upgraded by bumping a version; no fork; measured on the emulator at
  1.4–2.1 s to a healthy gateway.
- Bad: two processes to keep in step (a crash of one is not observed by the other); the target is
  experimental upstream; roughly 70–220 MB of RAM.
- Follow-up: JNI remains the option for iOS, where processes cannot be spawned.
