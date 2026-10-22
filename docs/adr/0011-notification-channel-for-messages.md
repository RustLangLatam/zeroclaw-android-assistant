# ADR-0011: Messages through notifications

| | |
|---|---|
| Status | Accepted |
| Date | 2026-10-16 |
| Deciders | the project's maintainers |
| Related | [spec: notification channel](../specs/notification-channel.md), PR #2 |

## Context and problem

The assistant needs to read messages sent to the owner and answer on the owner's behalf, in the
apps already in use (WhatsApp, Telegram, Signal, SMS), without access to the owner's accounts.

## Considered options

1. A `NotificationListenerService` reading MessagingStyle notifications and answering through the
   notification's reply action (`RemoteInput`).
2. ZeroClaw's channels (WhatsApp Web, Telegram bots): separate accounts, little gain.
3. Becoming the default SMS app: replaces the owner's messaging app.
4. An `AccessibilityService`: drives any app, restricted on Google Play, the highest risk.

## Decision

Option 1, enabled by the owner, for the apps the owner selects. Replies are the capability
`notification_reply` under the policy (work contacts with approval, family autonomously within
limits, others never) and the undo window.

## Consequences

- Good: no accounts; works with every messaging app that posts replyable notifications.
- Bad: identity comes from what the notification shows
  ([ADR-0019](0019-contacts-by-stable-identity.md)); only conversations with a live notification
  can be answered this way.
