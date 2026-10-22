# Writes app/src/main/res/drawable/splash_mark.xml: the Presence Mark as the button it is in the app
# (disc, 25 LEDs on the open ring in the strands' colors, the pearl), the LEDs lighting one by one and
# then the pearl, for the system splash. Same proportions as PresenceSymbol (Theme.kt), u = 1.6 dp.
# Also writes splash_mark_lit.xml next to it: the same mark fully lit, which the app shows where the splash
# was until the splash is handed over to it (the system lets the app's first frame through for a moment).
#   python3 scripts/splash.py app/src/main/res/drawable/splash_mark.xml
import math, sys

U, C = 1.6, 120.0                     # dp per unit; the icon is 240 x 240 dp, its centre at 120
DISC, RING, LED, PEARL = 47, 40, 1.8, 6.5
START, SWEEP, N = 8.7, 296.7, 25
A, B, Cc = (0xE2, 0xE7, 0xEC), (0xB3, 0xBC, 0xC5), (0x80, 0x8B, 0x96)   # The night palette's ring colours (the default palette)


def lerp(x, y, f):
    return tuple(round(a + (b - a) * f) for a, b in zip(x, y))


def along(f):
    if f < 0.28: return A
    if f < 0.38: return lerp(A, B, (f - 0.28) / 0.1)
    if f < 0.62: return B
    if f < 0.72: return lerp(B, Cc, (f - 0.62) / 0.1)
    return Cc


def circle(cx, cy, r):
    return f"M{cx - r:.2f},{cy:.2f}a{r:.2f},{r:.2f} 0,1 1,{2 * r:.2f},0a{r:.2f},{r:.2f} 0,1 1,{-2 * r:.2f},0"


paths, anims, lit = [], [], {}
# The disc as PresenceSymbol's button draws it on the night palette: lit from the top left (the sheet a little whiter)
# into the graphite, and a rim light above, a shade below.
R = DISC * U
paths.append(f'<path android:name="disc" android:pathData="{circle(C, C, R)}"><aapt:attr name="android:fillColor">'
             f'<gradient android:type="linear" android:startX="{C - R:.1f}" android:startY="{C - R:.1f}" android:endX="{C + R:.1f}" android:endY="{C + R:.1f}">'
             '<item android:offset="0" android:color="#FF2C2F34" /><item android:offset="1" android:color="#FF0C0D0F" /></gradient></aapt:attr></path>')
paths.append(f'<path android:name="rim" android:pathData="{circle(C, C, R - 0.6)}" android:strokeWidth="1.2"><aapt:attr name="android:strokeColor">'
             f'<gradient android:type="linear" android:startX="{C:.1f}" android:startY="{C - R:.1f}" android:endX="{C:.1f}" android:endY="{C + R:.1f}">'
             '<item android:offset="0" android:color="#BFFFFFFF" /><item android:offset="0.5" android:color="#00FFFFFF" /><item android:offset="1" android:color="#1A000000" /></gradient></aapt:attr></path>')
for k in range(N):
    f = k / (N - 1)
    a = math.radians(START + f * SWEEP)
    x, y = C + math.cos(a) * RING * U, C + math.sin(a) * RING * U
    r, g, b = along(f)
    paths.append(f'<path android:name="led{k}" android:pathData="{circle(x, y, LED * U * 2.2)}" android:fillColor="#{r:02X}{g:02X}{b:02X}" android:fillAlpha="0" />')
    paths.append(f'<path android:name="core{k}" android:pathData="{circle(x, y, LED * U)}" android:fillColor="#{r:02X}{g:02X}{b:02X}" android:fillAlpha="0" />')
    for name, to in ((f"led{k}", 0.14), (f"core{k}", 1.0)):
        lit[name] = to
        anims.append(f'<target android:name="{name}"><aapt:attr name="android:animation"><objectAnimator android:propertyName="fillAlpha" '
                     f'android:valueFrom="0" android:valueTo="{to}" android:startOffset="{60 + k * 24}" android:duration="180" /></aapt:attr></target>')
# The eye, as PresenceSymbol draws it (glassBack, glassFront): a ball of glass set in the button, the pearl inside.
EYE = 16
G = EYE * U
SHEET, VOID = (0x16, 0x18, 0x1B), (0x0C, 0x0D, 0x0F)
LIT = lerp(SHEET, (255, 255, 255), 0.16)
def hexa(rgb, a=0xFF): return "#%02X%02X%02X%02X" % ((a,) + tuple(rgb))
paths.append(f'<path android:name="socket" android:pathData="{circle(C, C, G * 1.22)}"><aapt:attr name="android:fillColor">'
             f'<gradient android:type="radial" android:centerX="{C:.1f}" android:centerY="{C:.1f}" android:gradientRadius="{G * 1.22:.1f}">'
             '<item android:offset="0" android:color="#73000000" /><item android:offset="0.78" android:color="#73000000" /><item android:offset="1" android:color="#00000000" /></gradient></aapt:attr></path>')
paths.append(f'<path android:name="ball" android:pathData="{circle(C, C, G)}"><aapt:attr name="android:fillColor">'
             f'<gradient android:type="radial" android:centerX="{C - 0.3 * G:.1f}" android:centerY="{C - 0.35 * G:.1f}" android:gradientRadius="{G * 1.45:.1f}">'
             f'<item android:offset="0" android:color="{hexa(LIT)}" /><item android:offset="0.55" android:color="{hexa(VOID)}" /><item android:offset="1" android:color="#FF000000" /></gradient></aapt:attr></path>')
paths.append(f'<path android:name="glow" android:pathData="{circle(C, C, G)}" android:fillColor="#FF4FA8FF" android:fillAlpha="0" />')
# The pearl as PresenceSymbol draws it at rest: its own soft light (a blur there, a fading ring here) and a
# radial gradient from the pearl to its edge.
P, AURA = PEARL * U, G   # its light stays inside the glass
aura = [(0, "F2"), (P / AURA, "78"), ((P + 3 * U) / AURA, "33"), ((P + 6 * U) / AURA, "0D"), (1, "00")]   # a blur's falloff
paths.append(f'<path android:name="aura" android:pathData="{circle(C, C, AURA)}" android:fillAlpha="0"><aapt:attr name="android:fillColor">'
             f'<gradient android:type="radial" android:centerX="{C:.1f}" android:centerY="{C:.1f}" android:gradientRadius="{AURA:.1f}">'
             + "".join(f'<item android:offset="{o:.3f}" android:color="#{a}7CCBFF" />' for o, a in aura) + '</gradient></aapt:attr></path>')
paths.append(f'<path android:name="pearl" android:pathData="{circle(C, C, P)}" android:fillAlpha="0"><aapt:attr name="android:fillColor">'
             f'<gradient android:type="radial" android:centerX="{C:.1f}" android:centerY="{C:.1f}" android:gradientRadius="{P:.1f}">'
             '<item android:offset="0" android:color="#FFF4FBFF" /><item android:offset="0.5" android:color="#FFF4FBFF" /><item android:offset="1" android:color="#FF7CCBFF" /></gradient></aapt:attr></path>')
# Over the pearl: light bounced in the glass below, a thin rim (no highlight: it read as a stain).
R = 0.84 * G
a0, a1 = math.radians(35), math.radians(145)
paths.append(f'<path android:name="bounce" android:pathData="M{C + R * math.cos(a0):.2f},{C + R * math.sin(a0):.2f} A{R:.2f},{R:.2f} 0 0,1 {C + R * math.cos(a1):.2f},{C + R * math.sin(a1):.2f}" '
             f'android:strokeColor="#1F4FA8FF" android:strokeWidth="{0.07 * G:.2f}" android:strokeLineCap="round" />')
paths.append(f'<path android:name="rim2" android:pathData="{circle(C, C, G - 0.025 * G)}" android:strokeWidth="{0.05 * G:.2f}"><aapt:attr name="android:strokeColor">'
             f'<gradient android:type="linear" android:startX="{C:.1f}" android:startY="{C - G:.1f}" android:endX="{C:.1f}" android:endY="{C + G:.1f}">'
             '<item android:offset="0" android:color="#47FFFFFF" /><item android:offset="0.5" android:color="#00FFFFFF" /><item android:offset="1" android:color="#4D000000" /></gradient></aapt:attr></path>')
for name, to in (("glow", 0.10), ("aura", 1.0), ("pearl", 1.0)):
    lit[name] = to
    anims.append(f'<target android:name="{name}"><aapt:attr name="android:animation"><objectAnimator android:propertyName="fillAlpha" '
                 f'android:valueFrom="0" android:valueTo="{to}" android:startOffset="{60 + N * 24}" android:duration="260" /></aapt:attr></target>')

out = ['<?xml version="1.0" encoding="utf-8"?>',
       '<!-- Generated by scripts/splash.py: the Presence Mark as a button, its LEDs lighting one by one, then the pearl. -->',
       '<animated-vector xmlns:android="http://schemas.android.com/apk/res/android" xmlns:aapt="http://schemas.android.com/aapt">',
       '<aapt:attr name="android:drawable">',
       '<vector android:width="240dp" android:height="240dp" android:viewportWidth="240" android:viewportHeight="240">',
       *paths, '</vector>', '</aapt:attr>', *anims, '</animated-vector>']
open(sys.argv[1], "w").write("\n".join(out) + "\n")

import re
lit_paths = [re.sub(r'android:name="(\w+)"(.*?)android:fillAlpha="0"', lambda m: f'android:name="{m[1]}"{m[2]}android:fillAlpha="{lit[m[1]]}"', x) for x in paths]
open(sys.argv[1].replace(".xml", "_lit.xml"), "w").write("\n".join([
    '<?xml version="1.0" encoding="utf-8"?>',
    '<!-- Generated by scripts/splash.py: splash_mark, fully lit. -->',
    '<vector xmlns:android="http://schemas.android.com/apk/res/android" xmlns:aapt="http://schemas.android.com/aapt" '
    'android:width="240dp" android:height="240dp" android:viewportWidth="240" android:viewportHeight="240">',
    *lit_paths, '</vector>']) + "\n")

# The launcher icon's monochrome layer and the notifications' small icon (ic_mark.xml): the same mark as a
# silhouette, 108 x 108 with the mark inside the adaptive icon's 66 dp safe zone: the LEDs on the open ring, the eye.
S, M = 30 / DISC, 54.0
mono = [f'<path android:pathData="{circle(M + math.cos(math.radians(START + k / (N - 1) * SWEEP)) * RING * S, M + math.sin(math.radians(START + k / (N - 1) * SWEEP)) * RING * S, LED * 1.25 * S)}" android:fillColor="#FFFFFFFF" />' for k in range(N)]
mono.append(f'<path android:pathData="{circle(M, M, EYE * S - 0.9)}" android:strokeColor="#FFFFFFFF" android:strokeWidth="1.8" />')
mono.append(f'<path android:pathData="{circle(M, M, PEARL * 1.15 * S)}" android:fillColor="#FFFFFFFF" />')
open(sys.argv[1].replace("splash_mark.xml", "ic_mark.xml"), "w").write("\n".join([
    '<?xml version="1.0" encoding="utf-8"?>',
    '<!-- Generated by scripts/splash.py: the Presence Mark as a silhouette (the eye in its open ring of LEDs). -->',
    '<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" '
    'android:viewportWidth="108" android:viewportHeight="108">',
    *mono, '</vector>']) + "\n")
