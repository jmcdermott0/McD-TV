#!/usr/bin/env python3
"""Builds app/src/main/res/raw/holiday_intro.ogg: the Holiday mode intro track.

In Holiday mode this track REPLACES the intro video's own sound (the Jarvis sting and voiceover are muted).
It is the only sound of the holiday intro: nothing is layered under or over it.

Melody: the chorus of "Jingle Bells" (James Lord Pierpont, 1857, public domain). Melody only: the
arrangement, harmony and every sound are synthesized here. No samples, no recordings.

Instruments: celesta (the tune), glockenspiel (the tune an octave up, for sparkle), a music box on the
off-beats, a warm plucked bass, a soft pad, sleigh bells, and a light stereo room. Full range (bass to air),
no low-pass, no noise bed.

Timing is matched to the intro video (res/raw/intro_video.mp4, 5.567 s, 30 fps), measured from its frames:
  0.00 to 1.10 s  the rings build: sleigh-bell roll and a rising celesta run, getting louder
  1.10 s          first flash (frame 33): the downbeat. The tune starts on a full chord and a low boom
  1.40 to 2.00 s  JARVIS types out: "jin-gle bells, jin-gle bells" in step
  4.30 s          second flash (frame 129): the last note of the phrase, a full chord and a boom
  4.90 to 5.50 s  the picture fades to black: the chord rings out and fades with it
  5.60 s          the track ends with the video

Usage: python3 tools/holiday/make_holiday_intro.py [keep.wav]   (needs numpy and ffmpeg with libvorbis)
"""
import os
import subprocess
import sys
import tempfile
import wave

import numpy as np

SR = 48000
LENGTH = 5.6
HIT1, HIT2 = 1.100, 4.300            # the video's two flashes (frames 33 and 129 at 30 fps)
FADE_FROM, FADE_TO = 4.90, 5.50      # the video's fade to black
BEAT = (HIT2 - HIT1) / 12            # three bars of 4/4 from flash to flash: 0.2667 s a beat (225 bpm, cut time)
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT = os.path.join(ROOT, "app", "src", "main", "res", "raw", "holiday_intro.ogg")

rng = np.random.default_rng(1857)    # fixed seed: the same file every run
N = int(SR * LENGTH) + SR
L = np.zeros(N)
R = np.zeros(N)


def hz(note):
    """'C5' -> frequency (A4 = 440 Hz)."""
    names = {"C": -9, "C#": -8, "D": -7, "D#": -6, "E": -5, "F": -4, "F#": -3, "G": -2, "G#": -1, "A": 0, "A#": 1, "B": 2}
    return 440.0 * 2 ** ((names[note[:-1]] + 12 * (int(note[-1]) - 4)) / 12)


def add(sig, at, pan=0.0):
    """Mixes a mono sound in at time `at`, panned -1 (left) to 1 (right) with equal power."""
    i = int(round(at * SR))
    n = min(len(sig), N - i)
    if n <= 0:
        return
    a = (pan + 1) * np.pi / 4
    L[i:i + n] += np.cos(a) * sig[:n]
    R[i:i + n] += np.sin(a) * sig[:n]


def env_t(dur):
    return np.arange(int(SR * dur)) / SR


def celesta(f, dur, amp):
    """Celesta: a near-pure tone with a bright, fast-fading upper partial and a soft hammer."""
    t = env_t(dur)
    s = (np.sin(2 * np.pi * f * t) * np.exp(-1.6 * t)
         + 0.30 * np.sin(2 * np.pi * 2 * f * t) * np.exp(-4.0 * t)
         + 0.16 * np.sin(2 * np.pi * 4.0 * f * t) * np.exp(-9.0 * t)
         + 0.05 * np.sin(2 * np.pi * 6.1 * f * t) * np.exp(-16.0 * t))
    s *= np.minimum(1.0, t / 0.002)
    return amp * s


def glock(f, dur, amp):
    """Glockenspiel bar: the bar's own (inharmonic) partials, very bright and short."""
    t = env_t(dur)
    s = np.zeros_like(t)
    for ratio, a, d in ((1.0, 1.0, 2.6), (2.756, 0.35, 6.0), (5.404, 0.12, 12.0), (8.933, 0.05, 20.0)):
        s += a * np.sin(2 * np.pi * f * ratio * t) * np.exp(-d * t)
    s *= np.minimum(1.0, t / 0.001)
    return amp * s


def musicbox(f, amp):
    """Music-box tine: a short bright pluck."""
    t = env_t(0.7)
    s = (np.sin(2 * np.pi * f * t) * np.exp(-5.0 * t)
         + 0.25 * np.sin(2 * np.pi * 3.0 * f * t) * np.exp(-11.0 * t))
    s *= np.minimum(1.0, t / 0.001)
    return amp * s


def bass(f, dur, amp):
    """Warm plucked bass: fundamental plus a little second and third harmonic, round attack."""
    t = env_t(dur)
    s = (np.sin(2 * np.pi * f * t) + 0.35 * np.sin(2 * np.pi * 2 * f * t) * np.exp(-3 * t)
         + 0.12 * np.sin(2 * np.pi * 3 * f * t) * np.exp(-6 * t)) * np.exp(-2.2 * t)
    s *= np.minimum(1.0, t / 0.006)
    return amp * s


def pad(freqs, dur, amp, attack=0.25):
    """Soft sustained chord: slightly detuned sine pairs, slow attack and release."""
    t = env_t(dur)
    s = np.zeros_like(t)
    for f in freqs:
        for det in (-0.12, 0.12):
            s += np.sin(2 * np.pi * f * (1 + det / 100) * t + rng.uniform(0, 6.28))
    e = np.minimum(1.0, t / attack) * np.minimum(1.0, (dur - t) / 0.25)
    return amp * s * e / (2 * len(freqs))


def boom(amp):
    """A deep, soft impact for the two flashes: a falling sine, no noise."""
    t = env_t(0.9)
    f = 48 + 70 * np.exp(-18 * t)
    s = np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-4.5 * t)
    s *= np.minimum(1.0, t / 0.003)
    return amp * s


def jingle():
    """One small bell of a sleigh-bell strap: a few metallic partials that die in a few hundredths of a second."""
    f0 = rng.uniform(2600, 5200)
    t = env_t(0.09)
    s = np.zeros_like(t)
    for ratio, a in ((1.0, 1.0), (1.47, 0.6), (2.09, 0.35)):
        s += a * np.sin(2 * np.pi * f0 * ratio * t + rng.uniform(0, 6.28))
    return s * np.exp(-55 * t) * np.minimum(1.0, t / 0.0005)


def shake(amp, spread=0.035, count=12):
    """One shake of sleigh bells: a dozen bells hit within a few hundredths of a second, plus a 2 ms metal tick."""
    out = np.zeros(int(SR * (spread + 0.1)))
    for _ in range(count):
        j = jingle() * rng.uniform(0.4, 1.0)
        i = int(rng.uniform(0, spread) * SR)
        out[i:i + len(j)] += j[:len(out) - i]
    tick = rng.standard_normal(int(SR * 0.002))
    tick = np.diff(tick, prepend=0) * np.linspace(1, 0, len(tick))
    out[:len(tick)] += 0.25 * tick
    return amp * out / count * 4.8


def fft_filter(x, gain_fn):
    spec = np.fft.rfft(x)
    f = np.fft.rfftfreq(len(x), 1 / SR)
    return np.fft.irfft(spec * gain_fn(f), len(x))


# --------------------------------------------------------------------------------------------- the score
C_MAJ = [hz("C4"), hz("E4"), hz("G4")]
G7 = [hz("B3"), hz("D4"), hz("F4"), hz("G4")]

# 0 to 1.1 s: the build. A sleigh-bell roll that thickens and grows, and a celesta run up the C major chord.
t = 0.08
while t < HIT1 - 0.02:
    p = t / HIT1
    add(shake(0.10 + 0.45 * p ** 2, spread=0.02, count=8), t, pan=0.35)
    t += 0.11 - 0.065 * p
run = ["C5", "E5", "G5", "C6", "E6", "G6", "C7"]
for k, n in enumerate(run):
    at = 0.30 + k * (HIT1 - 0.30 - 0.06) / len(run)
    add(celesta(hz(n), 0.6, 0.10 + 0.05 * k / len(run)), at, pan=-0.25 + 0.5 * k / len(run))
add(pad(C_MAJ, HIT1 + 0.1, 0.10, attack=0.9), 0.0)

# 1.1 s: the first flash. Downbeat chord, boom, big shake.
add(boom(0.55), HIT1)
add(shake(0.75, spread=0.05, count=16), HIT1, pan=0.35)
for n, a in (("C4", 0.10), ("G4", 0.08), ("C5", 0.08), ("E5", 0.06)):
    add(celesta(hz(n), 1.2, a), HIT1, pan=-0.2)

# 1.1 to 4.3 s: the "Jingle Bells" chorus. E E E- | E E E- | E G C. D | E (beats; the last E is on the second flash).
tune = [("E5", 0, 1), ("E5", 1, 1), ("E5", 2, 2), ("E5", 4, 1), ("E5", 5, 1), ("E5", 6, 2),
        ("E5", 8, 1), ("G5", 9, 1), ("C5", 10, 1.5), ("D5", 11.5, 0.5)]
for n, at, length in tune:
    when = HIT1 + at * BEAT
    add(celesta(hz(n), max(0.7, length * BEAT * 2.5), 0.40), when, pan=0.0)
    add(glock(hz(n) * 2, 0.6, 0.15), when, pan=-0.30)

# Harmony under the tune: C for two and a half bars, G7 for the turn back, C on the second flash.
add(pad(C_MAJ, 10 * BEAT + 0.15, 0.13), HIT1)
add(pad(G7, 2 * BEAT + 0.15, 0.13, attack=0.08), HIT1 + 10 * BEAT)
bass_line = [("C2", 0), ("G2", 2), ("C2", 4), ("G2", 6), ("C2", 8), ("E2", 10), ("G2", 11)]
for n, b in bass_line:
    add(bass(hz(n), 2 * BEAT + 0.1, 0.34), HIT1 + b * BEAT)

# Music box on the off-beats (the "and" of each beat), alternating sides; chord tones of the bar.
for b in range(12):
    chord = ["G5", "C6"] if b < 10 else ["F5", "B5"]
    add(musicbox(hz(chord[b % 2]), 0.07), HIT1 + (b + 0.5) * BEAT, pan=-0.45 if b % 2 else 0.45)

# Sleigh bells: every beat, lighter on the off-beats.
for b in range(12):
    add(shake(0.32 if b % 2 == 0 else 0.24), HIT1 + b * BEAT, pan=0.35)
    add(shake(0.13, count=8), HIT1 + (b + 0.5) * BEAT, pan=0.35)

# 4.3 s: the second flash. The last note, a full C major chord, boom, a glockenspiel sparkle up, bells ring out.
add(boom(0.5), HIT2)
ring = LENGTH - HIT2 + 0.2
add(celesta(hz("E5"), ring, 0.42), HIT2)
for n, a, p in (("C4", 0.12, -0.3), ("G4", 0.10, 0.3), ("C5", 0.10, -0.15), ("G5", 0.07, 0.15), ("C6", 0.07, 0.0)):
    add(celesta(hz(n), ring, a), HIT2, pan=p)
add(bass(hz("C2"), ring, 0.40), HIT2)
add(pad(C_MAJ + [hz("C5")], ring, 0.14, attack=0.03), HIT2)
for k, n in enumerate(["C6", "E6", "G6", "C7"]):
    add(glock(hz(n), 0.9, 0.09), HIT2 + 0.06 + k * 0.07, pan=-0.4 + 0.27 * k)
add(shake(0.8, spread=0.06, count=18), HIT2, pan=0.35)
for k in range(1, 6):
    add(shake(0.40 / k, spread=0.05), HIT2 + 0.14 * k, pan=0.35)

# ------------------------------------------------------------------------------------------- the room
# A short, bright stereo room (about 1.2 s): two independent decaying tails, gently darkened above 9 kHz so
# the reverb never hisses. Mixed in lightly; the dry sound stays in front.
ir_len = int(SR * 1.2)
ti = np.arange(ir_len) / SR
pre = int(SR * 0.012)
irs = []
for _ in range(2):
    ir = rng.standard_normal(ir_len) * np.exp(-5.8 * ti)
    ir = fft_filter(ir, lambda f: 1 / np.sqrt(1 + (f / 9000) ** 2))
    ir = np.concatenate([np.zeros(pre), ir])
    irs.append(ir / np.sqrt(np.sum(ir ** 2)))
size = 1 << int(np.ceil(np.log2(N + len(irs[0]))))
wetL = np.fft.irfft(np.fft.rfft(L, size) * np.fft.rfft(irs[0], size), size)[:N]
wetR = np.fft.irfft(np.fft.rfft(R, size) * np.fft.rfft(irs[1], size), size)[:N]
L = L + 0.16 * wetL
R = R + 0.16 * wetR

# --------------------------------------------------------------------------------------------- master
# Clean up the very bottom (below 28 Hz) only. Then end with the picture: fade with the video's fade to black.
L = fft_filter(L, lambda f: 1 / np.sqrt(1 + (28 / np.maximum(f, 1)) ** 4))
R = fft_filter(R, lambda f: 1 / np.sqrt(1 + (28 / np.maximum(f, 1)) ** 4))
n = int(SR * LENGTH)
L, R = L[:n], R[:n]
tt = np.arange(n) / SR
fade = np.clip((FADE_TO - tt) / (FADE_TO - FADE_FROM), 0, 1) ** 1.5
fade[:int(SR * FADE_FROM)] = 1.0
L *= fade
R *= fade
L[-int(SR * 0.05):] *= np.linspace(1, 0, int(SR * 0.05))
R[-int(SR * 0.05):] *= np.linspace(1, 0, int(SR * 0.05))

# Level: about as loud as the original intro's sound, peaks at -1 dBFS. A soft knee above 0.7 rounds off
# the few transient peaks instead of turning the whole track down.
stereo = np.stack([L, R], axis=1)
rms_target = 0.21
stereo *= rms_target / np.sqrt(np.mean(stereo[int(SR * HIT1):int(SR * HIT2)] ** 2))
knee = 0.7
over = np.abs(stereo) > knee
stereo[over] = np.sign(stereo[over]) * (knee + (1 - knee) * np.tanh((np.abs(stereo[over]) - knee) / (1 - knee)))
stereo *= 0.891 / max(0.891, np.max(np.abs(stereo)))  # -1 dBFS ceiling
pcm = (np.clip(stereo, -1, 1) * 32767).astype(np.int16)

with tempfile.TemporaryDirectory() as d:
    wav = os.path.join(d, "holiday_intro.wav")
    with wave.open(wav, "wb") as w:
        w.setnchannels(2)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(pcm.tobytes())
    if len(sys.argv) > 1:  # an extra argument: also keep the WAV there (for listening checks)
        subprocess.run(["cp", wav, sys.argv[1]], check=True)
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", wav, "-c:a", "libvorbis", "-q:a", "6", OUT], check=True)
print("wrote", os.path.relpath(OUT, ROOT), os.path.getsize(OUT), "bytes")
