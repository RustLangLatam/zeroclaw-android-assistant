# ADR-0022: What stays in the app, ZeroClaw first notwithstanding

| | |
|---|---|
| Status | Accepted |
| Date | 2026-10-17 |
| Deciders | the project's maintainers |
| Related | [ADR-0010](0010-zeroclaw-first-voice-first.md), [ADR-0014](0014-reminders-in-the-app.md), [ADR-0018](0018-tool-less-agent-for-unattended-turns.md), [ADR-0020](0020-fail-closed-daemon-start.md) |

## Context and problem

An architecture review proposed moving two jobs to ZeroClaw: setting its config through the
client's `ConfigApi` instead of writing `config.toml`, and running news briefings as ZeroClaw cron
jobs instead of the app's alarms.

## Considered options

1. Move both, as ZeroClaw first suggests.
2. Keep both in the app, and record why.

## Decision

Option 2.

- **Config.** ZeroClaw reads its MCP servers when it starts, so the bootstrap file is needed
  anyway; the app writes new MCP keys into it at every start (ADR-0020); its content changes only
  when the app is upgraded (the version mark lives in the app's preferences). A second path through
  `ConfigApi` would add a way for the two to disagree and no visible benefit.
- **News briefings.** A cron job is an agent turn that must fetch the headlines itself: it would
  call the owner's `news` tool, so other people's words (headlines) would enter a turn that holds
  the owner's tools, which ADR-0018 closed. The app fetches the headlines and hands them, as data,
  to the tool-less `scribe` agent.

## Consequences

- Good: no regression of the injection fix; one source for the config.
- Bad: two exceptions to ZeroClaw first to remember; schedules live in two places (reminders and
  news in the app, owner routines in ZeroClaw's cron).
- Routines the owner requests in conversation ("every Sunday, tell me…") do use ZeroClaw's cron
  (#47): their prompt is the owner's own words, not other people's, so they run as owner turns
  under the policy.
