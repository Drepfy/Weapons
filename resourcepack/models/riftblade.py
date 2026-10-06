"""Riftblade: a broad greatsword of dark void steel flecked with stars, split down the middle by a
glowing rift that flows up the blade and tears its point in two. Lavender bevels, a crescent
guard with silver rims and a cut violet crystal, a leather grip bound in violet wire, and a
crystal pommel.
"""
import math

from forge import Weapon, shape
from looks import gem, wrap
from paint import _hash, clamp, fbm, hexrgb, mix, noise, ramp, smooth

Y_BLADE, Y_FORK, Y_TIP = 6.05, 13.6, 15.9
HALF = 1.05
VOID = ramp('#0c0a10', '#17141e', '#241f30', '#352d47')
BEVEL = ramp('#3b3846', '#6f6c80', '#b4b1c4', '#f4f2fb')
RIFT = ramp('#1a0538', '#5a16b8', '#a24dff', '#d9a8ff', '#ffffff')
GUARD = ramp('#121116', '#22202a', '#393644', '#67637a', '#d2cfe0')
GEM = ramp('#1a0440', '#4a0f96', '#8d3df0', '#cf97ff', '#ffffff')
LEATHER = ramp('#0c0910', '#1a1422', '#2c2238', '#40344f')


def outline_x(y):
    """Half the blade's width at height y (a slight taper towards the point)."""
    return HALF - 0.12 * (y - Y_BLADE) / (Y_TIP - Y_BLADE)


def rift_half(y):
    return 0.16 if y < Y_FORK else 0.16 + (y - Y_FORK) * 0.4


def in_blade(x, y):
    if not (Y_BLADE <= y <= Y_TIP):
        return False
    d = x - 8
    w = outline_x(y)
    if abs(d) > w:
        return False
    if y > Y_FORK and abs(d) < rift_half(y) - 0.05:
        return False                                   # the rift has torn the point open
    # the two prongs: the left one long, the right one shorter
    if d < 0 and y > 15.0 and -d > w - (y - 15.0) * 1.3:
        return False
    if d > 0 and y > 14.6 and d > w - (y - 14.6) * 1.9:
        return False
    if d > 0 and y > 15.25:
        return False
    return True


def stars(x, y, density=9.0, seed=31):
    """Faint specks of light in the void steel."""
    gx, gy = math.floor(x * density), math.floor(y * density)
    v = 0.0
    for ox in (0, 1):
        for oy in (0, 1):
            cx, cy = gx + ox - 0.5, gy + oy - 0.5
            h = _hash(int(cx * 2), int(cy * 2), seed)
            if h > 0.72:
                sx = (cx + _hash(int(cx * 2), int(cy * 2), seed + 1)) / density
                sy = (cy + _hash(int(cx * 2), int(cy * 2), seed + 2)) / density
                d = math.hypot(x - sx, y - sy) * density
                v = max(v, (h - 0.72) / 0.28 * smooth(0.18, 0.0, d))
    return v


def blade_parts(s):
    d = s.x - 8
    w = outline_x(s.y)
    a = abs(d)
    return d, w - a, a - rift_half(s.y)          # side, distance from the outer edge, from the rift


def blade_height(s):
    d, e, r = blade_parts(s)
    h = 0.03 + 0.15 * smooth(0.0, 0.28, e)       # bevel up from the edge
    h -= 0.07 * smooth(0.2, 0.0, r)              # down into the rift
    return h - 0.03 * smooth(0.06, 0.0, s.d)


def blade_colour(s):
    d, e, r = blade_parts(s)
    swirl = fbm(s.x * 1.4 + fbm(s.x, s.y * 0.6, 32) * 2, s.y * 0.45, 33)
    c = VOID(0.3 + 0.7 * swirl)
    # nebula clouds of violet and deep blue drifting through the void steel
    cloud = fbm(s.x * 0.9 + 3.0, s.y * 0.5, 34)
    c = mix(c, hexrgb('#3b1670'), 0.55 * smooth(0.45, 0.8, cloud))
    c = mix(c, hexrgb('#16245a'), 0.4 * smooth(0.5, 0.85, 1 - cloud))
    c = mix(c, hexrgb('#ffffff'), 0.95 * stars(s.x, s.y, 12.0, 31))
    c = mix(c, hexrgb('#d7c6ff'), 0.7 * stars(s.x, s.y, 6.0, 35))
    bevel = smooth(0.3, 0.24, e)
    c = mix(c, BEVEL(0.2 + 0.75 * smooth(0.26, 0.0, e) + (0.1 if d < 0 else -0.12)), bevel)
    c = mix(c, hexrgb('#8a4dff'), 0.45 * smooth(0.18, 0.0, r))     # the rift's light on its walls
    return c


def rift_glow(s, t):
    a = abs(s.x - 8) / max(rift_half(s.y), 1e-6)
    core = 1.0 - min(1.0, a) * 0.7
    flow = 0.5 + 0.5 * math.sin(2 * math.pi * (t * 2 - s.y * 0.5))
    spark = 0.4 * smooth(0.8, 0.95, noise(s.x * 9, s.y * 3 - t * 12, 17))
    return core * (0.45 + 0.4 * flow) + spark


def in_rift(x, y):
    if not in_blade(x, y):
        return False
    if y < Y_FORK:
        return abs(x - 8) < rift_half(y)
    return y <= 15.4 and abs(x - 8) < rift_half(y) + 0.12


WING = [(4.9, 5.05), (5.6, 5.35), (6.6, 5.25), (8.0, 5.3), (9.4, 5.25), (10.4, 5.35), (11.1, 5.05),
        (11.6, 5.75), (11.75, 6.75), (11.35, 6.6), (10.85, 6.0), (10.0, 5.95), (8.0, 6.1),
        (6.0, 5.95), (5.15, 6.0), (4.65, 6.6), (4.25, 6.75), (4.4, 5.75)]


def guard_colour(s):
    c = GUARD(0.3 + 0.2 * fbm(s.x * 2, s.y * 6, 41))
    c = mix(c, GUARD(0.95), smooth(0.08, 0.02, s.d))            # polished silver rim
    c = mix(c, GUARD(0.08), 0.8 * smooth(0.03, 0.0, abs(s.d - 0.15)))   # engraved line inside it
    return c


def guard_height(s):
    return 0.12 * smooth(0.0, 0.2, s.d) - 0.02 * smooth(0.03, 0.0, abs(s.d - 0.15))


def gem_glow(s, t):
    return 0.35 + 0.25 * (0.5 + 0.5 * math.sin(2 * math.pi * t)) * smooth(0.6, 0.0, s.d)


def build():
    w = Weapon('riftblade', grip=3.1)
    w.sheet('blade', in_blade, lambda s: 0.26 if blade_parts(s)[1] < 0.32 else 0.42, blade_colour,
            box=(6.8, Y_BLADE, 9.2, Y_TIP), height=blade_height, relief=2.5, metal=0.7, gloss=0.7, spec=0.9,
            sheen=lambda s: 0.5 * math.exp(-((s.y - 11.5 + 0.6 * (s.x - 8)) / 1.1) ** 2))
    w.sheet('rift', in_rift, None, '#3a0e78', box=(7.2, Y_BLADE, 8.8, 15.5), glow=rift_glow,
            glow_colours=RIFT, glow_strength=1.15)
    w.sheet('guard', shape(WING), 0.72, guard_colour, height=guard_height, relief=2.0, metal=0.8, gloss=0.6,
            spec=0.8)
    mask, colour, height = gem(8.0, 5.68, 0.6, 0.6, GEM, facets=4)
    w.sheet('crystal', mask, 1.0, colour, height=height, relief=2.5, gloss=0.9, spec=1.2,
            glow=gem_glow, glow_colours=GEM, glow_strength=0.6)
    w.rod('grip', 8.0, 1.3, 5.05, 0.48,
          lambda s: wrap(s, 0.42, 0.74, LEATHER, hexrgb('#a465ff'))[0],
          height=lambda s: wrap(s, 0.42, 0.74, LEATHER, None)[1], relief=2.0, gloss=0.4, spec=0.5,
          metal=lambda s: 0.0 if wrap(s, 0.42, 0.74, LEATHER, None)[1] else 0.9)
    w.rod('pommel cap', 8.0, 0.95, 1.32, 0.62, lambda s: GUARD(0.55 if s.cap else 0.45), caps=(True, True),
          metal=0.85, gloss=0.6, spec=0.8)
    mask, colour, height = gem(8.0, 0.52, 0.5, 0.5, GEM, facets=4)
    w.sheet('pommel', mask, 0.9, colour, height=height, relief=2.5, gloss=0.9, spec=1.2,
            glow=gem_glow, glow_colours=GEM, glow_strength=0.6)
    return w
