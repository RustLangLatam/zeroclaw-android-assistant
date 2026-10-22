# Writes app/src/main/res/raw/ksandra_chime.wav: python3 scripts/chime.py app/src/main/res/raw/ksandra_chime.wav
import math, struct, sys, wave
# Notification chime: two soft bell notes rising a fifth (E5 then B5), short.
rate = 22050
def bell(f, t):
    return sum(a * math.sin(2 * math.pi * f * k * t) * math.exp(-t * d) for k, a, d in ((1, 1.0, 5.0), (2, 0.35, 9.0), (3, 0.12, 14.0)))
notes = ((659.25, 0.0), (987.77, 0.16))
n = int(rate * 0.95)
out = []
for i in range(n):
    t = i / rate
    s = sum(bell(f, t - at) * min(1.0, (t - at) * 200) for f, at in notes if t >= at)
    s *= 0.32 * min(1.0, (n - i) / (rate * 0.08))
    out.append(struct.pack('<h', int(max(-1, min(1, s)) * 32767)))
w = wave.open(sys.argv[1], 'wb'); w.setnchannels(1); w.setsampwidth(2); w.setframerate(rate); w.writeframes(b''.join(out)); w.close()
