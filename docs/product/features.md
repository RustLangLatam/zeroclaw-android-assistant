# Features

Factual list of what the app does, with status. **Implemented**: built and checked on the emulator
with fake data (end-to-end paths with the `fakechat` module and `scripts/e2e.sh`). **Planned**: not
built. Component internals are in the [specs](../specs/README.md); permissions in the
[access model](../specs/access-model.md).

## Interaction

| Feature | Description | Status |
|---|---|---|
| Voice and text chat | Tap the Presence Mark and speak, or type in Interactions; answers are spoken when the question was spoken or "speak aloud" is on | Implemented |
| Open microphone | Optional; listens again after each answer while the app is in the foreground; the microphone closes when the app leaves the screen | Implemented |
| Interface commands in code | "open settings", "show me my photos", "pause", "app mode", "arrange the widgets" are handled by `Commands` and never sent to the model | Implemented |
| Every setting by voice | `set_preference`, `set_contact_group`, `set_rule`, `open_section`; changes that widen access require the device credential | Implemented |
| Assistant mode and app mode | Assistant mode shows only the mark; app mode shows widgets and every screen one tap away | Implemented |
| Floating mark | Overlay over other apps while the app is in the background; tap opens, long press listens; shows new messages, approvals and the undo countdown | Implemented |
| Assist gesture | Once chosen as the digital assistant app, holding home (or the corner swipe) opens the assistant listening over any app; no wake word, no always-on microphone | Implemented |
| Presence Mark states | Resting, listening, processing, speaking, paused, offline; the ring reflects how much of the owner's profile is known | Implemented |
| Cards | Content better seen than heard (drafts, options, steps, links, videos) shown as a card with small Markdown and up to three buttons that answer the agent | Implemented |
| Interaction log | Each line in Interactions shows how it came (spoken, typed, touched) and which tools ran | Implemented |
| Languages | Spanish and English for the UI, voice and recognizer, following the phone or the owner's choice; third parties are answered in their language | Implemented |
| Palettes | Night (default), Lavender, Dawn, Coral; each shifts with the time of day | Implemented |
| Warmth | Discreet, natural or close: changes the persona, remarks and speech pace | Implemented |
| Wake word | Hands-free activation by name, opt-in, on-device | Planned |
| Owner voice recognition | Answer only the owner's voice | Planned |
| Face and name chosen with the owner | A final stage of the Presence Mark | Planned |

## Home

| Feature | Description | Status |
|---|---|---|
| Greeting and rotating line | Greeting by name; one line with state, news, messages or a question | Implemented |
| Widgets | Weather, alarm, calendar, messages, email, reminders, battery, news; two rows of four, arranged by drag or by voice | Implemented |
| Notes on Home | `show_on_home`: a flight time, a deadline, a birthday; optional expiry | Implemented |
| Lists by voice | "Add milk and bread to the shopping list", "I already bought the bread", "what do I need to buy?"; kept in ZeroClaw's memory, shown in a Home card; sending one goes through the usual approval | Implemented |

## Messages from people

| Feature | Description | Status |
|---|---|---|
| Notification channel | Reads notifications of the messaging apps the owner selects (none by default); group chats off by default | Implemented |
| Rules by group | Family (Ana): answered automatically with an undo window, 5 a day. Work (Laura): reply shown for approval with the device credential. Unknown (Pat): never answered | Implemented |
| Busy, meeting or driving mode | "I'm in a meeting until 11": do-not-disturb on, and family and work who write get one fixed reply each (written by the app, not the model); strangers never; ends on its own | Implemented |
| Introduction | The first reply in a conversation introduces the assistant, in the sender's language | Implemented |
| Messages as data | Third-party turns run in a separate agent with two tools only (`take_message`, `availability`) | Implemented |
| Activity | Who wrote, what was done, undone or not approved | Implemented |
| Sending to a contact | `send_message`: replied through the conversation's notification when the contact wrote recently; otherwise written in WhatsApp for the owner to send | Implemented |
| Reading messages | `list_messages` reads recent messages to the owner | Implemented |
| What is still unanswered | "What do I still have to answer?": family and work messages nobody answered whose chat the owner has not opened, and important emails not opened; a quiet notice after 4 h (by voice), once a day per person | Implemented |
| Possible-scam warnings | A message or email asking for codes or details, or money with urgency, plus a link or phone number, is pointed out in seconds with its reasons ("looks like"); never answered or acted on | Implemented |
| Notification actions | `act_on`: mark as read, archive, delete through the notification's own buttons | Implemented |
| Telegram and a dedicated WhatsApp account as connectors | | Planned |

## Email

| Feature | Description | Status |
|---|---|---|
| Email from notifications | Importance scored in code (family or work sender, urgent words, not bulk); quiet notice for important ones; drafts opened in the owner's email app | Implemented |
| Connected accounts | Gmail, Yahoo, iCloud or any IMAP/SMTP server, each with a setup guide, read through ZeroClaw's email channel in observer mode | Implemented |
| Acting on mail | Mark read, archive (notify), delete and send (approval with credential); the email is shown as a card before sending | Implemented |
| Bills and parcels from email | A bill with a due date or a parcel with a delivery date is offered as a reminder (two days before a bill, on the parcel's day) with a quiet notice; nothing is created until the owner taps | Implemented |
| Outlook | Requires OAuth | Planned |

## Calendar, reminders, routines

| Feature | Description | Status |
|---|---|---|
| Calendar first | `free_time` before committing; `add_event` refuses a clash until the owner decides; messages and email are held meanwhile | Implemented |
| Events | Create, move, delete (delete requires approval) | Implemented |
| Commitments | A commitment the owner makes in a message or email is added to the calendar | Implemented |
| Alarms | Set, cancel, next alarm | Implemented |
| Reminders with levels | Low: quiet notice. Normal: sounding notification. High: full-screen call screen over the lock screen, through do-not-disturb, every 5 minutes up to 6 times, until "done" or "snooze 10 minutes". Not a telephony call. Health reminders are always high, decided in code | Implemented |
| Birthdays | Contacts' birthdays from the phone: a notice the day before with a card to write something (proposed, never sent on its own) or be reminded; "who has a birthday soon?" | Implemented |
| Routines | "every Sunday at nine, tell me what's on my week": ZeroClaw cron jobs running owner turns | Implemented |

## Contacts, photos, files, apps, device

| Feature | Description | Status |
|---|---|---|
| Contacts | Search, add, edit, delete (delete requires approval); family and work groups by voice with the credential | Implemented |
| "Call Ana" | Opens the dialer with the contact's number; the owner presses call. The app never places a call (no `CALL_PHONE`) | Implemented |
| Photos | "photos from last December with my dog": date range from MediaStore, then on-device labels and face detection; nothing leaves the phone | Implemented |
| Files | "where is the loan quote?": Downloads, Documents, WhatsApp and Telegram files by name, plus emails and messages that mention it | Implemented |
| Apps and media | Most used apps first, open an app, play a search in a music, podcast or video app, media keys | Implemented |
| Device controls | Brightness, volume, flashlight, do-not-disturb, rotation; system panels for airplane mode, Bluetooth, Wi-Fi, mobile data, location, NFC | Implemented |
| Share a picture or document with the assistant | A bill, an appointment or a circular from the gallery or a PDF: text read on the phone; the due date, amount or time found in code; a reminder or a calendar entry proposed for the owner to tap; the text goes to the model only on "Ask Ksandra" | Implemented |
| Search by meaning | Documents, emails and messages searched semantically on the phone | Planned |
| Acting inside other apps | Deferred until an ADR decides whether it can be made safe | Planned |

## News, web, memory

| Feature | Description | Status |
|---|---|---|
| News | Followed topics, daily briefings or updates as they happen (Google News RSS), written by a tool-less agent | Implemented |
| Web | ZeroClaw's web search; page fetch limited to news and reference sites | Implemented |
| Videos | YouTube search results as card options; without a key, a search link that opens the YouTube app | Implemented |
| Memory | Told and inferred facts kept apart, by area; correct, delete or pause learning in Memory; kept in ZeroClaw's owner memory with a sealed copy on the phone | Implemented |
| Learned style | Short or detailed, formal or casual; important dates; a weekly reflection | Implemented |
| Catch-up | After the phone was off, the app closed or offline, a prioritized summary of what happened | Implemented |
| Encrypted memory backup | | Planned |

## Voice output

| Feature | Description | Status |
|---|---|---|
| Phone voices | The platform's text-to-speech voices, offline and free; the owner picks one | Implemented |
| Natural voice | A cloud speech model reads the agent's text verbatim (no conversation, no tools); platform voice as fallback without internet, past the monthly ceiling, or after 2.5 s without audio; short lines cached sealed on the phone ([ADR-0027](../adr/0027-assistant-voice-from-a-cloud-speech-model.md)) | Implemented |
| Full-duplex voice conversation | | Planned |

## Offline

| Feature | Description | Status |
|---|---|---|
| Offline answers | Opt-in download of a small local model (about 1 GB, on Wi-Fi), run by `llama-server` on the phone ([ADR-0025](../adr/0025-an-offline-agent-on-a-local-model.md)) | Implemented |
| Basics without a model | Reminders, alarms, next alarm, today's calendar, weather, opening an app handled in code | Implemented |
| Prompt cache | `--cache-reuse` and a saved slot restored at engine start, so the prompt is not reprocessed | Implemented |
| Continuity | When the connection returns, the online agent knows what was said offline | Implemented |
| Local model evaluated on real requests | | Planned |

## Cost

| Feature | Description | Status |
|---|---|---|
| What I cost | Model use this month, today and by agent, from ZeroClaw's `/api/cost`, in US$ when the price is configured; natural voice minutes and ceiling; also by voice (`usage`) | Implemented |
| Monthly model budget | "Don't spend more than 5 dollars a month": past the budget only the basics until next month; notices at 80 % and 100 %; by voice or in Settings | Implemented |

## Security and privacy

| Feature | Description | Status |
|---|---|---|
| Policy in code | Every tool call passes `Policy.kt`: capability, scope, autonomy (never, ask, act and notify, act), daily limit | Implemented |
| Rules ("What I can do") | Every capability's rule in plain words; changes require the device credential | Implemented |
| Device credential | Strong approvals, rule changes, contact groups and resuming require biometrics or the screen lock (screen lock alone when no biometric is enrolled) | Implemented |
| Undo window | Outward actions wait 10 to 120 s (default 30) | Implemented |
| Kill switch | "pause" stops every action and the daemon at once; resuming requires the credential | Implemented |
| Injection awareness | After reading other people's text, the model's claims about what the owner said are not trusted for the rest of the turn | Implemented |
| Sealed files | The app's files encrypted with a non-exportable Keystore key ([ADR-0023](../adr/0023-app-files-sealed-to-the-phone.md)) | Implemented |
| Loopback only, fail closed | Bearer keys on the MCP endpoints; the daemon starts only on a verified loopback address ([ADR-0020](../adr/0020-fail-closed-daemon-start.md)) | Implemented |
| Contacts by identity | Never by display name ([ADR-0019](../adr/0019-contacts-by-stable-identity.md)) | Implemented |
| Private screens | `FLAG_SECURE` on Interactions, Activity, Settings, Rules, Memory, Connectors and approvals | Implemented |
| "Permissions and privacy" | States what leaves the phone, to whom and when | Implemented |
| Connection log | A log of every connection that leaves the phone (ADR-0028, proposed) | Planned |
| Keys entered by the owner | Model, search, video and voice keys entered in Connectors › Services, encrypted on the phone; release APKs contain none ([ADR-0030](../adr/0030-service-keys-entered-by-the-owner.md)) | Implemented |

## First run

A setup in three steps (`SetupName`, `SetupNeeds`, `SetupPeople`); each permission shows whether it
is granted and is rechecked on return from system settings.

| Step | Item | Purpose |
|---|---|---|
| 1 | Name and language | how the assistant addresses the owner and introduces itself to others; Spanish or English |
| 2 | Notifications, contacts, calendar, photos | runtime permissions: to notify, find people, know when the owner is busy, find photos |
| 2 | Fingerprint (optional) | approvals with a fingerprint instead of the screen lock |
| 2 | Stay active | battery optimization off, so Android does not stop the daemon; on Xiaomi phones also autostart and the lock-screen permission |
| 2 | Call when something matters | full-screen notifications for high reminders |
| 2 | Search your files (optional) | all-files access, for documents other apps saved |
| 2 | Know which apps you use most (optional) | usage access, to pick the most used app |
| 2 | Always at hand (optional) | the overlay permission, for the floating mark |
| 2 | Read your notifications (optional) | notification access, to read incoming messages |
| 3 | Messaging apps | which apps' messages are read (none by default), and whether group chats too |
| 3 | Family / Work | from the contacts: family is answered automatically, work only with approval; anyone else is never answered |
| 3 | Speak aloud | speak answers aloud |

Saving requires the device credential when the phone has a screen lock. The assistant then
introduces itself with what it may do, generated from the rules in force (`introduction()`), so it
never claims more than the policy allows.

## Known limits

- The model key is compiled into the APK (`local.properties` at build time); the APK must not be
  shared.
- A message to a contact without a recent conversation is written in WhatsApp for the owner to send;
  Android lets no app press send.
- Without internet, conversation is slower and less capable; the basics still work.
- Battery and memory use on a range of devices is not yet measured.
