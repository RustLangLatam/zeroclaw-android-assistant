# Changelog

All notable changes. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow
[Semantic Versioning](https://semver.org/).

## [0.1.0] - unreleased

First public version: an on-device personal assistant for Android that runs the ZeroClaw v0.8.5 agent daemon inside
the app, acts on the phone through tools on a loopback MCP server, and enforces every permission in code.

### For the owner
- Voice and text conversation in English and Spanish; every setting by voice; the Presence Mark and a floating mark
  over other apps; the system assist gesture opens the assistant listening.
- Messages from the messaging apps the owner selects, answered under rules: family on its own with an undo window,
  work only with approval, strangers never. Busy, meeting or driving mode with one fixed reply. What is still
  unanswered. Possible-scam warnings. Approvals not answered in time are kept for the owner.
- Email from notifications and from connected accounts (Gmail, Yahoo, iCloud, IMAP/SMTP); bills and parcels found in
  email and offered as reminders.
- Calendar that refuses clashes; reminders by level (high ones ring full screen), repeating daily, every N hours, on
  weekdays or monthly; contacts' birthdays; time to leave for appointments; routines and a morning briefing.
- Share a picture or PDF with the assistant: text read on the phone, a reminder or calendar entry on a tap.
- Lists by voice; "call Ana" opens the dialer (the app never places calls); photo and file search on the phone;
  news briefings; device controls; memory of the owner, told and inferred kept apart.
- Answers without internet: the basics in code, and an optional local model.
- What it costs, and a monthly budget for the model; an optional natural cloud voice with its own ceiling.
- Simple mode: larger text, everything read aloud, slower and briefer.

### Security and reliability
- Policy in code for every tool call; separate endpoints for the owner and for third parties; the device credential
  for anything that widens what the assistant may do, refused without a screen lock; undo window and kill switch.
- No keys in the release APK: the owner enters them in Connectors › Services, encrypted on the phone.
- App files sealed with a Keystore key; private screens kept out of screenshots.
- Starts after a reboot or an update; restarts a hung engine; reports lost permissions; never leaves a question
  without a visible answer.

### Known limits
- Battery and memory measured on one mid-range phone only; arm64 only; not on the Play Store.
- Good conversation needs a cloud model; no wake word; no automation of other apps by design.

[0.1.0]: https://github.com/RustLangLatam/zeroclaw-android-assistant/releases/tag/v0.1.0
