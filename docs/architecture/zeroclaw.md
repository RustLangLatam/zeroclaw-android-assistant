# ZeroClaw facts

What the app relies on in ZeroClaw v0.8.5, checked in its source and on the emulator. When ZeroClaw
is upgraded, re-check this page; a fact that changes may change an ADR.

## What the official build offers

- Official releases build an `aarch64-linux-android` binary, marked experimental.
- Runtime Android awareness: `zeroclaw_api::platform::is_android()`; the shell tool uses
  `/system/bin/sh` there.
- The gateway (`127.0.0.1:42617`): chat over `/ws/chat`, events over `/api/events`, cost over
  `/api/cost`, agents, memory, cron and MCP servers configured through `/api/config`.

## Facts that shaped the code

| Fact | Consequence |
|---|---|
| The secrets key file is published with `link(2)`, denied to apps by SELinux (zeroclaw-labs/zeroclaw#11525) | [ADR-0004](../adr/0004-secrets-encryption-off.md): `[secrets] encrypt = false` |
| On Android the loopback name can resolve to `::1` | [ADR-0005](../adr/0005-bind-loopback-ipv4-explicitly.md) |
| MCP servers are connected when the daemon starts; one that fails is skipped silently | the app's MCP server is open before the daemon starts |
| `estop` does not stop a running agent | [ADR-0006](../adr/0006-kill-switch-in-the-app.md) |
| An MCP call does not say which turn it belongs to | [ADR-0007](../adr/0007-third-party-turns-one-at-a-time.md) |
| Generic persona files are written into each agent's workspace (`SOUL.md` names the agent after its alias) | [ADR-0008](../adr/0008-app-writes-persona-files.md) |
| Risk profiles (`allowed_tools`, `delegation_policy`) and runtime profiles (`max_actions_per_hour`, `max_cost_per_day_cents`) are separate config sections | both written per agent ([spec](../specs/assistant-service.md)) |
| An agent with no `mcp_bundles` gets no MCP server ("omission is not a grant"); an empty `allowed_tools` means every tool | the `scribe` agent has no bundle and `allowed_tools = ["calculator"]` ([ADR-0018](../adr/0018-tool-less-agent-for-unattended-turns.md)) |
| `web_fetch.allowed_domains` matches a name exactly, or with `*` | each allowed site written as `site` and `*.site` |
| ZeroClaw drops comments when it saves `config.toml` (at pairing) | the config's version mark lives in the app's prefs |
| An MCP server entry accepts `headers` | each endpoint gets a per-process bearer key ([ADR-0016](../adr/0016-mcp-key-per-endpoint.md)) |
| Email channels support `observer_mode` | connected accounts are read without the channel answering anyone ([ADR-0026](../adr/0026-google-email-through-zeroclaws-email-channel.md)) |
| A `llamacpp` provider points at any chat-completions-compatible local server | the `offline` agent runs on `llama-server` on loopback ([ADR-0025](../adr/0025-an-offline-agent-on-a-local-model.md)) |
| A long owner history (66–72 messages) made the model stop calling tools | [ADR-0012](../adr/0012-owner-history-24-messages.md) |

## Notes by area
Checked in the source; the config above was also loaded with a local ZeroClaw build, and every key
read back with the value intended.

- **Tool names.** Web search is `web_search_tool`, not `web_search`; a wrong name in
  `allowed_tools` silently leaves the tool out. Tools with `__` in their name (MCP tools) are
  always let through; an empty `allowed_tools` means no restriction at all.
- **Approvals.** At `supervised`, any tool not in `auto_approve` asks over the WebSocket
  (`approval_request`, 120 s). Every tool the app allows is auto-approved: the app's policy
  decides, not ZeroClaw's prompt.
- **Not used on Android:** `shell`, the `schedule` tool (shell jobs), `text_browser` and the
  coding CLIs start processes. `http_request` is off.
- **Memory.** SQLite by default, one database filtered by agent. Recall runs every turn
  (`memory_recall_limit`, `[memory] min_relevance_score`). Embeddings default to `none`
  (keyword search, no key). `auto_save` (on by default) stores each user message and makes one
  more model call per WebSocket turn to consolidate facts: off here, for cost and because a third
  party's messages must not be kept. The backend of an agent is fixed once it has data, so it is
  not changed.
- **Sessions.** `/ws/chat?agent=owner&session_id=…` resumes a conversation; `session_start`
  returns `session_id`, `resumed` and `message_count`. History is limited by
  `max_history_messages` and `max_context_tokens`, with optional `context_compression`.
- **Scheduling.** `cron_add` with `job_type: "agent"` runs a prompt on a schedule (`cron`, `at`,
  `every`). The gateway WebSocket is not a delivery channel: results come over SSE
  (`GET /api/events`, `cron_result`) or `GET /api/cron/{id}/runs`. In a cron run nobody can
  approve, so every tool it uses must be auto-approved.
- **Generation.** `temperature`, `max_tokens` and others go on the provider entry
  (`[providers.models.<type>.<alias>]`); the owner agent uses its own alias.
- **Persona.** SOUL, IDENTITY, USER, AGENTS, TOOLS, HEARTBEAT, BOOTSTRAP and MEMORY (`.md`, up to
  20,000 characters each) are read every turn; skills go in `workspace/skills/<name>/SKILL.md`.
