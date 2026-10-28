# ADR-0032: Photos seen on the phone, explained by the model only on request

| | |
|---|---|
| Status | Accepted |
| Date | 2026-10-25 |
| Deciders | the project's maintainers |
| Related | [phone-abilities.md](../specs/phone-abilities.md#taking-a-photo), [security.md](../architecture/security.md#prompt-injection), issue #57 |

## Context and problem

The owner wants to take a photo by voice and have it understood: always classified, so a later search finds it, and
explained when asked. A photo is personal data; sending every photo to a cloud model would ship the owner's life off
the phone for the sake of a label.

ZeroClaw v0.8.5 accepts pictures as `[IMAGE:<path, URL or data URI>]` markers in a message or a tool result: it loads
the file, sends it to a model that can see, and refuses the turn with a capability error when the model cannot. It
loads any marker it finds, whoever wrote it.

## Considered options

1. Every photo to the cloud model, for a description and labels.
2. On-device labels and text recognition always; the cloud model only when the owner asks for an explanation.
3. On-device only, never the cloud model.

## Decision

Option 2. The labels, faces and text come from the on-device models the photo search already uses (ML Kit, bundled),
so classifying costs nothing and nothing leaves the phone. *Explain it* sends the photo, at most 1280 px, with what the
phone found, to the tool-less `scribe` agent in a session of its own. When the main model cannot see pictures, ZeroClaw
hands that turn to the model the owner set for pictures (`[multimodal] vision_model_provider`, Connectors › Services ›
Photos); every other turn stays on the main model. The owner's session never holds the picture: ZeroClaw keeps it in
the history, and every later turn there went to the picture model. The owner's agent hears the scribe's answer with
the next message. If no model could look at the photo, the owner's agent explains from the labels and text.
The app takes the photo through the camera app (`ACTION_IMAGE_CAPTURE`), so it holds no camera permission and the
owner always presses the shutter.

Since ZeroClaw loads any marker, the app breaks markers in every text it hands over from others (`Guard.inert`):
third-party turns, scribe prompts, tool results and the owner's chat text. Only the app adds a marker.

## Consequences

- Good: photos are searchable by content and text with no data sent; the cloud sees a photo only when the owner taps.
- Good: a message cannot make the daemon attach one of the owner's files to a stranger's turn.
- Good: what the photo shows reaches a model with no tools; a sign in a photo cannot trigger an action.
- Bad / cost: on-device labels are coarse and in English; without a model that sees pictures, an explanation is
  limited to them. A second model means a second key and price.
- Follow-up: search by the words read in a photo, not just its kind.
