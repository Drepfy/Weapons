"""Sugarcrash: a crescent blade. An ivory spine, crimson layers and a soft pink glow along the
cutting edge, a raised spine following the curve, a silver crossguard with curled ends and a
crimson gem, and a glossy candy-striped handle with a silver pommel.
"""
import math
from functools import lru_cache

from mesh import Model
from paint import bevel, crystal, fbm, hexrgb, metal, mix, ramp, scale, smooth

IVORY, IVORY_SHADE = hexrgb('#fff7f1'), hexrgb('#e3cfc9')
INLAY = hexrgb('#5c0a1d')
CRIMSON, CRIMSON_DARK = hexrgb('#d0173a'), hexrgb('#8f0e28')
GLOW, GLOW_HOT = hexrgb('#ff7ab8'), hexrgb('#ffd2e8')
CANDY_RED, CANDY_WHITE = hexrgb('#d3183a'), hexrgb('#fff4f2')


def _bezier(p, t):
    u = 1 - t
    return tuple(u ** 3 * p[0][i] + 3 * u * u * t * p[1][i] + 3 * u * t * t * p[2][i] + t ** 3 * p[3][i] for i in range(2))


# the blade's centre line: up out of the guard, over to the right and down into the point
CURVE = [(8.0, 4.7), (6.9, 13.2), (13.4, 17.0), (14.3, 9.6)]
SAMPLES = [_bezier(CURVE, i / 160) for i in range(161)]


def half(t):
    """Half the blade's width along the curve: full through the middle, a sharp point at the end."""
    if t < 0.6:
        return 0.62 + 0.72 * math.sin(math.pi * min(t / 0.7, 1.0))
    return 1.31 * max(0.0, 1 - (t - 0.6) / 0.38) ** 1.25


@lru_cache(maxsize=None)
def nearest(x, y):
    """(t along the curve, signed distance: negative on the outer spine side)."""
    best = min(range(0, 161, 8), key=lambda i: (SAMPLES[i][0] - x) ** 2 + (SAMPLES[i][1] - y) ** 2)
    lo, hi = max(0, best - 8), min(160, best + 8)
    i = min(range(lo, hi + 1), key=lambda i: (SAMPLES[i][0] - x) ** 2 + (SAMPLES[i][1] - y) ** 2)
    px, py = SAMPLES[i]
    qx, qy = SAMPLES[min(160, i + 1)]
    ox, oy = SAMPLES[max(0, i - 1)]
    tx, ty = qx - ox, qy - oy
    d = math.hypot(x - px, y - py)
    cross = tx * (y - py) - ty * (x - px)                  # > 0: left of the direction of travel = outer
    return i / 160, (-d if cross > 0 else d)


def blade_colour(x, y, t, side):
    h = half(t)
    rel = side / max(h, 1e-6)                            # -1 outer spine .. +1 inner edge
    n = (fbm(x * 2, y * 2, 51) - 0.5) * 14
    if rel < -0.32:
        c = mix(IVORY, IVORY_SHADE, smooth((rel + 1) / 0.68) * 0.7)
    elif rel < -0.18:
        c = INLAY
    elif rel < 0.55:
        q = (rel + 0.18) / 0.73
        c = ramp([(0, CRIMSON_DARK), (0.35, CRIMSON), (1, mix(CRIMSON, GLOW, 0.3))], q)
    else:
        q = (rel - 0.55) / 0.45
        c = mix(GLOW, GLOW_HOT, smooth(q))               # the glowing cutting edge
    return tuple(v + n for v in c)


def blade(face, s, t, p):
    x, y = round(p[0], 3), round(p[1], 3)
    tc, side = nearest(x, y)
    if tc >= 0.985 or abs(side) > half(tc) or y < 4.55:
        return None
    return blade_colour(x, y, tc, side)


def spine(face, s, t, p):
    x, y = round(p[0], 3), round(p[1], 3)
    if face.dir in ('south', 'north'):
        tc, side = nearest(x, y)
        h = half(tc)
        return blade_colour(x, y, tc, max(-h, min(side, -0.33 * h)))
    return bevel(face, s, t, IVORY_SHADE if face.dir in ('down', 'east') else IVORY)


def candy(face, s, t, p):
    x, y, z = p
    around = math.atan2(z - 8, x - 8) / (2 * math.pi)
    phase = (y * 0.75 + around) % 1.0
    c = CANDY_RED if phase < 0.42 else CANDY_WHITE
    edge = min(abs(phase - 0.0), abs(phase - 0.42), abs(phase - 1.0))
    if edge < 0.05:
        c = mix(c, CRIMSON_DARK if c is CANDY_RED else IVORY_SHADE, 0.5)
    gloss = max(0.0, 1 - abs(s - 0.3) / 0.15) * 0.25    # a glossy stripe of light down each face
    return bevel(face, s, t, tuple(v + (255 - v) * gloss for v in c), 0.06)


def fit():
    """Every point of the blade must stay inside the inventory slot's diamond."""
    worst = 0.0
    for i in range(161):
        t = i / 160
        px, py = SAMPLES[i]
        for ang in range(0, 360, 30):
            x = px + half(t) * math.cos(math.radians(ang))
            y = py + half(t) * math.sin(math.radians(ang))
            worst = max(worst, abs(x - 8) + abs(y - 8))
    return worst


def build():
    m = Model()
    silver = metal(hexrgb('#5f6775'), hexrgb('#aeb7c5'), hexrgb('#f1f5fa'), seed=6)
    # the blade card covers the curve's bounding box; its paint cuts out the crescent
    xs = [p[0] for p in SAMPLES]
    ys = [p[1] for p in SAMPLES]
    m.card(min(xs) - 1.5, 4.55, max(xs) + 1.5, max(ys) + 1.5, 0.24, blade, tag='blade')
    # a raised spine along the outer edge, in short straight pieces
    pts = []
    for i in range(0, 141, 10):
        t = i / 160
        x, y = SAMPLES[i]
        qx, qy = SAMPLES[min(160, i + 2)]
        ox, oy = SAMPLES[max(0, i - 2)]
        tx, ty = qx - ox, qy - oy
        n = math.hypot(tx, ty)
        off = 0.62 * half(t)                              # towards the outer side (left of travel)
        pts.append((x - ty / n * off, y + tx / n * off, t))
    for (x0, y0, t0), (x1, y1, t1) in zip(pts, pts[1:]):
        w = 0.45 * min(half(t0), half(t1)) + 0.1
        m.segment((x0, y0), (x1, y1), w, 0.5 if t1 < 0.75 else 0.4, spine, tag='spine')
    # crossguard: a bar with curled tips and a crimson gem
    m.box((6.1, 3.95, 7.55), (9.9, 4.6, 8.45), silver, tag='guard')
    m.segment((6.25, 4.25), (5.35, 5.25), 0.5, 0.75, silver, tag='guard')
    m.segment((9.75, 4.25), (10.65, 5.25), 0.5, 0.75, silver, tag='guard')
    gem = crystal(hexrgb('#5c0a1d'), hexrgb('#e01e45'), hexrgb('#ffc2d0'))
    for z in (8.5, 7.5):
        m.box((7.6, 3.88, z - 0.2), (8.4, 4.68, z + 0.2), gem, rot=('z', 45.0, (8, 4.28, z)), tag='gem')
    # candy-striped handle and a silver pommel
    m.rod(-1.9, 3.95, 0.56, candy, tag='handle')
    m.rod(-2.6, -1.9, 0.7, silver, tag='pommel')
    m.box((7.68, -3.0, 7.68), (8.32, -2.36, 8.32), gem, rot=('z', 45.0, (8, -2.68, 8)), tag='gem')
    return m
