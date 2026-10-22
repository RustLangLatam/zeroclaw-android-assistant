# ADR-0008: The app writes the agents' persona files

| | |
|---|---|
| Status | Accepted |
| Date | 2026-10-16 |
| Deciders | the project's maintainers |
| Related | [spec: assistant service](../specs/assistant-service.md) |

## Context and problem

ZeroClaw writes generic workspace files on first start (`SOUL.md` names the agent after its alias,
"You are **owner**", and asks for emoji) and reads them into every system prompt.

## Decision

`AssistantService.writeIdentity` overwrites `IDENTITY.md`, `SOUL.md`, `USER.md`, `AGENTS.md` and
`TOOLS.md` for each agent on every start and whenever their content changes (memory, warmth, form
of address, setup, rules). `TOOLS.md` is generated from the app's tools and the rules in force, so
the agent's description of its abilities matches the policy.

## Consequences

- Good: one persona, always current, consistent with the policy.
- Bad: the files are prompt text, never a security boundary ([ADR-0003](0003-permissions-enforced-in-code.md)).

Note: the persona files were first written in Spanish; they are now written in English and instruct
the agent to answer in the owner's language.
