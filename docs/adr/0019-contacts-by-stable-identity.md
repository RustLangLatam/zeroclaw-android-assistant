# ADR-0019: Contacts by stable identity, never by display name

| | |
|---|---|
| Status | Accepted (PR #37) |
| Date | 2026-10-17 |
| Deciders | the project's maintainers |
| Related | [security](../architecture/security.md), [spec: notification channel](../specs/notification-channel.md) |

## Context and problem

Family and work rules were keyed by the name the notification shows, which the sender can choose.

## Decision

Resolve the sender to a saved contact through the notification's person (URI or phone number) and
`PhoneLookup`; key groups by the contact's id; anything not resolved is unknown. Email importance
matches the address, never the display name.

As built: `Identity.clue` reads the person's `tel:`, `mailto:` or contacts URI, WhatsApp's
`<number>@s.whatsapp.net` key, or a number written as the name; `saved` (`MessageListener.kt`) looks
it up in the owner's contacts (`PhoneLookup`, email lookup, or the contact URI); `Identity.named`
shows any other sender as "<name> (unverified)". Email importance is taken from the address's
contact. Checked end to end: a stranger using the name "Ana" with no number gets no reply.

## Consequences

- Good: a stranger cannot become "Ana".
- Cost: apps that expose no number or person URI make their senders unknown; the setup shows which
  contacts resolve.
