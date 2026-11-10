#!/usr/bin/env python3
"""Builds app/src/main/res/raw/holiday_jingle.ogg, the Holiday mode jingle over the Jarvis intro.

Everything is synthesized here: no samples, no recordings. The tune is the chorus of "Jingle Bells"
(James Lord Pierpont, 1857, public domain), played on synth bells over sleigh bells.

Timing follows the intro video (res/raw/intro_video.mp4, 5.6 s):
  0.0 to 1.1 s  sleigh bells build with the video's riser
  1.1 s         the video's first hit: the tune starts
  4.4 s         the video's second hit: the last note, with a bell chord that rings out
  5.6 s         the video ends
The voiceover sits between the hits, so the bells stay soft and high there.

Usage: python3 tools/holiday/make_jingle.py   (needs numpy and ffmpeg with libvorbis)
"""
import os
import subprocess
import sys
import tempfile
import wave

import numpy as np

SR = 44100
LENGTH = 5.6
HIT1, HIT2 = 1.1, 4.4
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT = os.path.join(ROOT, "app", "src", "main", "res", "raw", "holiday_jingle.ogg")

NOTE = {"C5": 523.25, "D5": 587.33, "E5": 659.26, "G5": 783.99, "C6": 1046.50, "E6": 1318.51, "G6": 1567.98}

rng = np.random.default_rng(1857)  # fixed seed: the same sound every run
out = np.zeros(int(SR * LENGTH) + SR)


def add(sig, at):
    i = int(at * SR)
    n = min(len(sig), len(out) - i)
    out[i:i + n] += sig[:n]


def bell(freq, dur, amp):
    """A bell: a few partials (the upper ones slightly inharmonic) with fast decays and a soft attack."""
    t = np.arange(int(SR * dur)) / SR
    s = np.zeros_like(t)
    for ratio, a, decay in ((1.0, 1.0, 2.2), (2.0, 0.45, 3.5), (3.01, 0.22, 5.0), (4.17, 0.12, 7.0), (5.43, 0.06, 9.0)):
        s += a * np.sin(2 * np.pi * freq * ratio * t) * np.exp(-decay * t)
    s *= np.minimum(1.0, t / 0.004)
    return amp * s


def shake(dur, amp):
    """One shake of sleigh bells: many tiny high jingles plus a burst of bright noise."""
    t = np.arange(int(SR * dur)) / SR
    s = np.zeros_like(t)
    for _ in range(14):
        f = rng.uniform(4200, 9000)
        start = rng.uniform(0, 0.03)
        tt = np.clip(t - start, 0, None)
        s += np.sin(2 * np.pi * f * tt) * np.exp(-28 * tt) * (t >= start) * rng.uniform(0.4, 1.0)
    noise = rng.standard_normal(len(t))
    noise = np.diff(noise, prepend=0)  # tilt toward the highs
    s = s / 14 * 2.2 + 0.18 * noise * np.exp(-30 * t)
    return amp * s


# Build-up: shakes getting closer and louder up to the first hit.
t = 0.15
while t < HIT1 - 0.05:
    p = t / HIT1
    add(shake(0.25, 0.20 + 0.50 * p * p), t)
    t += 0.22 - 0.12 * p

# The tune: E E E- | E E E- | E G C. D | E (beats). The last E lands on the second hit.
beat = (HIT2 - HIT1) / 12
tune = [("E5", 0, 1), ("E5", 1, 1), ("E5", 2, 2), ("E5", 4, 1), ("E5", 5, 1), ("E5", 6, 2),
        ("E5", 8, 1), ("G5", 9, 1), ("C5", 10, 1.5), ("D5", 11.5, 0.5)]
for name, at, length in tune:
    add(bell(NOTE[name], max(0.6, length * beat * 2.2), 0.30), HIT1 + at * beat)
    add(bell(NOTE[name] * 2, 0.35, 0.06), HIT1 + at * beat)  # a little sparkle an octave up

# Sleigh bells on every beat while the tune plays (on beats stronger), quiet enough for the voiceover.
for b in range(12):
    add(shake(0.22, 0.16), HIT1 + b * beat)
    add(shake(0.15, 0.07), HIT1 + (b + 0.5) * beat)

# Second hit: the last note, a bell chord and a long shake ringing out to the end.
for name, amp in (("E5", 0.34), ("C5", 0.16), ("G5", 0.14), ("C6", 0.10), ("E6", 0.07), ("G6", 0.05)):
    add(bell(NOTE[name], LENGTH - HIT2 + 0.2, amp), HIT2)
add(shake(0.6, 0.35), HIT2)
for k in range(1, 5):
    add(shake(0.3, 0.18 / k), HIT2 + 0.18 * k)

out = out[: int(SR * LENGTH)]
fade = int(SR * 0.35)
out[-fade:] *= np.linspace(1, 0, fade)
out *= 0.5 / np.max(np.abs(out))  # peak at about -6 dB: the player sets the final level
pcm = (np.clip(out, -1, 1) * 32767).astype(np.int16)

with tempfile.TemporaryDirectory() as d:
    wav = os.path.join(d, "jingle.wav")
    with wave.open(wav, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(pcm.tobytes())
    if len(sys.argv) > 1:  # an extra argument: also keep the WAV there (for listening checks)
        subprocess.run(["cp", wav, sys.argv[1]], check=True)
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", wav, "-c:a", "libvorbis", "-q:a", "4", OUT], check=True)
print("wrote", os.path.relpath(OUT, ROOT), os.path.getsize(OUT), "bytes")
