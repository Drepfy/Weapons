"""Sugarcrash: a candy war-scythe. A long crescent blade sweeps out from the top of the shaft: a
white candy spine swirled with crimson, a body of deep glossy crimson hard candy, and a cutting
edge that glows pink and shimmers along its length. A silver collar set with a cut crimson gem
holds it to a round, glossy candy-cane shaft with a silver ferrule and tip.
"""
import math

from forge import Weapon, shape
from looks import gem, helix
from paint import bezier, clamp, hexrgb, line_dist, mix, noise, ramp, smooth

OUTER = bezier((8.8, 14.5), (7.4, 16.55), (3.2, 16.2), (1.0, 11.1), 48)
INNER = bezier((1.0, 11.1), (2.9, 12.9), (5.9, 13.25), (8.05, 12.85), 48)
BLADE = OUTER + INNER[1:]

CANDY = ramp('#3d0210', '#7a0820', '#b8102f', '#e8264a', '#ff7088')
WHITE = hexrgb('#fbf3f1')
PINK = ramp('#3a0820', '#c0206a', '#ff5aa5', '#ffb0d6', '#ffffff')
SILVER = ramp('#2a2d35', '#5f6672', '#a3abb8', '#e4e9f0', '#ffffff')
GEM = ramp('#2a0210', '#7a0a20', '#d0182e', '#ff6a80', '#ffe0e6')
RED = hexrgb('#d81838')


def across(x, y):
    """0 at the spine (outer curve) .. 1 at the cutting edge (inner curve)."""
    d_out = line_dist(OUTER, x, y)
    d_in = line_dist(INNER, x, y)
    return d_out / max(d_out + d_in, 1e-6)


def blade_height(s):
    q = across(s.x, s.y)
    h = 0.2 * math.sqrt(max(0.0, 1 - (q / 0.6) ** 2)) if q < 0.6 else 0.0   # the rounded spine
    h = max(h, 0.16 - 0.14 * smooth(0.55, 1.0, q))                          # sloping to the edge
    return h - 0.03 * smooth(0.05, 0.0, s.d)


def blade_colour(s):
    q = across(s.x, s.y)
    if q < 0.3:
        # white candy with crisp crimson stripes twisting along the spine
        swirl = (s.x * 1.1 + s.y * 0.9 + q * 3.0) % 1.0
        c = mix(WHITE, RED, smooth(0.03, 0.0, abs(swirl - 0.5) - 0.14))
        c = mix(c, (1.0, 1.0, 1.0), 0.5 * math.exp(-((q - 0.1) / 0.035) ** 2))     # shine on the round
        return mix(c, CANDY(0.7), smooth(0.25, 0.3, q))
    # deep crimson hard candy, glowing from inside, with a clear glossy shine along the curve
    inner = math.exp(-((q - 0.5) / 0.15) ** 2)
    swirl = 0.5 + 0.5 * math.sin((s.x * 0.9 + s.y * 0.7) * 5.0 + q * 9.0)
    c = CANDY(0.38 + 0.32 * inner + 0.08 * swirl)
    c = mix(c, PINK(0.6), smooth(0.66, 0.82, q) * 0.65)
    shine = math.exp(-((q - 0.4) / 0.035) ** 2) * (0.6 + 0.4 * noise(s.x * 3, s.y * 3, 4))
    c = mix(c, (1.0, 0.92, 0.95), 0.75 * shine)
    if noise(s.x * 55, s.y * 55, 9) > 0.86:
        c = mix(c, (1.0, 1.0, 1.0), 0.7)                                # sugar sparkles
    return c


def edge_glow(s, t):
    q = across(s.x, s.y)
    run = max(0.0, math.sin(2 * math.pi * (t * 1.5 + s.x * 0.14))) ** 2
    return 0.4 + 0.35 * run + 0.25 * smooth(0.82, 1.0, q)


def shaft_colour(s):
    p = helix(s, 0.8)
    red = smooth(0.012, -0.012, abs(p - 0.5) - 0.25)        # crisp stripes
    return mix(WHITE, RED, red)


def build():
    w = Weapon('sugarcrash', grip=3.0)
    blade = shape(BLADE)
    w.sheet('blade', blade, 0.32, blade_colour,
            height=blade_height, relief=2.5, metal=0.1, gloss=0.85, spec=1.2,
            sheen=lambda s: 0.0)
    w.sheet('cutting edge', lambda x, y: blade(x, y) and across(x, y) > 0.8, None, '#7a0a3a', box=blade.box,
            gloss=0.85, spec=1.0, glow=edge_glow, glow_colours=PINK, glow_strength=1.2)
    # silver collar with a gem
    w.rod('collar', 8.0, 12.75, 14.55, 0.66,
          lambda s: SILVER(0.55 + 0.15 * smooth(0.12, 0.0, s.d) if s.cap == 0 else 0.6), caps=(True, True),
          metal=1.0, gloss=0.75, spec=1.0)
    for y0, y1 in ((12.75, 12.95), (14.35, 14.55)):
        w.rod('collar band', 8.0, y0, y1, 0.72, lambda s: SILVER(0.75), caps=(True, True), metal=1.0, gloss=0.8,
              spec=1.0)
    mask, colour, height = gem(8.0, 13.65, 0.44, 0.44, GEM, facets=8)
    w.sheet('gem', mask, 1.7, colour, height=height, relief=2.5, gloss=0.95, spec=1.4,
            glow=lambda s, t: 0.25 + 0.2 * (0.5 + 0.5 * math.sin(2 * math.pi * t)), glow_colours=GEM,
            glow_strength=0.5)
    # candy-cane shaft, silver ferrule and tip
    w.rod('shaft', 8.0, 0.85, 12.75, 0.42, shaft_colour, gloss=0.85, spec=1.3,
          height=lambda s: -0.01 * smooth(0.03, 0.0, abs(abs(helix(s, 0.8) - 0.5) - 0.25)), relief=2.0)
    w.rod('ferrule', 8.0, 0.35, 0.9, 0.55, lambda s: SILVER(0.6), caps=(True, True), metal=1.0, gloss=0.75,
          spec=1.0)
    w.rod('tip', 8.0, 0.0, 0.35, 0.34, lambda s: SILVER(0.7), caps=(True, False), metal=1.0, gloss=0.75, spec=1.0)
    return w
