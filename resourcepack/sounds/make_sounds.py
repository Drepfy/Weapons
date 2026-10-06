"""The legendary weapons' own sounds, synthesized from scratch (no samples): every ability's
cast and hit. Writes legendary/<weapon>/<sound>.ogg next to this file; the pack build only copies
those files, so this script is only needed to change a sound.

Needs numpy and soundfile (pip install numpy soundfile).

    python3 make_sounds.py              make every sound
    python3 make_sounds.py kurogane     only one weapon's
"""
import os
import sys

import numpy as np
import soundfile

SR = 44100
HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, 'legendary')


# ---- building blocks ---------------------------------------------------------------------------------


def silence(seconds):
    return np.zeros(int(seconds * SR))


def time(seconds):
    return np.arange(int(seconds * SR)) / SR


def noise(seconds, seed=0):
    return np.random.default_rng(seed).standard_normal(int(seconds * SR))


def env(seconds, attack, decay, hold=0.0, curve=4.0):
    """Rises over `attack`, holds, then dies away exponentially over `decay`."""
    t = time(seconds)
    e = np.where(t < attack, (t / max(attack, 1e-6)) ** 1.5, 1.0)
    after = np.clip(t - attack - hold, 0, None)
    e = e * np.exp(-curve * after / max(decay, 1e-6))
    return e


def swell(seconds, peak_at, curve=2.5):
    """Grows until `peak_at`, then stops (a reverse swell)."""
    t = time(seconds)
    return np.where(t < peak_at, (t / peak_at) ** curve, 0.0)


def band(x, lo, hi, soft=0.25):
    """Keeps the frequencies between lo and hi (smooth edges, in octaves)."""
    spectrum = np.fft.rfft(x)
    f = np.fft.rfftfreq(len(x), 1 / SR)
    lf = np.log2(np.maximum(f, 1.0))
    gain = 1 / (1 + np.exp(-(lf - np.log2(lo)) / soft)) * 1 / (1 + np.exp((lf - np.log2(hi)) / soft))
    return np.fft.irfft(spectrum * gain, len(x))


def lowpass(x, cutoff, soft=0.3):
    return band(x, 10, cutoff, soft)


def highpass(x, cutoff, soft=0.3):
    return band(x, cutoff, SR / 2, soft)


def sweep_noise(seconds, f_start, f_end, width=0.6, seed=0):
    """Noise through a band that slides from f_start to f_end (a whoosh)."""
    x = noise(seconds, seed)
    n = len(x)
    frame, hop = 2048, 256
    window = np.hanning(frame)
    out = np.zeros(n + frame)
    norm = np.zeros(n + frame)
    f = np.fft.rfftfreq(frame, 1 / SR)
    lf = np.log2(np.maximum(f, 1.0))
    for start in range(0, n, hop):
        chunk = x[start:start + frame]
        if len(chunk) < frame:
            chunk = np.pad(chunk, (0, frame - len(chunk)))
        p = start / max(1, n)
        centre = np.log2(f_start) + (np.log2(f_end) - np.log2(f_start)) * p
        gain = np.exp(-((lf - centre) / width) ** 2)
        y = np.fft.irfft(np.fft.rfft(chunk * window) * gain, frame)
        out[start:start + frame] += y * window
        norm[start:start + frame] += window ** 2
    return (out / np.maximum(norm, 1e-6))[:n]


def tone(seconds, f_start, f_end=None, vibrato=(0.0, 0.0), shape='sine'):
    """A pitch gliding (exponentially) from f_start to f_end, with optional vibrato (rate, depth)."""
    t = time(seconds)
    f_end = f_start if f_end is None else f_end
    freq = f_start * (f_end / f_start) ** (t / max(seconds, 1e-6))
    rate, depth = vibrato
    if depth:
        freq = freq * (1 + depth * np.sin(2 * np.pi * rate * t))
    phase = 2 * np.pi * np.cumsum(freq) / SR
    if shape == 'saw':
        return 2 * ((phase / (2 * np.pi)) % 1.0) - 1
    if shape == 'tri':
        return 2 * np.abs(2 * ((phase / (2 * np.pi)) % 1.0) - 1) - 1
    return np.sin(phase)


def bell(seconds, f0, decay=1.0, partials=((1, 1.0), (2.76, 0.5), (5.4, 0.25), (8.93, 0.12)), detune=0.0):
    """A struck metal or glass tone: inharmonic partials that die away (the high ones faster)."""
    t = time(seconds)
    out = np.zeros_like(t)
    for ratio, amp in partials:
        f = f0 * ratio * (1 + detune)
        out += amp * np.sin(2 * np.pi * f * t) * np.exp(-t * (1.5 + ratio) / decay)
    return out


def thump(seconds, f_start, f_end, decay=0.25):
    """A low hit: a sine dropping in pitch."""
    return tone(seconds, f_start, f_end) * env(seconds, 0.003, decay)


def crackle(seconds, density=60, seed=0, lo=800, hi=6000):
    """Short random clicks (stone, sparks, candy)."""
    rng = np.random.default_rng(seed)
    out = np.zeros(int(seconds * SR))
    for _ in range(int(density * seconds)):
        at = rng.integers(0, max(1, len(out) - 600))
        length = rng.integers(60, 500)
        out[at:at + length] += rng.standard_normal(length) * np.exp(-np.arange(length) / (length / 4)) * rng.uniform(0.3, 1)
    return band(out, lo, hi)


def reverb(x, t60=1.2, wet=0.3, tone_hz=5000, seed=7):
    """Room: the sound convolved with a decaying noise tail."""
    n = int(t60 * SR)
    t = np.arange(n) / SR
    ir = np.random.default_rng(seed).standard_normal(n) * np.exp(-6.9 * t / t60)
    ir = lowpass(ir, tone_hz)
    ir[:int(0.012 * SR)] *= np.linspace(0, 1, int(0.012 * SR))
    size = len(x) + n
    y = np.fft.irfft(np.fft.rfft(x, size) * np.fft.rfft(ir, size), size)
    y /= np.max(np.abs(y)) + 1e-9
    dry = np.pad(x, (0, n))
    dry /= np.max(np.abs(dry)) + 1e-9
    return (1 - wet) * dry + wet * y


def at(total, parts):
    """Mixes (start time, sound, gain) parts into one track `total` seconds long."""
    out = silence(total)
    for start, sound, gain in parts:
        i = int(start * SR)
        end = min(len(out), i + len(sound))
        if end > i:
            out[i:end] += gain * sound[:end - i]
    fade = int(0.03 * SR)
    out[-fade:] *= np.linspace(1, 0, fade)      # nothing is cut off with a click
    return out


def drive(x, amount=2.0):
    return np.tanh(x * amount) / np.tanh(amount)


def finish(x, peak=0.85, fade_out=0.05):
    x = x - np.mean(x)
    n = int(fade_out * SR)
    if n and len(x) > n:
        x[-n:] *= np.linspace(1, 0, n)
    x[:64] *= np.linspace(0, 1, 64)
    return x / (np.max(np.abs(x)) + 1e-9) * peak


def norm(x):
    return x / (np.max(np.abs(x)) + 1e-9)


# ---- Kurogane: a katana -------------------------------------------------------------------------------


def kurogane():
    s = {}
    whoosh = sweep_noise(0.32, 700, 6500, 0.55, 1) * env(0.32, 0.07, 0.25)
    shing = bell(0.9, 2950, 0.45, ((1, 1), (1.39, 0.7), (1.91, 0.45), (2.47, 0.3), (3.3, 0.15)))
    shing += 0.4 * bell(0.9, 4380, 0.35, ((1, 1), (1.5, 0.4)))
    draw = highpass(noise(0.12, 2), 3000) * env(0.12, 0.002, 0.08)
    s['flash'] = finish(reverb(at(1.0, [(0, draw, 0.5), (0.02, whoosh, 1.0), (0.05, norm(shing), 0.55)]), 1.0, 0.25))

    click = band(noise(0.04, 3), 1500, 7000) * env(0.04, 0.001, 0.02)
    ring = bell(0.5, 3300, 0.25, ((1, 1), (1.62, 0.4)))
    s['sheathe'] = finish(reverb(at(0.6, [(0, click, 1.0), (0.01, norm(ring), 0.35)]), 0.6, 0.2))

    slices = []
    for k, start in enumerate((0.0, 0.065, 0.13)):
        sl = sweep_noise(0.09, 1500 + k * 400, 7000, 0.45, 10 + k) * env(0.09, 0.008, 0.06)
        slices.append((start, sl, 1.0 - k * 0.15))
    wet = band(noise(0.25, 4), 900, 3200) * env(0.25, 0.004, 0.12)
    low = thump(0.3, 140, 60, 0.12)
    s['cut'] = finish(reverb(at(0.75, slices + [(0.16, wet, 0.6), (0.16, low, 0.7)]), 0.7, 0.2))

    hit_noise = band(noise(0.2, 5), 600, 5000) * env(0.2, 0.002, 0.07)
    s['hit'] = finish(at(0.45, [(0, hit_noise, 0.9), (0, thump(0.3, 160, 70, 0.1), 0.8),
                                (0.005, norm(bell(0.3, 2500, 0.12)), 0.25)]))

    drip = tone(0.25, 420, 170) * env(0.25, 0.004, 0.1) + 0.3 * band(noise(0.25, 6), 300, 1500) * env(0.25, 0.002, 0.04)
    s['bleed'] = finish(reverb(drip, 0.4, 0.2), peak=0.6)

    scrape = band(noise(0.8, 13), 2500, 8000) * swell(0.8, 0.55, 1.6) * env(0.8, 0.0, 0.75, 0.5)
    hum = tone(1.3, 110) * env(1.3, 0.15, 1.0, 0.2) + 0.4 * tone(1.3, 165) * env(1.3, 0.2, 0.9, 0.2)
    ring = bell(1.3, 1480, 0.9, ((1, 1), (1.5, 0.4), (2.2, 0.2)))
    s['iaido'] = finish(reverb(at(1.4, [(0, scrape, 0.5), (0, hum, 0.6), (0.5, norm(ring), 0.3)]), 1.2, 0.35, 3500), peak=0.75)

    clang = bell(0.9, 2200, 0.4, ((1, 1), (1.37, 0.8), (2.05, 0.55), (2.74, 0.35), (3.9, 0.2)))
    clang += 0.6 * bell(0.9, 3150, 0.3, ((1, 1), (1.6, 0.5)))
    tick = highpass(noise(0.03, 14), 3000) * env(0.03, 0.001, 0.015)
    s['parry'] = finish(reverb(at(1.0, [(0, tick, 0.8), (0, norm(clang), 0.9), (0, thump(0.25, 220, 110, 0.06), 0.5)]),
                               0.9, 0.3, 6000))

    rush = sweep_noise(0.22, 1200, 8000, 0.5, 15) * env(0.22, 0.03, 0.15)
    chop = sweep_noise(0.12, 2000, 9000, 0.4, 16) * env(0.12, 0.004, 0.08)
    s['counter'] = finish(reverb(at(0.9, [(0, rush, 0.8), (0.12, chop, 1.0), (0.13, thump(0.4, 150, 55, 0.15), 0.9),
                                          (0.14, norm(bell(0.6, 2600, 0.3)), 0.3),
                                          (0.15, band(noise(0.25, 17), 800, 3000) * env(0.25, 0.003, 0.1), 0.5)]),
                                  0.8, 0.25))

    deep = sweep_noise(0.14, 1400, 7500, 0.45, 18) * env(0.14, 0.006, 0.09)
    s['edge'] = finish(reverb(at(0.6, [(0, deep, 1.0), (0.05, wet, 0.6), (0.05, thump(0.3, 130, 55, 0.12), 0.8)]), 0.6, 0.2))

    unsheathe = band(noise(0.45, 19), 3000, 9000) * swell(0.45, 0.4, 1.4) * env(0.45, 0.0, 0.42, 0.3)
    s['draw'] = finish(reverb(at(1.0, [(0, unsheathe, 0.6), (0.38, norm(shing), 0.7)]), 1.0, 0.3, 6000), peak=0.75)
    return s


# ---- Sugarcrash: candy ----------------------------------------------------------------------------------


def _blip(f, seconds=0.12):
    return bell(seconds, f, 0.12, ((1, 1), (2.0, 0.3), (3.0, 0.15)))


def sugarcrash():
    s = {}
    rng = np.random.default_rng(22)
    throw = sweep_noise(0.4, 900, 5500, 0.55, 21) * env(0.4, 0.04, 0.3)
    whistle = tone(0.4, 700, 1500, vibrato=(9, 0.01)) * env(0.4, 0.05, 0.3)
    rope = crackle(0.4, 120, 20, 2000, 7000) * env(0.4, 0.02, 0.3)
    s['hook'] = finish(reverb(at(0.6, [(0, throw, 1.0), (0, whistle, 0.2), (0, rope, 0.25)]), 0.5, 0.2))

    snap = band(noise(0.06, 23), 1500, 8000) * env(0.06, 0.001, 0.03)
    s['catch'] = finish(reverb(at(0.6, [(0, snap, 0.8), (0, thump(0.25, 180, 80, 0.08), 0.8),
                                        (0.01, tone(0.1, 1000, 330) * env(0.1, 0.002, 0.06), 0.6),
                                        (0.03, norm(_blip(1568, 0.25)), 0.35)]), 0.5, 0.25))

    arp = [(k * 0.055, norm(_blip(f, 0.3)), 0.45 + 0.1 * k) for k, f in enumerate((1047, 1319, 1568, 2093, 2637))]
    s['full'] = finish(reverb(at(0.8, arp), 0.8, 0.35), peak=0.7)

    crack = band(noise(0.25, 24), 900, 9000) * env(0.25, 0.001, 0.06)
    bells = [(0.01 + rng.uniform(0, 0.25), norm(_blip(rng.uniform(1400, 3600), 0.25)), 0.3) for _ in range(10)]
    s['crash'] = finish(reverb(at(0.9, [(0, crack, 1.0), (0, thump(0.4, 140, 50, 0.15), 0.9),
                                        (0, tone(0.12, 900, 250) * env(0.12, 0.002, 0.08), 0.6)] + bells), 0.8, 0.3))

    t = time(3.2)
    spin_rate = 6 + 4 * t / 3.2
    spin = 0.55 + 0.45 * np.sin(2 * np.pi * np.cumsum(spin_rate) / SR)
    wind = sweep_noise(3.2, 700, 2600, 0.8, 24) * spin * np.minimum(1, t / 0.25) * np.exp(-np.clip(t - 2.8, 0, None) * 6)
    wind += 0.4 * sweep_noise(3.2, 2500, 900, 0.7, 25) * (1 - spin) * np.minimum(1, t / 0.4)
    sparks = [(rng.uniform(0.1, 2.9), norm(_blip(rng.uniform(1500, 3200), 0.15)), 0.18) for _ in range(18)]
    s['cyclone'] = finish(reverb(at(3.3, [(0, wind, 1.0)] + sparks), 0.9, 0.25), peak=0.7)

    s['pop'] = finish(at(0.18, [(0, tone(0.08, 1100, 320) * env(0.08, 0.001, 0.05), 1.0),
                                (0, highpass(noise(0.01, 26), 2000), 0.3)]), peak=0.7)

    crack = band(noise(0.3, 27), 800, 9000) * env(0.3, 0.001, 0.08)
    bells = [(0.02 + rng.uniform(0, 0.4), norm(_blip(rng.uniform(1200, 3600), 0.3)), 0.3) for _ in range(14)]
    s['burst'] = finish(reverb(at(1.1, [(0, crack, 0.9), (0, thump(0.5, 120, 45, 0.2), 0.8)] + bells), 1.0, 0.3))

    s['equip'] = finish(reverb(at(0.9, [(k * 0.07, norm(_blip(f, 0.35)), 0.5) for k, f in enumerate((1568, 2093, 2637))]
                                  + [(0, sweep_noise(0.3, 1500, 6000, 0.6, 28) * env(0.3, 0.05, 0.2), 0.3)]), 0.9, 0.35), peak=0.65)
    return s


# ---- Riftblade: the void ------------------------------------------------------------------------------


def riftblade():
    s = {}
    t = time(1.5)
    rip = band(noise(1.5, 31), 400, 6000) * (0.5 + 0.5 * np.sign(np.sin(2 * np.pi * 37 * t + 3 * np.sin(2 * np.pi * 3 * t))))
    rip *= env(1.5, 0.03, 0.9)
    drone = tone(1.5, 62) * tone(1.5, 91) * env(1.5, 0.2, 1.0, 0.3)
    whine = tone(1.5, 380, 1150, vibrato=(7, 0.02)) * env(1.5, 0.3, 0.8, 0.2)
    s['rend'] = finish(reverb(at(1.6, [(0, rip, 0.6), (0, drone, 0.7), (0, whine, 0.18),
                                       (0, sweep_noise(0.5, 2000, 300, 0.6, 32) * swell(0.5, 0.5), 0.5)]), 1.5, 0.35, 3000))

    suck = sweep_noise(0.3, 300, 4000, 0.7, 33) * swell(0.3, 0.3, 3.0)
    snap = thump(0.8, 130, 38, 0.35) + 0.5 * lowpass(noise(0.8, 34), 600) * env(0.8, 0.002, 0.25)
    zing = bell(0.6, 1760, 0.25, ((1, 1), (1.41, 0.6), (2.3, 0.3)))
    s['snap'] = finish(reverb(at(1.1, [(0, suck, 0.7), (0.3, snap, 1.0), (0.3, norm(zing), 0.18)]), 1.2, 0.3, 2500))

    comb = band(noise(0.3, 35), 300, 3000) * env(0.3, 0.002, 0.12)
    delay = int(0.004 * SR)
    comb[delay:] += 0.8 * comb[:-delay]
    s['hit'] = finish(at(0.4, [(0, comb, 0.8), (0, thump(0.3, 120, 55, 0.12), 0.9)]))

    up = tone(0.4, 300, 1600, shape='tri') * env(0.4, 0.05, 0.3)
    down = tone(0.45, 1600, 260, shape='tri') * env(0.45, 0.02, 0.35)
    air = sweep_noise(0.8, 500, 5000, 0.8, 36) * env(0.8, 0.1, 0.5)
    s['swap'] = finish(reverb(at(1.0, [(0, up, 0.35), (0.18, down, 0.35), (0, air, 0.6),
                                       (0.2, thump(0.3, 200, 70, 0.1), 0.5)]), 1.0, 0.35, 3500))

    t = time(0.5)
    glitch = tone(0.5, 500, 2400, shape='tri') * (0.5 + 0.5 * np.sign(np.sin(2 * np.pi * 45 * t))) * env(0.5, 0.01, 0.35)
    s['phase'] = finish(reverb(at(0.7, [(0, glitch, 0.4), (0, sweep_noise(0.4, 6000, 800, 0.6, 37) * env(0.4, 0.01, 0.3), 0.6),
                                        (0.02, norm(bell(0.4, 1900, 0.2)), 0.2)]), 0.8, 0.35, 4000))

    down2 = tone(0.35, 1500, 300, shape='tri') * env(0.35, 0.02, 0.25)
    up2 = tone(0.4, 260, 1300, shape='tri') * env(0.4, 0.05, 0.3)
    s['return'] = finish(reverb(at(0.9, [(0, down2, 0.35), (0.15, up2, 0.35),
                                         (0, sweep_noise(0.7, 5000, 500, 0.8, 38) * env(0.7, 0.05, 0.5), 0.6),
                                         (0.15, thump(0.3, 180, 70, 0.1), 0.5)]), 1.0, 0.35, 3500))

    t = time(0.9)
    hum = tone(0.9, 70) * tone(0.9, 104) * swell(0.9, 0.5, 2.0) * env(0.9, 0.0, 0.8, 0.5)
    shimmer = tone(0.9, 900, 1300, vibrato=(6, 0.02)) * swell(0.9, 0.5, 2.0) * env(0.9, 0.0, 0.8, 0.5)
    s['equip'] = finish(reverb(at(1.0, [(0, hum, 0.8), (0, shimmer, 0.12),
                                        (0.3, sweep_noise(0.5, 2500, 400, 0.6, 39) * env(0.5, 0.05, 0.4), 0.4)]), 1.1, 0.35, 3000), peak=0.7)
    return s


# ---- Gravebreaker: stone and iron ---------------------------------------------------------------------------


def gravebreaker():
    s = {}
    whoosh = sweep_noise(0.6, 180, 1100, 0.6, 41) * env(0.6, 0.12, 0.4)
    s['leap'] = finish(reverb(at(0.75, [(0, whoosh, 1.0), (0, thump(0.25, 110, 60, 0.1), 0.4)]), 0.6, 0.2))

    boom = thump(1.6, 92, 30, 0.7) + 0.6 * thump(1.6, 55, 28, 0.9)
    crack = lowpass(noise(0.5, 42), 2500) * env(0.5, 0.001, 0.12)
    rocks = crackle(1.4, 90, 43, 300, 3000) * env(1.4, 0.05, 0.9)
    s['slam'] = finish(drive(reverb(at(2.0, [(0, boom, 1.0), (0, crack, 0.8), (0.05, rocks, 0.5)]), 1.6, 0.3, 1800), 1.6))

    s['hit'] = finish(drive(at(0.45, [(0, thump(0.4, 110, 48, 0.14), 1.0),
                                      (0, band(noise(0.2, 44), 300, 2500) * env(0.2, 0.002, 0.06), 0.6)]), 1.5))

    t = time(2.0)
    rumble = lowpass(noise(2.0, 45), 180) * np.minimum(1, t / 0.6) * np.exp(-np.clip(t - 1.3, 0, None) * 4)
    s['rumble'] = finish(reverb(at(2.1, [(0, rumble, 1.0), (0.2, crackle(1.5, 40, 46, 200, 1500) * 0.6, 0.6)]), 1.2, 0.25, 1200))

    snap = band(noise(0.15, 47), 1000, 5000) * env(0.15, 0.001, 0.05)
    grind = band(noise(0.5, 48), 250, 1300) * (0.6 + 0.4 * np.sin(2 * np.pi * 23 * time(0.5))) * env(0.5, 0.01, 0.3)
    s['stone'] = finish(reverb(at(0.7, [(0, snap, 0.8), (0.01, grind, 0.8), (0, thump(0.35, 130, 50, 0.12), 0.9)]),
                               0.7, 0.2, 2000))

    t = time(0.7)
    roar = sweep_noise(0.7, 200, 2200, 0.7, 49) * np.minimum(1, (t / 0.6) ** 1.5) * env(0.7, 0.0, 0.7, 0.55)
    s['dive'] = finish(reverb(at(0.8, [(0, roar, 1.0), (0, lowpass(noise(0.7, 50), 160) * np.minimum(1, t / 0.5), 0.6)]),
                              0.6, 0.2, 1500))

    toll = bell(2.2, 196, 2.0, ((1, 1), (2.0, 0.5), (2.4, 0.4), (3.0, 0.25), (4.2, 0.15)))
    whisper = band(noise(1.2, 59), 1500, 5000) * swell(1.2, 0.3, 2.0) * env(1.2, 0.0, 1.0, 0.3)
    s['rites'] = finish(reverb(at(2.2, [(0, norm(toll), 0.9), (0.05, whisper, 0.15)]), 2.0, 0.35, 2500), peak=0.8)

    iron = bell(0.9, 330, 0.5, ((1, 1), (2.1, 0.5), (3.3, 0.3), (5.2, 0.15)))
    s['equip'] = finish(drive(reverb(at(1.0, [(0, thump(0.5, 120, 50, 0.18), 0.9), (0, norm(iron), 0.45),
                                              (0.02, band(noise(0.15, 51), 400, 3000) * env(0.15, 0.002, 0.06), 0.5)]),
                                     0.9, 0.25, 2000), 1.3), peak=0.75)
    return s


# ---- Starforged: stars ---------------------------------------------------------------------------------


def starforged():
    s = {}
    chord = sum(bell(1.8, f, 1.2, ((1, 1), (2.0, 0.35), (3.0, 0.2), (4.2, 0.1)), detune=0.002 * k)
                for k, f in enumerate((523.3, 659.3, 784.0, 1046.5, 1318.5)))
    riser = sweep_noise(0.9, 800, 7000, 0.6, 51) * swell(0.9, 0.9, 2.0)
    s['call'] = finish(reverb(at(1.9, [(0, riser, 0.4), (0.25, norm(chord), 0.8)]), 2.0, 0.45, 6000))

    whistle = tone(0.5, 2300, 720, vibrato=(11, 0.01)) * env(0.5, 0.05, 0.4, 0.15)
    air = sweep_noise(0.5, 4000, 1200, 0.6, 52) * env(0.5, 0.05, 0.4)
    s['fall'] = finish(at(0.55, [(0, whistle, 0.45), (0, air, 0.7)]), peak=0.75)

    crack = band(noise(0.3, 53), 700, 9000) * env(0.3, 0.001, 0.07)
    shards = sum(bell(0.9, f, 0.35) for f in (1975, 2637, 3520))
    s['impact'] = finish(reverb(at(1.1, [(0, crack, 0.8), (0, thump(0.6, 100, 40, 0.22), 1.0), (0.005, norm(shards), 0.4)]),
                                1.0, 0.3, 6000))

    s['hit'] = finish(at(0.4, [(0, norm(bell(0.35, 1800, 0.12, ((1, 1), (1.5, 0.6), (2.27, 0.4)))), 0.6),
                               (0, highpass(noise(0.02, 54), 3000), 0.4), (0, thump(0.25, 160, 80, 0.08), 0.5)]))

    t = time(3.3)
    hum = (tone(3.3, 44) + 0.5 * tone(3.3, 66, 58) + 0.3 * tone(3.3, 88, 70, vibrato=(0.6, 0.03)))
    hum *= np.minimum(1, t / 0.4)
    swirl = sweep_noise(3.3, 400, 1600, 0.5, 55) * (0.5 + 0.5 * np.sin(2 * np.pi * (1.5 + t) * t))
    pitch = tone(3.3, 220, 110, vibrato=(5, 0.02), shape='tri') * 0.15
    s['singularity'] = finish(reverb(at(3.4, [(0, drive(hum, 1.5), 0.8), (0, swirl, 0.35), (0, pitch, 1.0)]), 1.4, 0.3, 2000), peak=0.55)

    rev = sweep_noise(0.2, 300, 6000, 0.7, 56) * swell(0.2, 0.2, 3.0)
    boom = thump(1.5, 70, 28, 0.6) + 0.6 * lowpass(noise(1.5, 57), 500) * env(1.5, 0.002, 0.4)
    glitter = sum(bell(1.3, f, 0.8) for f in (1567, 2093, 2637, 3136))
    s['nova'] = finish(drive(reverb(at(1.8, [(0, rev, 0.6), (0.2, boom, 1.0), (0.2, norm(glitter), 0.3),
                                              (0.2, band(noise(0.4, 58), 1500, 9000) * env(0.4, 0.001, 0.15), 0.5)]),
                                    1.6, 0.35, 5000), 1.4))

    small = band(noise(0.2, 60), 1200, 9000) * env(0.2, 0.001, 0.05)
    tinkle = sum(bell(0.7, f, 0.3) for f in (2349, 3136, 3951))
    s['starstruck'] = finish(reverb(at(0.8, [(0, small, 0.7), (0, thump(0.4, 130, 55, 0.15), 0.8), (0.005, norm(tinkle), 0.45)]),
                                    0.8, 0.3, 6000))

    sparkle = sum(bell(1.0, f, 0.6) for f in (1046.5, 1568, 2093))
    s['equip'] = finish(reverb(at(1.0, [(0, sweep_noise(0.35, 1500, 7000, 0.6, 61) * swell(0.35, 0.35), 0.4),
                                        (0.3, norm(sparkle), 0.6)]), 1.2, 0.4, 7000), peak=0.7)
    return s


WEAPONS = {'kurogane': kurogane, 'sugarcrash': sugarcrash, 'riftblade': riftblade,
           'gravebreaker': gravebreaker, 'starforged': starforged}


def main(names):
    for name in names:
        folder = os.path.join(OUT, name)
        os.makedirs(folder, exist_ok=True)
        for sound, data in WEAPONS[name]().items():
            path = os.path.join(folder, sound + '.ogg')
            soundfile.write(path, data.astype(np.float32), SR, format='OGG', subtype='VORBIS')
            print(f'{name}/{sound}.ogg  {len(data) / SR:.2f}s  {os.path.getsize(path) // 1024} KB')


if __name__ == '__main__':
    main(sys.argv[1:] or list(WEAPONS))
