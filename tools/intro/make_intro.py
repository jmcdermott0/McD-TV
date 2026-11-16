#!/usr/bin/env python3
"""
Renders the Jarvis intro (app/src/main/res/raw/intro_video.mp4): "Night Ops", 5.6 s, 1920x1080 at 60 fps,
with its own synthesized sound. Pure numpy + Pillow + ffmpeg; no samples, no stock footage.

Beat grid (kept from the first intro so the holiday track and HolidayIntro.kt stay in step):
  0.00-1.10  a hairline of light opens across the dark and pulls back to the center (riser)
  1.10 HIT1  the diamond mark ignites: flash, shockwave, lens streak, camera shake (sub drop + metal hit)
  1.10-4.30  angled panels slide in behind, the mark breathes, JARVIS assembles letter by letter (pulse, riser)
  4.30 HIT2  the wordmark locks: flash, second shockwave, light sweep, ALL SYSTEMS ONLINE (impact + tail)
  4.90-5.50  fade to black

Usage:
  python3 tools/intro/make_intro.py --saira SairaCondensed-ExtraBold.ttf --barlow BarlowSemiCondensed-SemiBold.ttf \\
      --out app/src/main/res/raw/intro_video.mp4 [--preview 1.1,2.8,4.4]
Fonts: Saira Condensed and Barlow Semi Condensed from github.com/google/fonts (ofl/), SIL OFL.
"""
import argparse
import math
import subprocess
import sys
import wave

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

W, H = 1920, 1080
FPS = 60
DUR = 5.6
HIT1, HIT2 = 1.10, 4.30
FADE_FROM, FADE_TO = 4.90, 5.50
SR = 48000

CYAN = np.array([0.18, 0.90, 1.00], np.float32)
ICE = np.array([0.70, 0.97, 1.00], np.float32)
WHITE = np.array([1.0, 1.0, 1.0], np.float32)
GROUND = np.array([0.020, 0.024, 0.028], np.float32)
TEAL = np.array([0.024, 0.094, 0.106], np.float32)

MARK_C = (W / 2, H * 0.37)
MARK_R = 118.0


def clamp01(x):
    return max(0.0, min(1.0, x))


def ease_out(x, p=3.0):
    x = clamp01(x)
    return 1 - (1 - x) ** p


def ease_in(x, p=3.0):
    return clamp01(x) ** p


def ease_out_back(x, s=1.9):
    x = clamp01(x)
    x -= 1
    return x * x * ((s + 1) * x + s) + 1


def seg(t, a, b):
    return clamp01((t - a) / (b - a)) if b > a else float(t >= a)


# ------------------------------------------------------------------ drawing helpers (masks are float 0..1)


def mask_from(draw_fn, scale=1):
    im = Image.new("L", (W // scale, H // scale), 0)
    d = ImageDraw.Draw(im)
    draw_fn(d, scale)
    return np.asarray(im, np.float32) / 255.0


def diamond_pts(c, r, scale=1):
    cx, cy = c[0] / scale, c[1] / scale
    r /= scale
    return [(cx, cy - r), (cx + r, cy), (cx, cy + r), (cx - r, cy)]


def diamond_outline_partial(d, c, r, width, frac, scale=1):
    """Outer diamond traced from the top, clockwise, [frac] of the way round."""
    pts = diamond_pts(c, r, scale)
    pts = pts + [pts[0]]
    total = 4.0 * frac
    for k in range(4):
        if total <= 0:
            break
        a, b = pts[k], pts[k + 1]
        f = min(1.0, total)
        e = (a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f)
        d.line([a, e], fill=255, width=max(1, int(round(width / scale))), joint="curve")
        total -= 1.0


def blur(a, radius, down=1):
    """Gaussian blur of a float image (H, W[, 3]) in Pillow, optionally at a lower resolution."""
    if a.ndim == 2:
        a = a[..., None]
    out = np.empty_like(a)
    for ch in range(a.shape[2]):
        im = Image.fromarray(np.clip(a[..., ch] * 64.0, 0, 255).astype(np.uint8), "L")
        if down > 1:
            im = im.resize((W // down, H // down), Image.BILINEAR)
            im = im.filter(ImageFilter.GaussianBlur(radius / down))
            im = im.resize((W, H), Image.BILINEAR)
        else:
            im = im.filter(ImageFilter.GaussianBlur(radius))
        out[..., ch] = np.asarray(im, np.float32) / 64.0
    return out if out.shape[2] > 1 else out[..., 0]


# ------------------------------------------------------------------ static layers

YY, XX = np.mgrid[0:H, 0:W].astype(np.float32)
VIGNETTE = np.clip(1.0 - (((XX - W / 2) / (W * 0.62)) ** 2 + ((YY - H * 0.42) / (H * 0.78)) ** 2) * 0.55, 0.35, 1.0)
HATCH = (((XX + YY) % 14.0) < 1.0).astype(np.float32) * 0.010


class Wordmark:
    def __init__(self, font_path, size):
        self.font = ImageFont.truetype(font_path, size)
        self.letters = list("JARVIS")
        self.widths = [self.font.getbbox(ch)[2] - self.font.getbbox(ch)[0] for ch in self.letters]
        self.offs = [self.font.getbbox(ch)[0] for ch in self.letters]
        asc, desc = self.font.getmetrics()
        self.asc = asc

    def mask(self, tracking, alphas, dy, cy):
        total = sum(self.widths) + tracking * (len(self.letters) - 1)
        x = W / 2 - total / 2
        im = Image.new("L", (W, H), 0)
        d = ImageDraw.Draw(im)
        for i, ch in enumerate(self.letters):
            a = alphas[i]
            if a > 0.003:
                d.text((x - self.offs[i], cy - self.asc * 0.62 + dy[i]), ch, font=self.font, fill=int(255 * a))
            x += self.widths[i] + tracking
        return np.asarray(im, np.float32) / 255.0, total


def slabs(t):
    """Angled panels behind the mark: (fill mask, edge mask). They slide in after HIT1 and jolt at HIT2."""
    if t < HIT1:
        return None, None
    jolt = 26.0 * math.exp(-max(0.0, t - HIT2) * 9.0) if t >= HIT2 else 0.0
    drift = (t - HIT1) * 14.0
    specs = [  # final x of the slab's top edge, width, delay, speed
        (1180.0, 520.0, 0.00, 1.0),
        (1520.0, 120.0, 0.12, 1.25),
        (330.0, 260.0, 0.25, 0.8),
    ]
    fill = Image.new("L", (W, H), 0)
    edge = Image.new("L", (W, H), 0)
    df, de = ImageDraw.Draw(fill), ImageDraw.Draw(edge)
    skew = H * math.tan(math.radians(18))
    for k, (x0, wd, delay, sp) in enumerate(specs):
        p = ease_out(seg(t, HIT1 + delay, HIT1 + delay + 0.9 / sp), 4)
        start = W + 300 if k != 2 else -900
        x = start + (x0 - start) * p - drift * (1 if k != 2 else -1) - jolt * (1 if k != 2 else -1)
        poly = [(x + skew, 0), (x + skew + wd, 0), (x + wd, H), (x, H)]
        df.polygon(poly, fill=int(255 * (0.55 if k == 0 else 0.35)))
        if k == 1:
            de.line([(x + skew + wd, 0), (x + wd, H)], fill=200, width=3)
    return np.asarray(fill, np.float32) / 255.0, np.asarray(edge, np.float32) / 255.0


RNG = np.random.default_rng(7)
DUST = np.column_stack([RNG.uniform(0, W, 140), RNG.uniform(H * 0.2, H * 0.8, 140), RNG.uniform(0.3, 1.0, 140)])


def frame(t, wm, small_font):
    img = np.empty((H, W, 3), np.float32)
    img[:] = GROUND
    img += HATCH[..., None]
    light = np.zeros((H, W, 3), np.float32)

    # ---- background panels
    f, e = slabs(t)
    if f is not None:
        img += f[..., None] * TEAL * 1.6
        light += e[..., None] * CYAN * 0.55

    # ---- phase A: the hairline opens, then pulls back into the center
    if t < HIT1 + 0.05:
        open_w = W * 0.5 * ease_out(seg(t, 0.05, 0.75), 3)
        pull = ease_in(seg(t, 0.72, HIT1), 2.2)
        half = open_w * (1 - pull) + 4
        inten = 0.25 + 1.1 * seg(t, 0.05, 0.7) + 2.2 * pull
        cy = MARK_C[1]
        m = mask_from(lambda d, s: d.line([(W / 2 - half, cy), (W / 2 + half, cy)], fill=255, width=3))
        light += m[..., None] * (CYAN * inten + WHITE * 0.6 * pull)
        # dust drawn toward the center as the light pulls in
        dust = Image.new("L", (W, H), 0)
        dd = ImageDraw.Draw(dust)
        for x, y, b in DUST:
            px = x + (W / 2 - x) * pull * 0.85
            py = y + (cy - y) * pull * 0.85
            dd.ellipse([px - 1.5, py - 1.5, px + 1.5, py + 1.5], fill=int(255 * b * 0.6 * seg(t, 0.2, 0.6)))
        light += (np.asarray(dust, np.float32) / 255.0)[..., None] * ICE * 0.8

    # ---- the mark
    shake = (0.0, 0.0)
    if t >= HIT1:
        u = t - HIT1
        push = 1.0 + 0.07 * ease_out(seg(t, HIT1, FADE_TO), 1.4)
        r = MARK_R * push
        trace = ease_out(seg(t, HIT1, HIT1 + 0.16), 2)
        inner = ease_out_back(seg(t, HIT1 + 0.02, HIT1 + 0.26))
        breathe = 0.85 + 0.15 * math.sin(u * 5.2)
        ignite = 1.0 + 3.0 * math.exp(-u * 9.0)
        outer = mask_from(lambda d, s: diamond_outline_partial(d, MARK_C, r, 9, trace))
        light += outer[..., None] * (CYAN * 1.3 * breathe * ignite + WHITE * 0.5 * math.exp(-u * 6))
        if inner > 0.001:
            ir = r * 0.42 * inner
            core = mask_from(lambda d, s: d.polygon(diamond_pts(MARK_C, ir), fill=255))
            light += core[..., None] * (CYAN * 1.15 * ignite + WHITE * 0.35)
        # shockwaves at both hits
        for hit, strength in ((HIT1, 1.0), (HIT2, 0.75)):
            if hit <= t < hit + 0.9:
                v = (t - hit) / 0.9
                sr = r * (1.15 + 6.5 * ease_out(v, 2.6))
                wv = max(1, int(14 * (1 - v)))
                ring = mask_from(lambda d, s: d.polygon(diamond_pts(MARK_C, sr), outline=255, width=wv))
                light += ring[..., None] * CYAN * (1 - v) ** 1.6 * 1.6 * strength
        if u < 0.32:
            k = math.exp(-u * 14.0) * 14.0
            shake = (math.sin(u * 97.0) * k, math.cos(u * 83.0) * k * 0.7)

    # ---- anamorphic streaks at the hits
    for hit, strength in ((HIT1, 1.0), (HIT2, 0.8)):
        if hit <= t < hit + 0.6:
            v = (t - hit) / 0.6
            a = (1 - v) ** 2 * strength
            cy = MARK_C[1] if hit == HIT1 else H * 0.62
            streak = np.exp(-((YY - cy) / 3.0) ** 2) * np.exp(-((XX - W / 2) / (W * 0.42)) ** 2)
            light += streak[..., None] * (CYAN * 2.4 + WHITE * 1.0) * a

    # ---- the wordmark
    if t >= 2.30:
        n = 6
        alphas, dys = [], []
        for i in range(n):
            s0 = 2.45 + i * 0.16
            a = ease_out(seg(t, s0, s0 + 0.35), 2)
            alphas.append(a)
            dys.append(46.0 * (1 - ease_out(seg(t, s0, s0 + 0.45), 3)))
        track = 96.0 - 62.0 * ease_out(seg(t, 2.45, 4.10), 2.4)
        if t >= HIT2:
            track = 34.0 - 10.0 * ease_out(seg(t, HIT2, HIT2 + 0.12), 2)
        elif t > 4.18:
            track = 34.0 + 10.0 * ease_in(seg(t, 4.18, HIT2), 2)  # breath in before the lock
        wmask, total = wm.mask(track, alphas, dys, H * 0.64)
        lock = math.exp(-max(0.0, t - HIT2) * 7.0) if t >= HIT2 else 0.0
        img += wmask[..., None] * (WHITE * 0.92)
        light += wmask[..., None] * (CYAN * 0.22 + WHITE * 1.6 * lock)
        # light sweep across the letters
        for s0, s1 in ((3.55, 4.15), (HIT2 + 0.05, HIT2 + 0.55)):
            if s0 <= t < s1:
                v = (t - s0) / (s1 - s0)
                bx = W / 2 - total / 2 - 200 + (total + 400) * v
                band = np.exp(-((XX - (bx + (YY - H * 0.64) * 0.32)) / 34.0) ** 2)
                light += (wmask * band)[..., None] * (WHITE * 2.2)
        # hairline and status after the lock
        if t >= HIT2:
            lw = (total * 0.5 + 40) * ease_out(seg(t, HIT2, HIT2 + 0.35), 3)
            ly = H * 0.64 + 128
            line = mask_from(lambda d, s: d.line([(W / 2 - lw, ly), (W / 2 + lw, ly)], fill=255, width=2))
            light += line[..., None] * CYAN * 1.4
            sa = ease_out(seg(t, HIT2 + 0.15, HIT2 + 0.5), 2)
            if sa > 0:
                txt = "ALL SYSTEMS ONLINE"
                st = Image.new("L", (W, H), 0)
                sd = ImageDraw.Draw(st)
                sp = 12
                widths = [small_font.getbbox(c)[2] - small_font.getbbox(c)[0] if c != " " else 14 for c in txt]
                x = W / 2 - (sum(widths) + sp * (len(txt) - 1)) / 2
                for c, wdt in zip(txt, widths):
                    if c != " ":
                        sd.text((x - small_font.getbbox(c)[0], ly + 22), c, font=small_font, fill=int(255 * sa))
                    x += wdt + sp
                sm = np.asarray(st, np.float32) / 255.0
                img += sm[..., None] * np.array([0.55, 0.60, 0.66], np.float32)
                light += sm[..., None] * CYAN * 0.25

    # ---- flashes
    for hit, peak in ((HIT1, 0.30), (HIT2, 0.22)):
        if hit <= t < hit + 0.35:
            img += (peak * math.exp(-(t - hit) * 13.0)) * (CYAN * 0.5 + WHITE * 0.5)
    if 4.12 < t < HIT2:  # suck in before the second hit
        img *= 1 - 0.35 * ease_in(seg(t, 4.12, HIT2), 2)
        light *= 1 - 0.35 * ease_in(seg(t, 4.12, HIT2), 2)

    # ---- bloom and compose
    b1 = blur(light, 6)
    b2 = blur(light, 26, down=2)
    b3 = blur(light, 90, down=4)
    out = img + light + b1 * 0.55 + b2 * 0.55 + b3 * 0.45
    out *= VIGNETTE[..., None]
    out = np.tanh(out * 1.08) / np.tanh(1.08)
    if t >= FADE_FROM:
        out *= 1 - ease_in(seg(t, FADE_FROM, FADE_TO), 1.6)
    # Fine grain that changes every third frame (keeps the file small; noise on every frame doubles it).
    grain = np.random.default_rng(int(t * FPS) // 3).normal(0, 0.006, (H // 2, W // 2)).astype(np.float32)
    grain = np.repeat(np.repeat(grain, 2, 0), 2, 1)
    out += grain[..., None]
    if shake != (0.0, 0.0):
        dx, dy = int(round(shake[0])), int(round(shake[1]))
        out = np.roll(out, (dy, dx), axis=(0, 1))
    return (np.clip(out, 0, 1) * 255 + 0.5).astype(np.uint8)


# ------------------------------------------------------------------ sound


def synth():
    n = int(SR * DUR)
    t = np.arange(n) / SR
    L = np.zeros(n)
    R = np.zeros(n)
    rng = np.random.default_rng(11)

    def add(sig, start, pan=0.0, gain=1.0):
        i = int(start * SR)
        j = min(n, i + len(sig))
        if j <= i:
            return
        l, r = math.cos((pan + 1) * math.pi / 4), math.sin((pan + 1) * math.pi / 4)
        L[i:j] += sig[: j - i] * gain * l * 1.414
        R[i:j] += sig[: j - i] * gain * r * 1.414

    def env(length, a, d):
        x = np.arange(int(length * SR)) / SR
        return np.minimum(1, x / max(a, 1e-4)) * np.exp(-x / d)

    def lowpass(x, cutoff):
        # one-pole, cutoff may be an array
        a = np.exp(-2 * np.pi * np.asarray(cutoff, float) / SR)
        y = np.zeros_like(x)
        prev = 0.0
        a = np.broadcast_to(a, x.shape)
        for i in range(len(x)):
            prev = (1 - a[i]) * x[i] + a[i] * prev
            y[i] = prev
        return y

    def noise(sec):
        return rng.standard_normal(int(sec * SR))

    # riser into HIT1: filtered noise sweep + rising sub
    ln = HIT1
    x = np.arange(int(ln * SR)) / SR
    sweep = 300 + 7000 * (x / ln) ** 2.5
    rs = lowpass(noise(ln), sweep) * (x / ln) ** 2.2
    add(rs * 0.5, 0.0, -0.2)
    add(lowpass(noise(ln), sweep) * (x / ln) ** 2.2 * 0.5, 0.0, 0.2)
    f = 38 + 30 * (x / ln) ** 2
    add(np.sin(2 * np.pi * np.cumsum(f) / SR) * (x / ln) ** 1.5 * 0.55, 0.0)
    # tick of the hairline opening
    add(np.sin(2 * np.pi * 2400 * np.arange(int(0.05 * SR)) / SR) * env(0.05, 0.001, 0.012) * 0.25, 0.05)

    def impact(at, size):
        ln = 2.4
        x = np.arange(int(ln * SR)) / SR
        f = 32 + 95 * np.exp(-x * 18)  # sub drop
        sub = np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-x / (0.55 * size))
        body = lowpass(noise(ln), 900) * np.exp(-x / 0.18) * 0.9
        crack = lowpass(noise(0.06), 9000) * env(0.06, 0.0005, 0.02) * 0.8
        metal = sum(np.sin(2 * np.pi * fr * x + ph) * np.exp(-x / (0.9 * size)) * g
                    for fr, ph, g in ((311, 0, .30), (466.2, 1, .22), (739, 2, .18), (1103, 3, .12), (1667, .5, .08), (2489, 2.2, .05)))
        hit = sub * 1.25 + body + metal * 0.55
        add(hit, at, -0.15, size)
        add(hit, at + 0.011, 0.15, size * 0.9)
        add(crack, at, 0.0, size)

    impact(HIT1, 1.0)

    # dark pad between the hits (D minor: D2, A2, F3), slowly opening
    ln = HIT2 - HIT1 + 0.2
    x = np.arange(int(ln * SR)) / SR
    pad = np.zeros_like(x)
    for fr in (73.42, 110.0, 174.61, 146.83):
        for det in (-0.35, 0.35):
            ph = 2 * np.pi * (fr + det) * x
            pad += (2 * ((ph / (2 * np.pi)) % 1) - 1) * 0.08  # saw
    pad = lowpass(pad, 220 + 1600 * (x / ln) ** 2) * np.minimum(1, x / 0.6) * (0.55 + 0.45 * x / ln)
    add(pad, HIT1, -0.3, 1.15)
    add(pad, HIT1 + 0.007, 0.3, 1.15)
    # pulse: sub thumps on the eighth notes (120 bpm), rising
    for k in range(12):
        at = HIT1 + 0.5 + k * 0.25
        if at > HIT2 - 0.15:
            break
        lx = np.arange(int(0.22 * SR)) / SR
        thump = np.sin(2 * np.pi * np.cumsum(48 + 70 * np.exp(-lx * 40)) / SR) * np.exp(-lx / 0.07)
        add(thump, at, 0.0, 0.45 + 0.05 * k)
    # letter ticks as JARVIS assembles
    for i in range(6):
        lx = np.arange(int(0.08 * SR)) / SR
        tick = lowpass(rng.standard_normal(len(lx)), 6000) * np.exp(-lx / 0.012) + np.sin(2 * np.pi * (1800 + i * 140) * lx) * np.exp(-lx / 0.02) * 0.5
        add(tick, 2.45 + i * 0.16, -0.5 + i * 0.2, 0.28)
    # riser into HIT2, then a breath of silence
    ln = 1.0
    x = np.arange(int(ln * SR)) / SR
    rs = lowpass(noise(ln), 400 + 9000 * (x / ln) ** 2.2) * (x / ln) ** 2.4
    fr = 220 * 2 ** (x / ln * 1.5)
    tone = np.sin(2 * np.pi * np.cumsum(fr) / SR) * (x / ln) ** 2 * 0.18
    add((rs * 0.55 + tone)[: int((HIT2 - 0.05 - (HIT2 - 1.0)) * SR)], HIT2 - 1.0, 0.0)

    impact(HIT2, 1.35)
    # braam at the lock: detuned saws, filter opening then closing
    ln = 1.25
    x = np.arange(int(ln * SR)) / SR
    br = np.zeros_like(x)
    for fr in (36.71, 73.42, 110.0, 146.83):
        for det in (-0.6, 0.0, 0.6):
            br += (2 * (((fr + det) * x) % 1) - 1) * 0.07
    cut = 180 + 2600 * np.exp(-x / 0.35) * np.minimum(1, x / 0.04)
    br = lowpass(br, cut) * np.exp(-x / 0.6)
    add(br, HIT2, -0.25, 1.0)
    add(br, HIT2 + 0.013, 0.25, 1.0)

    # simple reverb on everything: a decaying noise tail, convolved by FFT
    ir_len = int(1.8 * SR)
    xi = np.arange(ir_len) / SR
    ir = rng.standard_normal(ir_len) * np.exp(-xi / 0.45)
    ir[: int(0.012 * SR)] = 0
    ir /= np.sqrt((ir ** 2).sum())

    def verb(sig):
        m = len(sig) + ir_len
        nfft = 1 << (m - 1).bit_length()
        y = np.fft.irfft(np.fft.rfft(sig, nfft) * np.fft.rfft(ir, nfft), nfft)[: len(sig)]
        return sig + y * 0.32

    L, R = verb(L), verb(R)
    # fade out with the picture
    fade = np.ones(n)
    a, b = int(FADE_FROM * SR), int(DUR * SR)
    fade[a:b] = np.linspace(1, 0, b - a) ** 1.5
    L *= fade
    R *= fade
    peak = max(np.abs(L).max(), np.abs(R).max())
    L, R = np.tanh(L / peak * 1.4) * 0.89, np.tanh(R / peak * 1.4) * 0.89
    return np.column_stack([L, R])


def write_wav(path, stereo):
    data = (np.clip(stereo, -1, 1) * 32767).astype("<i2")
    with wave.open(path, "wb") as w:
        w.setnchannels(2)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(data.tobytes())


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--saira", required=True)
    ap.add_argument("--barlow", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--preview", default="")
    ap.add_argument("--wav", default="/tmp/jarvis_intro.wav")
    a = ap.parse_args()
    wm = Wordmark(a.saira, 236)
    small = ImageFont.truetype(a.barlow, 30)
    if a.preview:
        for ts in a.preview.split(","):
            Image.fromarray(frame(float(ts), wm, small)).save(f"{a.out}_{ts}.png")
        return
    write_wav(a.wav, synth())
    frames = int(round(DUR * FPS))
    cmd = ["ffmpeg", "-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{W}x{H}", "-r", str(FPS), "-i", "-",
           "-i", a.wav, "-c:v", "libx264", "-preset", "slow", "-crf", "21", "-maxrate", "6M", "-bufsize", "12M", "-pix_fmt", "yuv420p", "-profile:v", "high",
           "-level", "4.2", "-r", str(FPS), "-c:a", "aac", "-b:a", "192k", "-ar", "48000", "-movflags", "+faststart",
           "-t", str(DUR), a.out]
    p = subprocess.Popen(cmd, stdin=subprocess.PIPE)
    for i in range(frames):
        p.stdin.write(frame(i / FPS, wm, small).tobytes())
        if i % 30 == 0:
            print(f"frame {i}/{frames}", file=sys.stderr, flush=True)
    p.stdin.close()
    sys.exit(p.wait())


if __name__ == "__main__":
    main()
