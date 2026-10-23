# Documentation

Map of the project documentation. Standards and maintenance rules are defined in
[ADR-0000](adr/0000-documentation-standards.md).

| Question | Where | Standard |
|---|---|---|
| What is it for, what does it do? | [product/](product/): [vision](product/vision.md), [features](product/features.md) | — |
| How is it built, how do the parts connect? | [architecture/](architecture/README.md) | arc42, C4 |
| How is it configured, built and started? | [architecture/configuration.md](architecture/configuration.md) | — |
| How does each component work? | [specs/](specs/README.md) | one spec per component, [template](specs/_template.md) |
| Why is it built this way? | [adr/](adr/README.md) | MADR, [template](adr/_template.md) |
| How to contribute? | [CONTRIBUTING.md](../CONTRIBUTING.md) | — |
| How to report a vulnerability? | [SECURITY.md](../SECURITY.md) | — |

Roadmap and open work are tracked in GitHub issues, grouped by phase in milestones: [Phase A: robustness and trust](https://github.com/RustLangLatam/zeroclaw-android-assistant/milestone/1) (done but for the battery measurement on a real phone), [Phase B: everyday value](https://github.com/RustLangLatam/zeroclaw-android-assistant/milestone/2) (done), and [Phase C](https://github.com/RustLangLatam/zeroclaw-android-assistant/issues/24) (later).

## Rules

- A PR that changes how a component works updates its [spec](specs/README.md) and the spec's
  "last checked against code" line.
- A change of direction, or a choice someone will later ask "why?" about, gets an [ADR](adr/README.md).
  Accepted ADRs are not rewritten; a new ADR supersedes them.
- Documentation is in English. No AI vendor or model names (the model provider is configuration),
  no real personal names: the owner in examples is Milton; contacts are Laura (work), Ana (family)
  and Pat (unknown). See [AGENTS.md](../AGENTS.md).
