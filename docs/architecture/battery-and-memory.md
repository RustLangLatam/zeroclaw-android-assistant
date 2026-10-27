# Battery and memory

What Ksandra costs on a real phone, and how to measure it again. Issue
[#9](https://github.com/RustLangLatam/zeroclaw-android-assistant/issues/9) (A8) sets the target: under 3 % of the
battery a day at rest, without the local model.

## Measured

**Phone:** Redmi 13C 4G (`23108RN04Y`), arm64, Android 15 (HyperOS 2), battery 4858 mAh, 5.8 GB of RAM.
**Build:** release (R8), with the foreground service and the ZeroClaw daemon running. No email account was connected,
and offline answers (the local model) were off.
**Window:** 2026-10-25 20:14 to 2026-10-27 20:06, on battery the whole time: 47 h 51 min, screen off 99.7 % of it
(7 min on, 28 times).

| | Battery used | Share |
|---|---|---|
| The whole phone | 632–680 mAh (computed 687) | about 13 % of the battery in two days |
| **Ksandra (app and daemon, one UID)** | **6.7 mAh**: 6.2 in the foreground service, 0.6 with the app open | **0.07 % of the battery a day** |
| For scale: the system UID | 156 mAh, most of it 33 min of flashlight | |

- **Target met:** about 3.4 mAh a day against a limit of 146 mAh (3 % of 4858), some forty times under it.
- **CPU:** 6 min 11 s in two days, nearly all in the foreground service.
- **Wake locks:** 0.1 s of partial wake lock blamed on the app. The service does not keep the phone awake; it stays
  in memory and runs when an event arrives.
- **The service never stopped:** "Fg Service for: 1d 23h 51m", the whole window.

**Memory** (at the end of the window, idle):

| Process | Memory |
|---|---|
| App (`org.rustlanglatam.zeroclaw.android`) | 130 MB PSS, 166 MB RSS, plus 76 MB swapped (compressed) |
| Daemon (`libzeroclaw.so`) | 8 MB resident: idle, the system keeps most of it compressed |
| The phone | 2.0 GB available of 5.8 GB |

## Not measured yet

- **With an email account connected:** ZeroClaw's email channel keeps an IMAP IDLE connection per account and
  reconnects every 25 minutes; its cost on Wi-Fi and on mobile data is the open question in #9.
- **With offline answers on:** the local model takes about 1 GB of RAM while loaded.

## How to measure

1. Install a release build, open the app once (it starts the service) and leave it as the owner uses it. Do not
   reinstall during the window: an install restarts the app and its counters.
2. Charge the phone, unplug it, and leave it at rest for at least 24 hours.
3. Plug it into the computer and, before anything else (charging resets the "since charged" counters on some phones):

   ```sh
   adb shell dumpsys batterystats --charged > batterystats.txt
   adb shell pm list packages -U org.rustlanglatam.zeroclaw.android   # uid:10074 → u0a74
   adb shell dumpsys meminfo org.rustlanglatam.zeroclaw.android | grep -E 'TOTAL (PSS|RSS)'
   adb shell ps -A -o PID,RSS,NAME | grep -iE 'zeroclaw|llama'
   ```

4. In `batterystats.txt`: the window is "Time on battery"; the phone's drain is "Computed drain" and "actual drain"
   under "Estimated power use"; the app's is its `UID u0aNN:` line there (the daemon runs under the app's UID, so it is
   included), and its wake locks, foreground service time and CPU are in the `u0aNN:` section further down.
5. Divide the app's mAh by the days on battery and by the capacity to get the share a day; compare it with 3 %.
