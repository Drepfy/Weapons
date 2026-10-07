"""Reaper: a soul-harvesting sickle sword. A long black-violet blade rises from the guard and
sweeps forward into a hooked point like a scythe's; its inner cutting edge burns with spectral
green soul-fire that licks into the steel, a line of soul runes glows faintly down its middle,
and barbs rise from its back. The guard is a pair of ribs curving up round a bone knuckle; the
grip is a column of vertebrae bound in violet leather, and the pommel is a skull whose eyes glow.
"""
import math

from forge import Weapon
from looks import runes, scratches
from paint import bezier, clamp, fbm, hexrgb, mix, noise, ramp, smooth

VIOLET = ramp('#07040c', '#140a22', '#25133d', '#3e2266', '#6d4aa6')
SOUL = ramp('#001a12', '#00664a', '#1fe0a8', '#8affdf', '#f2fffb')
BONE = ramp('#3a3226', '#7d6f58', '#c8bb9c', '#ece3cb', '#fffaf0')
LEATHER = ramp('#0a0510', '#1c0f2a', '#2e1a44', '#45295f')

Y_BLADE = 6.15
CENTER = bezier((8.0, Y_BLADE - 0.2), (8.75, 10.6), (8.45, 14.55), (4.55, 15.72), 72)


def _length(pts):
    return sum(math.hypot(pts[k + 1][0] - pts[k][0], pts[k + 1][1] - pts[k][1]) for k in range(len(pts) - 1))


LENGTH = _length(CENTER)


def width(t):
    """Half the blade's width a fraction t of the way to the point."""
    return (0.74 + 0.28 * math.sin(math.pi * t * 0.9)) * (1 - t ** 6) ** 0.8


def _outline():
    edge, spine = [], []
    for k, (x, y) in enumerate(CENTER):
        a, b = CENTER[max(0, k - 1)], CENTER[min(len(CENTER) - 1, k + 1)]
        dx, dy = b[0] - a[0], b[1] - a[1]
        n = math.hypot(dx, dy) or 1e-9
        nx, ny = -dy / n, dx / n                       # the left (inner, concave) side
        w = width(k / (len(CENTER) - 1))
        edge.append((x + nx * w, y + ny * w))
        spine.append((x - nx * w, y - ny * w))
    return edge, spine


EDGE, SPINE = _outline()
BLADE = EDGE + list(reversed(SPINE))[:-1]


def frame(x, y):
    """Where a point lies on the blade: (signed distance from the centre line, + towards the
    edge; distance along it from the guard; t = 0..1 to the point)."""
    best, where, side, run = 1e9, 0.0, 1.0, 0.0
    for k in range(len(CENTER) - 1):
        (x1, y1), (x2, y2) = CENTER[k], CENTER[k + 1]
        dx, dy = x2 - x1, y2 - y1
        ln = math.hypot(dx, dy) or 1e-9
        t = max(0.0, min(1.0, ((x - x1) * dx + (y - y1) * dy) / (ln * ln)))
        d = math.hypot(x - x1 - t * dx, y - y1 - t * dy)
        if d < best:
            best, where = d, run + t * ln
            side = 1.0 if (dx * (y - y1) - dy * (x - x1)) >= 0 else -1.0
        run += ln
    return best * side, where, where / LENGTH


def _inside(poly):
    from paint import inside
    xs, ys = [p[0] for p in poly], [p[1] for p in poly]
    box = (min(xs), min(ys), max(xs), max(ys))

    def mask(x, y):
        return box[0] <= x <= box[2] and box[1] <= y <= box[3] and inside(poly, x, y)
    mask.box = box
    return mask


in_blade = _inside(BLADE)


def across(x, y):
    """0 at the cutting edge .. 1 at the back, and how far along (t)."""
    off, along, t = frame(x, y)
    w = max(width(clamp(t)), 1e-3)
    return clamp(0.5 - off / (2 * w)), along, t


def soul_line(along, t):
    """How far the soul-fire reaches in from the edge: tongues of flame leaning towards the point."""
    v = 0.2 + 0.08 * abs(math.sin(along * 3.3 - 0.4)) ** 1.5 + 0.03 * math.sin(along * 9.1 + 1.0)
    return v * (1 - 0.35 * smooth(0.8, 1.0, t))


def rune_depth(x, y):
    off, along, t = frame(x, y)
    if not 0.08 < t < 0.74:
        return 0.0
    return runes(along, (off + 0.05) / 0.17, cell=0.42, width=0.05, seed=31)


def blade_height(s):
    q, along, t = across(s.x, s.y)
    h = 0.03 + 0.17 * smooth(0.0, 0.42, q)                       # the long bevel from the edge
    h -= 0.04 * smooth(0.82, 1.0, q)                              # rounded off at the back
    h -= 0.025 * rune_depth(s.x, s.y)
    h -= 0.005 * scratches(s.x, s.y, 32)
    return h - 0.02 * smooth(0.05, 0.0, s.d)


def blade_colour(s):
    q, along, t = across(s.x, s.y)
    line = soul_line(along, t)
    if q < line:
        return mix(hexrgb('#06402e'), hexrgb('#0d7a58'), smooth(line, 0.0, q))
    # black-violet steel, clouded like smoke, with green mist where the soul-fire meets it
    cloud = fbm(s.x * 1.3 + fbm(s.x * 2.2, s.y * 1.1, 33) * 1.4, s.y * 0.8, 34)
    c = VIOLET(0.28 + 0.32 * cloud)
    c = mix(c, hexrgb('#0e5a48'), 0.6 * math.exp(-(q - line) / 0.05))
    c = mix(c, VIOLET(0.75), 0.4 * smooth(0.86, 0.97, q))         # the polished back
    c = mix(c, VIOLET(0.85), 0.4 * scratches(s.x, s.y, 32))
    return mix(c, hexrgb('#04140f'), 0.85 * rune_depth(s.x, s.y))


def blade_sheen(s):
    q, along, t = across(s.x, s.y)
    return 0.8 * math.exp(-((along - 5.2 + 1.2 * q) / 0.8) ** 2) + 0.4 * math.exp(-((along - 2.0) / 0.4) ** 2) - 0.1


def in_soul(x, y):
    if not in_blade(x, y):
        return False
    q, along, t = across(x, y)
    return q < soul_line(along, t)


in_soul.box = in_blade.box


def soul_glow(s, t):
    """Soul-fire: brightest on the edge, wisps flowing along it towards the point."""
    q, along, tt = across(s.x, s.y)
    line = max(soul_line(along, tt), 1e-3)
    k = q / line
    wisp = fbm(along * 2.2 - t * 6.0, q * 7.0, 35)
    v = 0.9 - 0.55 * k ** 0.8 + 0.25 * (wisp - 0.5)
    return v + 0.12 * max(0.0, math.sin(2 * math.pi * (t * 1.5 - along * 0.18))) ** 2


def in_runes(x, y):
    return in_blade(x, y) and rune_depth(x, y) > 0.45


in_runes.box = in_blade.box


def rune_glow(s, t):
    _, along, _ = frame(s.x, s.y)
    return 0.3 + 0.35 * max(0.0, math.sin(2 * math.pi * (t - along * 0.12))) ** 2


# barbs rising from the back of the blade
def _barb(t0, size):
    k = int(t0 * (len(CENTER) - 1))
    (x, y), (bx, by) = SPINE[k], SPINE[min(k + 3, len(SPINE) - 1)]
    cx, cy = CENTER[k]
    ox, oy = x - cx, y - cy
    n = math.hypot(ox, oy) or 1e-9
    ox, oy = ox / n, oy / n
    dx, dy = bx - x, by - y
    m = math.hypot(dx, dy) or 1e-9
    dx, dy = dx / m, dy / m
    base0 = (x - ox * 0.12 - dx * size * 0.45, y - oy * 0.12 - dy * size * 0.45)
    base1 = (x - ox * 0.12 + dx * size * 0.45, y - oy * 0.12 + dy * size * 0.45)
    tip = (x + ox * size + dx * size * 0.9, y + oy * size + dy * size * 0.9)   # leaning towards the point
    return [base0, tip, base1]


BARBS = [_barb(0.3, 0.55), _barb(0.45, 0.62), _barb(0.6, 0.55)]


# ---- the guard: ribs round a bone knuckle ---------------------------------------------------------------

RIB = bezier((8.3, 5.5), (9.5, 5.25), (10.7, 5.65), (11.15, 7.0), 36)
RIB_LENGTH = _length(RIB)


def rib(x, y):
    """(distance across the rib, 0..1 along it) for either rib (mirrored)."""
    px = 8 + abs(x - 8)
    best, where, run = 1e9, 0.0, 0.0
    for k in range(len(RIB) - 1):
        (x1, y1), (x2, y2) = RIB[k], RIB[k + 1]
        dx, dy = x2 - x1, y2 - y1
        ln = math.hypot(dx, dy) or 1e-9
        t = max(0.0, min(1.0, ((px - x1) * dx + (y - y1) * dy) / (ln * ln)))
        d = math.hypot(px - x1 - t * dx, y - y1 - t * dy)
        if d < best:
            best, where = d, (run + t * ln) / RIB_LENGTH
        run += ln
    return best, where


def rib_radius(t):
    return 0.27 * (1 - t) ** 0.6 + 0.04


def in_ribs(x, y):
    d, t = rib(x, y)
    return d < rib_radius(t)


in_ribs.box = (16 - 11.35, 4.9, 11.35, 7.15)


def rib_colour(s):
    d, t = rib(s.x, s.y)
    q = d / rib_radius(t)
    c = BONE(0.55 + 0.25 * (1 - q) - 0.15 * fbm(s.x * 4, s.y * 4, 36))
    c = mix(c, BONE(0.15), 0.6 * smooth(0.03, 0.0, abs(fbm(s.x * 3, s.y * 3, 37) - 0.5)))   # hairline cracks
    return mix(c, BONE(0.95), 0.4 * math.exp(-((q - 0.3) / 0.2) ** 2))


def rib_height(s):
    d, t = rib(s.x, s.y)
    q = clamp(d / rib_radius(t))
    return 0.16 * math.sqrt(max(0.0, 1 - q * q)) * (0.5 + 0.5 * (1 - t))


KNUCKLE_Y = 5.55


def knuckle(x, y):
    """The bone knuckle in the middle of the guard: a vertebra with a spine pointing up."""
    body = ((x - 8) / 0.62) ** 2 + ((y - KNUCKLE_Y) / 0.46) ** 2 <= 1
    wings = abs(y - KNUCKLE_Y + 0.05) < 0.13 - 0.06 * abs(x - 8) and abs(x - 8) < 1.0
    return body or wings


knuckle.box = (7.0, KNUCKLE_Y - 0.5, 9.0, KNUCKLE_Y + 0.5)


def knuckle_colour(s):
    r = math.hypot((s.x - 8) / 0.62, (s.y - KNUCKLE_Y) / 0.46)
    c = BONE(0.5 + 0.3 * (1 - clamp(r)) - 0.12 * fbm(s.x * 5, s.y * 5, 38))
    return mix(c, BONE(0.18), 0.7 * smooth(0.04, 0.0, abs(r - 0.55)) * (s.y < KNUCKLE_Y + 0.2))


# ---- the grip: vertebrae ------------------------------------------------------------------------------------

Y_GRIP0, Y_GRIP1 = 1.62, 4.9


def grip_colour(s):
    seg = ((s.y - Y_GRIP0) / 0.54) % 1.0
    if seg > 0.72:
        # a vertebra between the turns of leather
        m = (seg - 0.72) / 0.28
        return BONE(0.45 + 0.35 * math.sin(math.pi * m) - 0.1 * fbm(s.a * 3, s.y * 9, 39))
    p = (s.y / 0.18 + s.a / (2 * math.pi) * 2) % 1.0
    return LEATHER(0.35 + 0.35 * math.sin(math.pi * p) + 0.05 * noise(s.a * 9, s.y * 30, 40))


def grip_height(s):
    seg = ((s.y - Y_GRIP0) / 0.54) % 1.0
    if seg > 0.72:
        return 0.05 * math.sin(math.pi * (seg - 0.72) / 0.28)
    p = (s.y / 0.18 + s.a / (2 * math.pi) * 2) % 1.0
    return 0.015 * math.sin(math.pi * p)


# ---- the skull --------------------------------------------------------------------------------------------

SKULL_X, SKULL_Y, SKULL_R = 8.0, 1.12, 0.66
EYES = [(7.73, 1.06), (8.27, 1.06)]


def skull(x, y):
    cranium = math.hypot((x - SKULL_X) / SKULL_R, (y - SKULL_Y) / (SKULL_R * 0.95)) <= 1
    jaw = 0.08 <= y <= 0.7 and abs(x - SKULL_X) <= 0.42 - 0.12 * smooth(0.4, 0.08, y)
    return cranium or jaw


skull.box = (SKULL_X - SKULL_R, 0.05, SKULL_X + SKULL_R, SKULL_Y + SKULL_R)


def eye(x, y):
    for ex, ey in EYES:
        if ((x - ex) / 0.19) ** 2 + ((y - ey) / 0.16) ** 2 <= 1:
            return True
    return False


eye.box = (7.5, 0.88, 8.5, 1.24)


def nose(x, y):
    return 0.66 <= y <= 0.86 and abs(x - SKULL_X) <= 0.09 * (y - 0.66) / 0.2


def teeth(x, y):
    """0..1 on the dark lines between the teeth."""
    if not 0.2 <= y <= 0.5:
        return 0.0
    line = abs(((x - SKULL_X) / 0.13) % 1.0 - 0.5)
    gap = smooth(0.02, 0.0, abs(y - 0.35))
    return max(smooth(0.12, 0.0, 0.5 - line) * 0.8, gap)


def skull_colour(s):
    c = BONE(0.62 - 0.14 * fbm(s.x * 5, s.y * 5, 41) + 0.15 * smooth(0.3, 0.0, s.d))
    c = mix(c, BONE(0.15), max(teeth(s.x, s.y), 1.0 if nose(s.x, s.y) else 0.0))
    # dark round the eye sockets
    for ex, ey in EYES:
        r = math.hypot((s.x - ex) / 0.19, (s.y - ey) / 0.16)
        c = mix(c, BONE(0.12), 0.8 * smooth(1.45, 1.0, r))
    return c


def skull_height(s):
    r = math.hypot((s.x - SKULL_X) / SKULL_R, (s.y - SKULL_Y - 0.1) / SKULL_R)
    h = 0.17 * math.sqrt(max(0.0, 1 - min(1.0, r) ** 2)) + 0.03
    for ex, ey in EYES:
        q = math.hypot((s.x - ex) / 0.19, (s.y - ey) / 0.16)
        h -= 0.07 * smooth(1.35, 0.8, q)
    if nose(s.x, s.y):
        h -= 0.05
    return h - 0.02 * teeth(s.x, s.y)


def eye_glow(s, t):
    q = min(math.hypot((s.x - ex) / 0.19, (s.y - ey) / 0.16) for ex, ey in EYES)
    return 0.55 + 0.35 * (1 - q) + 0.12 * math.sin(2 * math.pi * t)


def build():
    w = Weapon('reaper', grip=3.25)
    w.sheet('blade', in_blade, lambda s: 0.22 if across(s.x, s.y)[0] < 0.3 else 0.38, blade_colour,
            height=blade_height, relief=2.8, metal=0.75, gloss=0.75, spec=1.0, sheen=blade_sheen, coat=0.25)
    w.sheet('soul edge', in_soul, None, '#06402e', gloss=0.8, spec=0.8, glow=soul_glow, glow_colours=SOUL,
            glow_strength=1.5, bloom=0.45)
    w.sheet('soul runes', in_runes, None, '#04140f', glow=rune_glow, glow_colours=SOUL, glow_strength=0.8,
            bloom=0.3)
    from forge import shape
    for barb in BARBS:
        w.sheet('barb', shape(barb), 0.3, lambda s: VIOLET(0.45 + 0.35 * smooth(0.0, 0.12, s.d)),
                height=lambda s: 0.02 + 0.08 * smooth(0.0, 0.15, s.d), relief=2.5, metal=0.8, gloss=0.7, spec=0.9)
    # the guard
    w.rod('collar', 8.0, 5.85, 6.35, 0.6, lambda s: BONE(0.55 + 0.2 * math.sin(s.a * 6) ** 2), caps=(False, True),
          gloss=0.5, spec=0.6)
    w.sheet('ribs', in_ribs, 0.5, rib_colour, height=rib_height, relief=2.2, gloss=0.5, spec=0.6)
    w.sheet('knuckle', knuckle, 0.95, knuckle_colour,
            height=lambda s: 0.12 * smooth(0.0, 0.25, s.d) + 0.015 * fbm(s.x * 5, s.y * 5, 38),
            relief=2.2, gloss=0.5, spec=0.6)
    w.rod('guard collar', 8.0, Y_GRIP1, 5.2, 0.6, lambda s: VIOLET(0.5), caps=(True, True), metal=0.8, gloss=0.6,
          spec=0.8)
    # the grip and skull
    w.rod('grip', 8.0, Y_GRIP0, Y_GRIP1, 0.5, grip_colour, height=grip_height, relief=2.2, gloss=0.45, spec=0.5)
    w.sheet('skull', skull, 1.2, skull_colour, height=skull_height, relief=2.4, gloss=0.5, spec=0.6)
    w.sheet('eyes', eye, None, '#012a1e', glow=eye_glow, glow_colours=SOUL, glow_strength=1.4, bloom=0.6)
    return w
