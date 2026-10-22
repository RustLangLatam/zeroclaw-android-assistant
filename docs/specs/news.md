# Spec: News

| | |
|---|---|
| Status | Stable |
| Code | `app/.../News.kt`, `app/.../PhoneNews.kt` (`PhoneNews`, `NewsReceiver`); tools in `PhoneMcp.kt`; `Turns.scribe` in `MessageListener.kt`; `NewsPlace` in `Screens.kt`; widget in `HomeWidgets.kt`; missed checks in `CatchUp.kt` |
| Tests | `app/src/test/.../NewsTest.kt`, `BackOnlineTest.kt` (`News.missed`); `scripts/e2e.sh`: none (debug: `--ei news <id>` runs a check now, `--ei watch <id>` a watch) |
| Decisions | [ADR-0010](../adr/0010-zeroclaw-first-voice-first.md), [ADR-0018](../adr/0018-tool-less-agent-for-unattended-turns.md), [ADR-0003](../adr/0003-permissions-enforced-in-code.md), [ADR-0022](../adr/0022-what-stays-in-the-app.md), [ADR-0023](../adr/0023-app-files-sealed-to-the-phone.md) |
| Last checked against code | 2026-10-22, commit `01f6f19` |

## Purpose

Keep the owner up to date on topics the owner asked to follow ("keep me posted on…"), and give the
owner agent current headlines to talk about anything. Headlines come from the Google News RSS search
feed (no account, no key; only the topic leaves the phone). Briefings are written by the `scribe`
agent in the owner's language, with a plain fallback. It is not a news reader or a web search.

## Responsibilities

- Keep the followed topics and when to report on each; the headlines already reported per topic.
- Fetch and parse the feed; keep only fresh headlines.
- Ask the `scribe` agent for a briefing; fall back to plain headlines.
- Watch daily topics between briefings for urgent changes.
- Deliver the briefing (chime or notification, aloud in the app) and keep the last 30 for the News screen.
- Set an alarm per topic; set them again at start; check topics missed while offline.

## Interfaces

MCP tools, owner endpoint only, through `Guard.gated(…, Capability.NEWS, Policy.OWNER_TARGET, …)`:

| Tool | Args | Does / returns |
|---|---|---|
| `follow_topic` | `topic` (required), `time` HH:MM (default `08:00`), `every_hours` 1–24 (wins over `time`) | `PhoneNews.follow`; `Following "q" (#id): a briefing every day at HH:MM when there is news.` or `checked every N h, told when there is something new.` |
| `unfollow_topic` | `topic` (words or `#id`) | `PhoneNews.unfollow`: both alarms cancelled, topic removed |
| `list_topics` | — | `News.describe`: `#id "query" every day at HH:MM` / `as it happens (checked every N h)` |
| `news` | `topic` | `News.headlines(topic, fetch(topic))`: up to 10, framed as data from a news feed; on error `The news could not be read now (no connection?).` Marks the turn as having read third-party content |

Other:
- `NewsReceiver` (not exported): alarm broadcast with extra `topic` → `PhoneNews.check(id)`; with `watch = true` → `PhoneNews.watch(id)`.
- `Turns.scribe(context, prompt, done)`: a new `scribe` session over `/ws/chat`; answer or `null` after `OWNER_MS = 120 s`.
- `PhoneNews.onBriefing`: set by `MainActivity` to say the briefing aloud (`remark`).
- Notification channels `news` (default importance) and `news-important` (high importance), both
  through `Chime.channel`; tap opens `MainActivity` with a sealed extra `place = Place.NEWS`.
- `CatchUp.missed` → `News.missed(topics, since, now)` → `check(id)` for each topic whose time passed while offline.
- Readers: the News screen (`NewsPlace`; menu option, tile, `NEWS` command), the Home widget `NEWS`
  (last briefing), the panorama (last briefing under 24 h).

## State and data

| File | Content | Written by |
|---|---|---|
| `files/topics.json` (sealed) | `[{id, query, time, everyHours (0 = none), seen: [link…]}]` | `follow`, `unfollow`, `check`, `watch` |
| `files/briefings.json` (sealed) | `[{topic, at, text, headlines: [{title, source, link, published}]}]`, last 30 | `keep` |

Both are written under the instance lock, then `Ui.refresh()`. `seen` keeps at most `SEEN = 60`
links per topic. New id = max id + 1. Alarms are in `AlarmManager` only. A new topic is also
remembered as an owner interest in [Memory](memory.md) (area `INTERESTS`).

## Behaviour

**Feed** (`News.feed`): `https://news.google.com/rss/search?q=<query> when:2d&hl=<language>&gl=<country>&ceid=<country>:<language>`.
The country is where the phone is: its network, else its SIM, else the locale's country, else `US`.
The language is `News.edition`: `es` for Spain, `es-419` elsewhere. HTTP through
`AssistantService.http` with `callTimeout(15 s)`; a non-2xx response → no headlines.

**Parse** (`News.parse`): regex over `<item>…</item>`; `title`, `source`, `link`, `pubDate`; CDATA
and common entities unescaped, tags stripped; the " - Source" suffix removed from the title; blank
titles dropped.

**Fresh** (`News.fresh`): headlines whose link is not in `seen`, de-duplicated by normalized title;
the new links are appended to `seen` (last 60 kept).

**Follow**: a topic with the same normalized query is updated (time or `every_hours`) and
rescheduled. A new one first fetches the feed and marks everything there as seen, so the first
briefing reports only what came after.

**Schedule** (`schedule`): `every_hours` → now + N h; else today at `time` (unparsable → 08:00), or
tomorrow if that is within a minute. `setWindow(RTC_WAKEUP, at, 10 min)`: inexact, no exact-alarm
permission needed. Request code `9400 + id`. `rescheduleAll()` at the first service start
(`AssistantService.onStartCommand`) schedules every check and watch.

**Check** (`check(id)`, new thread): schedule the next check first; kill switch on → stop; fetch
(failure logged, stop); save `seen`; nothing new → nothing said. Otherwise `Turns.scribe` with
`News.ask`: the owner's name, the topic, up to 8 headlines marked as data from a news feed, asking
for 2–4 warm sentences in `Language.current`, the sources named lightly, no lists or opinions, facts
and every side on politics, no tool calls. Blank or failed answer → `News.plain`: the topic plus 3
headlines with sources. The briefing keeps up to 8 headlines.

**Watch** (`watch(id)`, daily topics only, every `News.WATCH_HOURS` = 3 h, window 15 min, request
code `19400 + id`; cancelled for interval topics): schedule the next watch; kill switch on → stop;
fetch; nothing new → nothing. Otherwise `Turns.scribe` with `News.watch`, which asks whether a
headline is a big change the owner would want now. The answer must start with `URGENT` and carry at
least 10 characters of text (`News.urgent`, checked in code); anything else waits for the briefing,
and its headlines stay unseen. When urgent, the headlines are marked seen and delivered as an
important briefing.

**Deliver** (`tell`): keep; `NOTICE` gesture; a floating notice "News · <topic>" ("Important · <topic>"
when important); `onBriefing`; app visible → chime only; else a notification (big text; important:
channel `news-important`, high importance), id `500 000 + floorMod(topic.hashCode(), 1000)`.

**News screen**: the topics with their schedule (daily at HH:MM, or as it happens), when the first
briefing will come and how to stop following a topic; then briefings newest first, each with up to
3 headlines; tapping a headline opens its link (`ACTION_VIEW`).

## Invariants

- Only the topic text leaves the phone for the feed.
- A headline link is reported at most once per topic (within the last 60).
- Nothing new → no turn, no notification.
- Every briefing is kept even when the agent fails (plain fallback).
- While paused (kill switch) no briefing or watch turn runs, but checks stay scheduled.
- Briefings and watches are written by `scribe`, which has no MCP tools: headlines cannot act.

## Failure modes

- No network or feed error: the check ends silently; the next one is already scheduled; after
  reconnecting, topics whose time passed are checked (`News.missed`).
- Gateway down or unpaired: `Turns.open` waits for a token; after 120 s the plain fallback is delivered.
- Feed format change: the regex parse yields nothing; topics stay silent.
- Process killed: alarms survive; reboot: lost until the service starts.

## Security

- Principal: owner agent only (`Capability.NEWS`, `Risk.INTERNAL`, default rule `ACT`).
- Headlines are untrusted text from the web. In briefings they reach only the tool-less `scribe`
  agent; through the `news` tool they reach the owner agent marked as data, and the turn's claims
  about what the owner said are no longer trusted (`Guard.heardOthers`).
- A briefing turn is checked against the kill switch only before it starts; each opens a new `scribe` session.

## Open issues

- The feed edition is always a Spanish one (`es` or `es-419`), whatever the assistant's language;
  briefings are written in the assistant's language from those headlines.
- Briefings stay scheduled by the app, not by ZeroClaw's cron ([ADR-0022](../adr/0022-what-stays-in-the-app.md)).
