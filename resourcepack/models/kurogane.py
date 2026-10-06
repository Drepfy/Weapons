"""Kurogane: a crimson-steel katana. A long curved blade of mirror-polished steel: a frosted
cutting edge bounded by a wavy temper line that glows crimson and pulses up the blade, a crisp
ridge line, a darker burnished back with a red-lacquered groove. A gold collar, a round black
iron guard rimmed in gold, a round handle bound in crimson silk cord over white ray skin, and a
black and gold pommel cap.
"""
import math

from forge import Weapon
from paint import clamp, fbm, hexrgb, mix, noise, ramp, smooth

Y_BLADE, Y_KISSAKI, Y_TIP = 6.25, 14.3, 15.9
STEEL = hexrgb('#cfd6e0')
FROST = hexrgb('#f6f8fb')
BACK = hexrgb('#59616f')
SPINE = hexrgb('#2e333d')
LACQUER = hexrgb('#5c0a14')
GOLD = ramp('#6e4210', '#c08a2c', '#f0c860', '#fff2c0')
IRON = hexrgb('#24262c')
CORD = ramp('#3d0109', '#8c0c1e', '#d3233a', '#ff6878')
SKIN = hexrgb('#e9e4dc')
GLOW = ramp('#2a0006', '#8a0a1a', '#ff2038', '#ff7a8a', '#fff0f2')


def bow(y):
    """How far the blade curves towards its back (right) at height y."""
    return 0.45 * max(0.0, (y - Y_BLADE) / (Y_TIP - Y_BLADE)) ** 2


def half(y):
    return 0.52 - 0.07 * clamp((y - Y_BLADE) / (Y_KISSAKI - Y_BLADE))


def edge(y):
    x = 8 + bow(y) - half(y)
    if y > Y_KISSAKI:
        q = (y - Y_KISSAKI) / (Y_TIP - Y_KISSAKI)
        x += (spine(y) - x) * (1 - math.sqrt(max(0.0, 1 - q ** 1.25)))   # the edge sweeps up to the point
    return x


def spine(y):
    x = 8 + bow(y) + half(y)
    if y > Y_KISSAKI:
        x -= 0.22 * ((y - Y_KISSAKI) / (Y_TIP - Y_KISSAKI)) ** 2.4
    return x


def across(x, y):
    """0 at the cutting edge, 1 at the back."""
    return (x - edge(y)) / max(spine(y) - edge(y), 1e-6)


def in_blade(x, y):
    return Y_BLADE <= y <= Y_TIP and edge(y) <= x <= spine(y)


def hamon(y):
    """Where the temper line runs (fraction of the way from edge to back): rolling waves with
    smaller ripples on them, like a real gunome hamon."""
    return 0.32 + 0.06 * abs(math.sin(y * 2.6)) + 0.03 * math.sin(y * 7.3 + 1.0) + 0.012 * math.sin(y * 19.0)


RIDGE = 0.68


def in_groove(x, y):
    u = across(x, y)
    top = 12.4 + 0.12 * math.cos((u - 0.79) * 20)
    return 0.725 <= u <= 0.855 and Y_BLADE + 0.3 <= y <= top


def ridge(y):
    if y <= Y_KISSAKI:
        return RIDGE
    return RIDGE + 0.3 * (y - Y_KISSAKI) / (Y_TIP - Y_KISSAKI)


def blade_height(s):
    u = across(s.x, s.y)
    RIDGE = ridge(s.y)
    if u < RIDGE:
        h = 0.03 + 0.17 * u / RIDGE
    else:
        h = 0.2 - 0.07 * (u - RIDGE) / (1 - RIDGE)
    h -= 0.02 * smooth(0.0, 0.05, 0.05 - s.d)          # rounded off at the very outline
    if in_groove(s.x, s.y):
        g = (across(s.x, s.y) - 0.79) / 0.065
        h -= 0.05 * math.sqrt(max(0.0, 1 - g * g))
    if s.y > Y_KISSAKI - 0.02:
        h += 0.02 * smooth(0.04, 0.0, abs(s.y - Y_KISSAKI))   # the crisp line across the point
    return h


def blade_colour(s):
    x, y = s.x, s.y
    u = across(x, y)
    brushed = 0.95 + 0.05 * noise(x * 46, y * 1.4, 3)
    if u < RIDGE:
        hm = hamon(y)
        # frosted hardened edge (with sparkling nie crystals), misty where it meets the polished
        # steel, which darkens a little towards the ridge
        c = mix(FROST, STEEL, smooth(hm - 0.04, hm + 0.02, u))
        c = mix(c, hexrgb('#9aa3b2'), 0.35 * smooth(hm + 0.05, RIDGE, u))
        mist = math.exp(-abs(u - hm) / 0.05)
        c = mix(c, hexrgb('#ff7a8c'), 0.3 * mist)
        nie = noise(x * 60, y * 60, 17)
        if u < hm and nie > 0.78:
            c = mix(c, (1.0, 1.0, 1.0), 0.8)
        if u < 0.07:
            c = mix(c, (1.0, 1.0, 1.0), 0.75)             # the honed edge
    elif y > Y_KISSAKI:
        c = mix(STEEL, BACK, 0.4)                         # the back of the point, polished
    else:
        # the burnished flat above the ridge: dark blue-black steel, a bright ridge line
        c = mix(BACK, SPINE, smooth(0.8, 0.95, u))
        c = mix(c, hexrgb('#e8ecf2'), smooth(0.03, 0.0, abs(u - RIDGE - 0.012)))
        if in_groove(x, y):
            c = LACQUER
    c = tuple(v * brushed for v in c)
    if y > Y_KISSAKI:
        c = mix(c, FROST, 0.5 * smooth(0.6, 0.1, u))
    return c


def blade_sheen(s):
    u = across(s.x, s.y)
    y = s.y
    v = 0.9 * math.exp(-((y - 11.6 + 1.6 * u) / 0.75) ** 2) + 0.45 * math.exp(-((y - 8.0 + 1.2 * u) / 0.45) ** 2)
    v += 0.5 * math.exp(-((y - 15.0 + 0.8 * u) / 0.35) ** 2)
    if u > ridge(y):
        v *= 0.4
    return v - 0.12


def temper_glow(s, t):
    pulse = max(0.0, math.sin(2 * math.pi * (t - s.y * 0.11))) ** 3
    return 0.42 + 0.5 * pulse + 0.08 * noise(s.y * 6, t * 8, 5)


def in_temper(x, y):
    if not (Y_BLADE + 0.3 <= y <= Y_KISSAKI - 0.06) or not in_blade(x, y):
        return False
    hx = edge(y) + hamon(y) * (spine(y) - edge(y))
    return abs(x - hx) < 0.085


def cord_pattern(s):
    """The silk cord wound in diamonds round the handle: (on a cord, which cord is on top, how far
    across the cord: 0 middle .. 1 its edge)."""
    k = 1.05
    turn = s.a / (2 * math.pi)
    p1 = (s.y * k + turn) % 1.0
    p2 = (s.y * k - turn) % 1.0
    w = 0.29
    d1, d2 = abs(p1 - 0.5) / w, abs(p2 - 0.5) / w
    on1, on2 = d1 < 1, d2 < 1
    if on1 and on2:
        top = 1 if (math.floor(s.y * k + turn) + math.floor(s.y * k - turn)) % 2 == 0 else 2
        return True, (d1 if top == 1 else d2)
    if on1:
        return True, d1
    if on2:
        return True, d2
    return False, min(d1, d2)


def handle_colour(s):
    on, d = cord_pattern(s)
    if on:
        weave = 0.85 + 0.15 * math.sin((s.y * 1.05 + s.a / 6.28) * 120)
        return tuple(v * weave for v in CORD(0.55 + 0.3 * (1 - d)))
    nod = noise(s.u * 26, s.y * 26, 9)
    return mix(SKIN, hexrgb('#b8b0a6'), 0.35 * nod + 0.3 * smooth(1.0, 1.6, d))


def handle_height(s):
    on, d = cord_pattern(s)
    if on:
        return 0.05 * math.sqrt(max(0.0, 1 - d * d))
    return 0.012 * noise(s.u * 26, s.y * 26, 9)


def tsuba_colour(s):
    if s.cap == 0:
        return GOLD(0.62 + 0.25 * noise(s.a * 3, s.y * 12, 4))
    rho = 1.55 - s.d
    if s.d < 0.14:
        return GOLD(0.6 + 0.3 * smooth(0.14, 0.0, s.d))
    if rho < 0.82:
        return GOLD(0.5) if rho > 0.72 else IRON
    hammered = 0.8 + 0.4 * fbm(s.x * 5, s.z * 5, 21)
    return tuple(v * hammered for v in mix(IRON, hexrgb('#3a2c2a'), 0.4 * fbm(s.x * 2, s.z * 2, 22)))


def tsuba_height(s):
    if s.cap == 0:
        return 0.0
    rho = 1.55 - s.d
    h = 0.03 * smooth(0.18, 0.1, s.d)
    h += 0.015 * fbm(s.x * 5, s.z * 5, 21)
    h += 0.02 * smooth(0.86, 0.8, rho)
    return h


def build():
    w = Weapon('kurogane', grip=2.9)
    w.sheet('blade', in_blade, lambda s: 0.19 if across(s.x, s.y) < 0.3 else 0.31, blade_colour,
            box=(7.2, Y_BLADE, 9.3, Y_TIP), height=blade_height, relief=3.0, metal=0.85, gloss=0.75,
            spec=0.9, sheen=blade_sheen)
    w.sheet('temper line', in_temper, None, '#4a0810', box=(7.3, Y_BLADE, 9.0, Y_KISSAKI),
            metal=0.3, gloss=0.6, glow=temper_glow, glow_colours=GLOW, glow_strength=1.3)
    # gold collar, guard, collar of the handle
    w.rod('collar', 8.0, 5.55, 6.3, 0.74, lambda s: GOLD(0.55 + 0.25 * math.sin(s.y * 40 + s.a * 3) ** 2),
          caps=(False, True), metal=1.0, gloss=0.6, spec=0.8)
    w.rod('guard', 8.0, 5.13, 5.55, 1.55, tsuba_colour, sides=16, caps=(True, True), height=tsuba_height,
          relief=2.0, metal=0.75, gloss=0.55, spec=0.7)
    w.rod('handle collar', 8.0, 4.83, 5.13, 0.64, lambda s: GOLD(0.55), metal=1.0, gloss=0.6, spec=0.8)
    w.rod('handle', 8.0, 0.98, 4.83, 0.57, handle_colour, height=handle_height, relief=2.5,
          gloss=0.45, spec=0.5)
    w.rod('pommel', 8.0, 0.32, 0.98, 0.62,
          lambda s: GOLD(0.55) if (s.cap == 0 and (s.y > 0.86 or s.y < 0.42)) or (s.cap and s.d < 0.1) else IRON,
          caps=(True, False), metal=0.8, gloss=0.6, spec=0.7)
    return w
