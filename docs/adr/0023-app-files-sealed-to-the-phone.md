# ADR-0023: The app's files sealed to the phone

| | |
|---|---|
| Status | Accepted |
| Date | 2026-10-17 |
| Deciders | the project's maintainers |
| Related | [ADR-0004](0004-secrets-encryption-off.md), [ADR-0013](0013-memory-in-zeroclaw-with-phone-copy.md), [security](../architecture/security.md), [storage](../architecture/storage.md) |

## Context and problem

What the app keeps (memory, messages, log) must be useless when copied to another device. The app's
files were plain text in its private storage. Android's sandbox and file-based encryption protect
them from other apps and from a powered-off phone. They do not protect them from a copy taken from
a running, unlocked phone: root, a debuggable build (`run-as`), or a forensic tool. Only the gateway
token was encrypted (`EncryptedSharedPreferences`).

## Considered options

1. Leave it to Android (sandbox, file-based encryption, no backups).
2. Seal the app's own files with a non-exportable key in the Android Keystore.
3. Option 2, plus encrypting ZeroClaw's own files (memory database, sessions, `config.toml`).

## Decision

Option 2. `Vault` keeps an AES-256-GCM key in the Android Keystore, in secure hardware when the
phone has it. The key never leaves the Keystore. Every file the app owns is sealed with it:
policy, activity log, memory copy, notes, style, email, seen messages, introductions, pending
memory writes, reminders, news, and the photo index. A copy of these files on another device, or
restored after a factory reset, cannot be opened.

Option 3 is not possible today. ZeroClaw opens its SQLite memory, its session store and
`config.toml` itself, and v0.8.5 has no at-rest encryption for them (no SQLCipher). Its own secrets
encryption is off ([ADR-0004](0004-secrets-encryption-off.md)). Those files stay protected only by
the sandbox and file-based encryption. Encrypted storage is a candidate request to upstream
ZeroClaw.

## Consequences

- An unreadable file (sealed with another key) loads as empty. On another phone the app starts
  without what was copied.
- Files written before this change are read once as plain text and sealed on their next write.
- The end-to-end checks can no longer read `activity.jsonl`. Debug builds log each entry to logcat
  (`KsandraJournal`), and the checks read it from there.
- A future backup of the assistant's data must be encrypted with a key the owner holds, not this
  one, so it can be restored on a new phone.
- ZeroClaw's files can still be read on a rooted or debuggable phone.
