# Spec: Memory

| | |
|---|---|
| Status | Stable |
| Code | `app/.../Memory.kt`, `app/.../PhoneMemory.kt`, `app/.../Bond.kt`, `app/.../Empathy.kt`; wiring in `PhoneMcp.kt` (tools), `AssistantService.kt` (`writeIdentity`, config), `Screens.kt` (`MemoryPlace`, `Knowing`), `Theme.kt` (the ring) |
| Tests | `app/src/test/.../MemoryTest.kt`, `PhoneMemoryTest.kt`, `BondTest.kt`, `EmpathyTest.kt`; `scripts/e2e.sh`: none |
| Decisions | [ADR-0013](../adr/0013-memory-in-zeroclaw-with-phone-copy.md), [ADR-0003](../adr/0003-permissions-enforced-in-code.md), [ADR-0009](../adr/0009-gateway-only-through-kotlin-client.md), [ADR-0010](../adr/0010-zeroclaw-first-voice-first.md), [ADR-0018](../adr/0018-tool-less-agent-for-unattended-turns.md), [ADR-0023](../adr/0023-app-files-sealed-to-the-phone.md) |
| Last checked against code | 2026-10-22, commit `01f6f19` |

## Purpose

What the assistant knows about the owner: preferences, routines, people, facts, each marked as told
by the owner or inferred, grouped by area. It feeds the owner agent's persona (`USER.md`), the
Memory screen and the Presence Mark's ring (how well the owner is known). It is the owner's only:
third parties never read it. It is not chat history and not what other people wrote.

## Responsibilities

- Keep the profile (`Memory.items`, at most `MAX = 200` on the phone) and the learning switch (`Memory.learning`).
- Classify each memory into an `Area` and score how well each area is known (`Depth`).
- Mirror every write to the owner agent's memory in ZeroClaw through the Kotlin client, queue what
  could not be sent, and bring ZeroClaw's memory back as the phone copy (`PhoneMemory.sync`).
- Tell the owner agent what it knows (`Memory.describe` into `USER.md`) and how well (`Memory.profile`).
- Map depth to the ring's three strands and the mark's load (`Bond`).
- Learn the owner's preferred style and keep dated memories and a weekly reflection (`Empathy`).

## Interfaces

MCP tools, owner endpoint only (`/mcp/owner`), all through `Guard.gated(…, Capability.MEMORY, Policy.OWNER_TARGET, …)`:

| Tool | Args | Does | Returns |
|---|---|---|---|
| `remember` | `text`, `confirmed` (required), `area`, `date` (optional) | `Memory.remember(text, told, area = Area.named(area), on = Empathy.Moments.day(date))`; `told = confirmed && !Guard.heardOthers`; a told memory is shown to the owner (`Guard.onNotify`) | `Remembered: …` / `Remembered, as something you inferred: …` / `The owner paused learning: nothing was saved.` / `Nothing to remember.`; when `confirmed` was ignored, a note asking the agent to have the owner confirm it |
| `forget` | `text` | `Memory.forget(text)` | `Forgot N memories.` or, when too vague, a request to be more exact |
| `recall` | — | `Memory.describe()` | profile by area, facts and inferences apart |
| `how_well_i_know_you` | — | `Memory.profile()` | per area: depth, told/inferred counts, the gaps (never health), how to say it |

Other entry points:

- `set_preference setting=learning value=on|off` → `Memory.pause(!on)` (`PhoneMcp.kt`).
- Memory screen: `MainActivity.forget(item)` → `Memory.forget(id)`; `setLearning(on)` → `Memory.pause(!on)`.
- `PhoneNews.follow`: a newly followed topic is remembered as a told memory in `INTERESTS`.
- ZeroClaw native tools for the owner agent: `memory_store`, `memory_recall`, `memory_forget` (in
  `AssistantService.NATIVE_TOOLS`, auto-approved); what they store shows up here as conversation items.
- Uses: `client.memory.store(key, content, category, "owner")`, `client.memory.forget(key, "owner")`,
  `client.memory.search(MemorySearch(agent = "owner"))`; `AssistantService.token` / `client`.
- Hooks set by `AssistantService.onStartCommand`: `Memory.onChange` (refresh UI, `writeIdentity`),
  `Memory.onPut` / `onDrop` (`PhoneMemory.attach`, which also hooks `Notes`).

## State and data

| Where | What | Format |
|---|---|---|
| `files/memory.json` (sealed, [ADR-0023](../adr/0023-app-files-sealed-to-the-phone.md); `Guard.load` → `Memory.load`) | phone copy | `{learning, migrated, items: [{id, text, confirmed, at, area, key, conversation, on}]}` |
| ZeroClaw memory, agent `owner` | source of truth | key `profile-<id>`, content = text, category `profile:<area>:told` or `profile:<area>:inferred`, plus `:<on>` when dated (`Memory.category`) |
| ZeroClaw memory, agent `owner`, other keys | what the agent stored itself | any key and category; read back as `conversation = true`, inferred; category `widget` belongs to `Notes` |
| `files/memory-pending.txt` (sealed) | operations not yet delivered | one `store <key>` or `forget <key>` per line; the last operation per key wins (`PhoneMemory.Pending`) |
| `files/zeroclaw/agents/owner/workspace/USER.md` | persona | address + `Memory.describe()` |
| `files/style.json` (`Empathy.Style`) | how the owner likes to be talked to | cue counts (`SHORTER`, `LONGER`, `HUMOR`, `PLAIN`); written into `SOUL.md` from 3 (`SEEN`) |
| app prefs `empathy` | weekly reflection | `reflected` (ms of the last run) |

- `Item.id` is the creation time in ms for new memories; for conversation items, `key.hashCode() and 0xffffffff`.
- `Memory.fromStore` parses the category: three or four parts starting with `profile` → profile item
  with its area, told/inferred and date; anything else → conversation item, area guessed by `Area.of`.
- `learning` and `migrated` survive restarts in `memory.json` only (not in ZeroClaw).
- ZeroClaw config (`AssistantService.initialConfig`): `[memory] auto_save = false`,
  `embedding_provider = "none"`; owner `memory_recall_limit = 8`; third party, scribe and offline
  `memory_recall_limit = 0`.

## Behaviour

**Areas** (`Memory.Area`; keyword patterns from `assets/lexicon.json` (`memory.area.*`, Spanish and
English) over `Commands.normalize(text)`; most hits wins, ties to the earlier area; no hit →
`PERSONAL`): `PERSONAL` About you, `PEOPLE` Your people, `WORK` Your work, `HEALTH` Your health,
`TASTES` Your tastes, `ROUTINES` Your day, `INTERESTS` What interests you, `GOALS` Your plans,
`PLACES` Your places, and `MOOD` Your mood (transient, not counted in depth; `Area.KNOWN` excludes it).
An explicit `area` argument wins over the guess. Labels come from string resources.

**Depth** (`Memory.depth`): score = 2 per told + 1 per inferred item in the area (conversation items
count as inferred). `0` NONE, `< 5` LITTLE, `< 12` SOME, else WELL.

**`remember`**:
1. Trim, drop a trailing `.`; empty → nothing. Learning paused → nothing saved.
2. Same normalized text exists → update it: `confirmed = old || new`, `at = now`, area and date if
   given, `conversation = false` (a conversation memory said again joins the profile under its key).
3. Else a new `Item(now, …)`, key `profile-<now>`.
4. Over 200 items → oldest removed from the phone copy only (ZeroClaw keeps everything).
5. Save `memory.json`, `onChange`, then `onPut` (store in ZeroClaw on a new thread).

**`forget(text)`**: an exact normalized match, else every item whose normalized text contains the
argument when it has at least `MIN_FORGET = 4` characters and matches at most `MAX_FORGET = 3`
items; broader matches delete nothing. Each deleted item is forgotten in ZeroClaw. `forget(id)` from
the screen removes one.

**Writes** (`PhoneMemory.write`, a new thread): send the operation with the current token; when
there is no token or the call fails, add it to `memory-pending.txt`.

**Sync** (`PhoneMemory.sync`, a new thread; does nothing without a token): called after the gateway
is healthy and paired (`AssistantService.run`) and each time Memory opens. First the pending
operations are sent (failed ones stay). Once (`migrated == false`) every non-conversation item is
stored in ZeroClaw. Then `memory.search(agent = "owner")` is split: category `widget` replaces
`Notes`, the rest replaces `Memory.items` (sorted by `at`), which saves and fires `onChange`.

**What the agent is told**:
- `USER.md` (owner agent only, rewritten by `writeIdentity` on every `onChange`): "What the owner
  told you:" and "What you inferred (not confirmed; ask before relying on it):", grouped by area.
  A profile memory not refreshed within `FADE_DAYS = 180` and without a date goes into an "Unsure
  now" section; `remember` again refreshes it. Moods of the last `MOOD_DAYS = 14` are listed apart
  with their dates. Conversation items are left out (they come back through ZeroClaw's own recall).
  The other agents' `USER.md` has no profile.
- `AGENTS.md` (owner) tells it to use `remember` for the profile and `memory_store` for
  conversation threads; to ask light questions about thin areas, never about health unasked; to
  remember moods with area `mood` and dates that matter with their date.

**Empathy** (`Empathy.kt`): `Style.learn` counts cues in the owner's words (shorter, longer, humor,
plain; patterns in `empathy.cue.*`); from 3 sightings a cue is described in `SOUL.md`.
`Moments.upcoming` finds dated memories (`MM-DD` every year, `YYYY-MM-DD` once) for the day and the
day before; a daemon thread (`Empathy.run`, every six hours, skipped while paused) posts them as notes
on Home's assistant card. `Reflection` runs at most weekly: with at least 3 memories from the week, a `scribe`
turn proposes up to three conclusions, kept as inferred memories.

**Memory screen** (`MemoryPlace`, also opened by the `MEMORY` command): the "Learn about you"
toggle; "How well I know you" (`Knowing`: per area, three bars in its strand's color and the depth
label); "Memories · N", newest first, each with area, origin ("you told me", "from a
conversation", "I inferred it"), age and a forget button.

**Bond** (`Bond.kt`):
- Strands: `YOU` = PERSONAL, PEOPLE, HEALTH, TASTES; `DAY` = WORK, ROUTINES, PLACES; `WORLD` =
  INTERESTS, GOALS; drawn in the palette's ring colors A, B, C.
- `Bond.of`: per strand, the average of its areas' weights (NONE 0, LITTLE 1/3, SOME 2/3, WELL 1).
  `Ring.lit` = mean of the three; `whole` when `lit >= 1`.
- `Bond.color(known) = 0.65 + 0.35 * known`: color is visible even at zero.
- `Bond.load`: `URGENT` when a high reminder is calling; `BUSY` when approvals + outgoing +
  unopened important emails > 0 or unread messages ≥ 3; else `CALM` (the glow around the mark).
- `Panorama.curious` offers a question about the first known area at NONE, never health.

## Invariants

- Only the owner principal reaches `remember`, `forget`, `recall`, `how_well_i_know_you`; third-party
  turns get no memory tools, no recall (`memory_recall_limit = 0`) and no profile in `USER.md`.
- Unattended turns run on `scribe`, which cannot call `remember` or `forget`.
- With learning paused, `remember` saves nothing; what is known stays until deleted.
- Told and inferred stay apart everywhere: category suffix, `USER.md` sections, the Memory screen label.
- A confirmation never turns a fact back into an inference (`confirmed = old || new`).
- In an owner turn that read third-party content, `confirmed` is ignored.
- At most 200 items on the phone.
- Every phone write or delete is sent to ZeroClaw under the same key, or queued until it can be.

## Failure modes

- Gateway down or unpaired: writes go to `memory-pending.txt` and are sent before the next sync.
- A write in flight while `sync` reads may be missed; the next sync picks it up (known limitation in `PhoneMemory.sync`).
- Corrupt `memory.json`: `load` stops at the error; items read so far stay.
- `memory.search` returns what ZeroClaw returns for the agent; no paging in the app.
- Kill switch on: the four tools are refused (`Policy.decide` returns `NEVER`) and the daemon is
  stopped, so ZeroClaw's native memory tools are unavailable too.

## Security

- Principal: owner agent only, enforced by the MCP endpoint and `Policy` (`Capability.MEMORY`,
  `Risk.INTERNAL`, default rule `ACT`): no approval, logged in the activity log with the text.
- Untrusted input: `confirmed` is the model's claim, ignored after third-party content; whatever
  text the owner agent saves lands in `USER.md` on every turn, and every told memory is shown to the owner.
- `forget` needs four letters and matches at most three memories, so one vague request cannot erase many.
- The phone copy and the pending queue are sealed to the phone and excluded from backups.

## Open issues

None known.
