#!/usr/bin/env bash
# Captures the README screenshots from the emulator: each screen, the Presence Mark states and simulated flows.
#   ANDROID_SERIAL=emulator-5554 scripts/screenshots.sh [language: en|es] [out dir]
# The debug app and the fakechat module installed; the owner is Milton, the fake contacts Laura, Ana and Pat.
set -euo pipefail
LANG_TAG=${1:-en}
OUT=${2:-docs/images}
APP=org.rustlanglatam.zeroclaw.android
FAKE=org.rustlanglatam.zeroclaw.fakechat
case "${ANDROID_SERIAL:-}" in emulator-*) ;; *) echo "Emulator only: set ANDROID_SERIAL=emulator-…" >&2; exit 1 ;; esac
mkdir -p "$OUT"

go() { adb shell am start -n "$APP/.MainActivity" "$@" >/dev/null 2>&1; }
open() { go --es section "$1"; sleep 1.5; }
# A picture of the screen, [2] cropped as WxH+X+Y if given, 540 wide.
shot() {
  local tmp; tmp=$(mktemp --suffix .png)
  adb exec-out screencap -p > "$tmp"
  local crop=""; [ -n "${2:-}" ] && crop="crop=${2},"
  ffmpeg -loglevel error -y -i "$tmp" -vf "${crop}scale=540:-1" -q:v 3 "$OUT/$1.jpg"
  rm -f "$tmp"; echo "$OUT/$1.jpg"
}
spot() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || return 1
  local b; b=$(adb shell cat /sdcard/ui.xml | grep -o "text=\"$1[^\"]*\"[^>]*bounds=\"\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]" | head -1 | grep -o '\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]')
  [ -n "$b" ] || return 1
  set -- $(echo "$b" | grep -o '[0-9]*'); echo "$(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))"
}
press() { for _ in $(seq "${2:-20}"); do local xy; xy=$(spot "$1") && { [ "${3:-}" = look ] || adb shell input tap $xy; return 0; }; sleep 1; done; return 1; }
number() { case "$1" in Laura) echo +34600000001;; Ana) echo +34600000002;; Pat) echo +34600000003;; esac; }
post() { adb shell am broadcast -n $FAKE/.Post --es from "$1" --es number "$(number "$1")" --es text "'$2'" >/dev/null; }
t() { [ "$LANG_TAG" = en ] && echo "$1" || echo "$2"; }

# Setup: screen on, the chosen language, owner Milton, night palette, nothing pending.
adb shell input keyevent 224
go --es language "$LANG_TAG"; sleep 8
# A new conversation and an empty activity log: nothing from earlier runs, no other names.
go --ez fresh true --ez clearActivity true; sleep 3
# Restarts the app so the screen and the agent persona start in this language with no earlier state.
adb shell am force-stop $APP; sleep 1
adb shell am start-foreground-service -n "$APP/.AssistantService" >/dev/null 2>&1 || true
go; sleep 25
go --ez voice false --ez stop false --ez clean true --es owner Milton --es palette night --ez hideCosts false --ez allowCapture true \
  --es face "''" --es faceVideo "''" --es pose "''" --ez ringFull false --ez keepMark false --ez appMode false
sleep 8
open home; sleep 3
MARK=640:640:220:977

shot home
shot mark-rest "$MARK"
go --ez keepMark true --es pose listening; sleep 1.2; shot mark-listening "$MARK"
go --es pose thinking; sleep 1.2; shot mark-thinking "$MARK"
go --es pose speaking --es narrate "'$(t "I am speaking now." "Ahora estoy hablando.")'"; sleep 1.5; shot mark-speaking "$MARK"
go --es pose "''"; sleep 3
go --ez keepMark false --es pose "''" --ez ringFull true; sleep 3; shot mark-ring-full "$MARK"
go --ez ringFull false; sleep 2
go --ez appMode true; sleep 2; shot home-app-mode
go --ez appMode false; sleep 1.5
go --ez arrange true; sleep 1.5; shot widgets-arrange
go --ez arrange false; sleep 1

for p in lavender dusk coral night; do go --es palette $p; sleep 1.5; shot "palette-$p"; done

open interactions; go --es say "'$(t "What do I have tomorrow?" "¿Qué tengo mañana?")'"; sleep 20; shot conversation
go --es card "'$(t "Five oven-roasted chicken videos" "Cinco vídeos de pollo al horno")'" --es cardBody "'1. [$(t "Crispy roast chicken" "Pollo al horno crujiente")](https://www.youtube.com/results?search_query=roast+chicken)\n2. [$(t "Lemon and herb chicken" "Pollo al limón con hierbas")](https://www.youtube.com/results?search_query=lemon+chicken)\n3. [$(t "Chicken with potatoes" "Pollo con patatas")](https://www.youtube.com/results?search_query=chicken+potatoes)'"
sleep 2; shot card
go --ez closeCard true; sleep 1

open home
post Ana "$(t "Hi! Are you coming for lunch on Sunday?" "¡Hola! ¿Vienes a comer el domingo?")"
press "$(t Undo Deshacer)" 60 look; sleep 2; shot reply-undo
press "$(t Undo Deshacer)" 5; sleep 1
post Laura "$(t "Can we move the meeting tomorrow to four?" "¿Podemos pasar la reunión de mañana a las cuatro?")"
press "$(t Approve Aprobar)" 60 look; sleep 3; shot reply-approval
press "$(t Decline Rechazar)" 5; sleep 1
post Pat "$(t "Hi, who is this?" "Hola, ¿con quién hablo?")"; sleep 10
open home; open activity; sleep 1; shot activity

open rules; shot rules
open privacy; shot privacy
open offline; shot offline
open voice; shot voice
open presence; shot presence
open people; shot people
open cost; shot cost
open connectors; shot connectors
open add_email; shot connectors-add-email
open home
go --es card "'$(t "To Laura" "Para Laura")'" --es cardBody "'**$(t To Para):** laura@$(t work trabajo).com\n**$(t Subject Asunto):** $(t "Meeting tomorrow" "Reunión de mañana")\n\n$(t "Hi Laura, four tomorrow works perfectly for me. See you then." "Hola Laura, mañana a las cuatro me viene perfecto. Nos vemos.")'"
sleep 2; shot email-card
go --ez closeCard true; sleep 1
# Photo and file searches run through the agent: they need sample images (with a dog) and documents (a loan budget)
# on the emulator, and all-files access granted to the app; otherwise the screens show their empty state.
go --ez fresh true; sleep 6
go --es say "'$(t "Find the photos of my dog" "Busca las fotos de mi perro")'"; sleep 25
open photos; shot photos
go --es say "'$(t "Where is the loan budget document?" "¿Dónde está el documento del presupuesto del préstamo?")'"; sleep 25
open files; shot files
open news; shot news
open memory; shot memory

go --es call "'$(t "Take the blood pressure pill" "Tomar la pastilla de la tensión")'"
press "$(t Answer Contestar)" 30 look; sleep 1; shot call-ringing
press "$(t Answer Contestar)" 5; sleep 3; shot call-answered
press "$(t "Done it" "Ya lo hice")" 20; sleep 2
open home

# Planned face stage, if files/face.jpg exists in the app's storage: ring fully lit, the face inside a rim of its colors.
if adb shell run-as $APP ls files/face.jpg >/dev/null 2>&1; then
  go --ez keepMark true --ez ringFull true; sleep 3
  go --es face face.jpg; sleep 2; shot face "$MARK"
  go --es face "''" --ez ringFull false --ez keepMark false; sleep 1
fi

# Composite strips for the README: the mark's states side by side, and the palettes.
ffmpeg -loglevel error -y -i "$OUT/mark-rest.jpg" -i "$OUT/mark-listening.jpg" -i "$OUT/mark-thinking.jpg" -i "$OUT/mark-speaking.jpg" -i "$OUT/mark-ring-full.jpg" \
  -filter_complex "[0][1][2][3][4]hstack=5,scale=1350:-1" -q:v 3 "$OUT/mark-states.jpg"
ffmpeg -loglevel error -y -i "$OUT/palette-night.jpg" -i "$OUT/palette-lavender.jpg" -i "$OUT/palette-dusk.jpg" -i "$OUT/palette-coral.jpg" \
  -filter_complex "[0][1][2][3]hstack=4,scale=1080:-1" -q:v 3 "$OUT/palettes.jpg"

# The floating mark over another app.
adb shell input keyevent 3; sleep 3; shot floating-mark
echo done
