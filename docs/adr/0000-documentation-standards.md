# ADR-0000: Documentation standards

| | |
|---|---|
| Status | Accepted |
| Date | 2026-10-17 |
| Deciders | the project's maintainers |
| Related | [docs/README.md](../README.md), [AGENTS.md](../../AGENTS.md) |

## Context and problem

Decisions, descriptions of how components work, and project history were mixed in free-form
documents. It was not possible to tell which parts were built, why a choice was made, or whether a
document still matched the code.

## Considered options

1. Keep free-form documents and update them as needed.
2. Adopt one known standard per kind of document, each in its own folder, with a status on every
   document.

## Decision

Option 2:

| Kind | Standard | Location |
|---|---|---|
| Entry point | what the project is, how to build, run and test it | `README.md` |
| Architecture overview | arc42 sections, C4 diagrams (context, containers, components) in Mermaid | `docs/architecture/` |
| Architecture decisions | MADR (Markdown Architectural Decision Records), numbered; an accepted ADR is not rewritten, a new one supersedes it | `docs/adr/` |
| Component specs | one per component, fixed template ([_template.md](../specs/_template.md)), with a "last checked against code" date | `docs/specs/` |
| Product | vision, features and their status | `docs/product/` |

Rules:

- A PR that changes how a component works updates that component's spec in the same PR.
- A change of direction gets an ADR.
- Work tracking (tasks, multi-PR work, bugs, review findings) happens in GitHub issues and
  projects, not in the repository's documents.
- [docs/README.md](../README.md) keeps the map of the documentation.

## Consequences

- Good: one place for each question (why, how, what); status is visible on every document.
- Cost: most PRs touch documentation; specs must be re-checked when the code changes.
- Follow-up: PR review checks that a PR changing a component also updates its spec.
