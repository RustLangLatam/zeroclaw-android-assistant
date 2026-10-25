# ADR-0033: Recommended models in a data file

| | |
|---|---|
| Status | Accepted |
| Date | 2026-10-25 |
| Deciders | the project's maintainers |
| Related | [ADR-0030](0030-service-keys-entered-by-the-owner.md), [ADR-0031](0031-voice-services-named-in-a-data-file.md), [ADR-0032](0032-photos-seen-on-the-phone-explained-on-request.md) |

## Context and problem

The services form takes the model provider and model from ZeroClaw's catalog, which lists a provider's models but
does not say which one to use. A live list has dozens of ids, many of them unsuited (old, preview, text-only for
photos), so an owner who chose a provider was left with an empty model field and no hint. On the phone, a photos model
left without its model was never written to the config, and the photo went to a model that cannot see.

## Considered options

1. A data file in the APK naming, per ZeroClaw provider family, a recommended model for chat and one for pictures.
2. A neutral rule over the live list (the first id, or by words in the id).
3. Leave the model to the owner.

## Decision

Option 1. `app/src/main/assets/models.json` maps each provider family to its `chat` and `vision` model. Choosing a
provider fills the model with the recommended one when the provider's list has it (or there is no list to check),
else with the list's first (`ModelCatalog.pick`). The photos group offers only the providers with a `vision` entry; any
other model can still be typed. The form also marks what each group in use still needs (`Connectors.required`): the
model, the key and, for a custom gateway, its URL; the key is the only field left empty, shown in red until entered,
and a saved key stops counting when its provider changes. Saving waits until nothing is marked.

## Consequences

- AGENTS.md's no-vendor-names rule has a second exception: this file.
- A recommendation that goes stale is replaced by the list's first until the file is updated; it is one line.
- Search keys stay optional: some search providers need none.
