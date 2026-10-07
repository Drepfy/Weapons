"""Wyrmfang: a dragon greatsword. A broad blade of polished jade, clouded and veined like the real
stone, with pale bone-white bevels, rows of dragon teeth along both edges above the guard, and
scales carved into its base; a venom-green vein glows up its middle and pulses towards the
point. Gold dragon wings spread out as the guard round an amber dragon's eye with a slit pupil;
a dark green leather grip bound in gold wire, and a gold claw clutching a jade orb as the pommel.
"""
import math

from forge import Weapon, shape
from looks import gem, scratches, wrap
from paint import clamp, fbm, hexrgb, mix, noise, ramp, smooth

Y_BLADE, Y_TIP = 6.1, 15.9
JADE = ramp('#04200f', '#0a3d22', '#136a3d', '#23a35e', '#7fe0a8')
BONE = ramp('#3d4f43', '#8fae98', '#d6eedd', '#ffffff')
VENOM = ramp('#03200c', '#0d6b2e', '#2fe06e', '#b9ffcf', '#ffffff')
GOLD = ramp('#4a2e08', '#9a6a18', '#dcae44', '#fff0b8')
AMBER = ramp('#2a0e00', '#7a3600', '#e08a10', '#ffd060', '#fff6d8')
LEATHER = ramp('#06100a', '#0f2016', '#1b3424', '#2b4a36')


def half(y):
    """Half the blade's width at height y: a little broader a third of the way up, then sweeping
    in to the point."""
    t = clamp((y - Y_BLADE) / (Y_TIP - Y_BLADE))
    w = 1.08 + 0.16 * math.sin(math.pi * min(1.0, t / 0.7))
    if t > 0.62:
        w *= ((1 - t) / 0.38) ** 0.85
    return w


def teeth(y):
    """How far the dragon teeth along the edges bite in at height y: saw teeth pointing up the
    blade, only on its lower part."""
    if not Y_BLADE + 0.25 < y < Y_BLADE + 2.6:
        return 0.0
    f = ((y - Y_BLADE - 0.25) / 0.46) % 1.0
    return 0.24 * f * smooth(Y_BLADE + 2.6, Y_BLADE + 2.0, y)


def in_blade(x, y):
    return Y_BLADE <= y <= Y_TIP and abs(x - 8) <= half(y) - teeth(y)


def edge_distance(x, y):
    return half(y) - teeth(y) - abs(x - 8)


def vein_half(y):
    return 0.15 * smooth(Y_TIP - 1.2, Y_TIP - 3.2, y) + 0.03


def scales(x, y):
    """Dragon scales carved into the base of the blade, in overlapping rows: 0..1 how deep."""
    if not Y_BLADE + 0.15 < y < Y_BLADE + 2.4:
        return 0.0
    a = abs(x - 8)
    if not 0.24 < a < 0.82:
        return 0.0
    row = math.floor((y - Y_BLADE) / 0.3)
    cx = 0.22 * (0.5 if row % 2 else 0.0)
    lx = ((a + cx) % 0.22) - 0.11
    ly = (y - Y_BLADE) - (row + 0.15) * 0.3
    ring = abs(math.hypot(lx, ly) - 0.13)
    fade = smooth(Y_BLADE + 2.4, Y_BLADE + 1.6, y)
    return smooth(0.038, 0.0, ring) * (ly > -0.02) * fade


def blade_height(s):
    e = edge_distance(s.x, s.y)
    h = 0.03 + 0.16 * smooth(0.0, 0.34, e)              # bevelled up from both edges
    h -= 0.05 * smooth(vein_half(s.y) + 0.08, 0.0, abs(s.x - 8))   # a groove down the middle
    h -= 0.022 * scales(s.x, s.y)
    return h - 0.03 * smooth(0.05, 0.0, s.d)


def blade_colour(s):
    e = edge_distance(s.x, s.y)
    # clouded jade: drifting light and dark green, fine darker veins and a few pale inclusions
    cloud = fbm(s.x * 0.8 + fbm(s.x * 1.5, s.y * 0.7, 61) * 1.6, s.y * 0.45, 62)
    c = JADE(0.25 + 0.6 * cloud)
    vein = abs(fbm(s.x * 2.2 + 4, s.y * 1.1, 63) - 0.5)
    c = mix(c, JADE(0.05), 0.55 * smooth(0.03, 0.0, vein))
    c = mix(c, hexrgb('#c8f5d8'), 0.35 * smooth(0.72, 0.9, fbm(s.x * 3, s.y * 2.2, 64)))
    # the pale bone bevels, and the honed edge
    bevel = smooth(0.34, 0.26, e)
    c = mix(c, BONE(0.25 + 0.7 * smooth(0.28, 0.0, e) + (0.08 if s.x < 8 else -0.1)), bevel)
    c = mix(c, (1.0, 1.0, 1.0), 0.6 * smooth(0.05, 0.0, e))
    c = mix(c, hexrgb('#0b2a18'), 0.5 * scratches(s.x, s.y, seed=65, density=4.0) * (1 - bevel))
    # the vein's light on the groove's walls, and the carved scales picked out in gold
    c = mix(c, hexrgb('#3dff86'), 0.4 * smooth(vein_half(s.y) + 0.12, vein_half(s.y), abs(s.x - 8)))
    return mix(c, GOLD(0.7), 0.75 * scales(s.x, s.y))


def blade_sheen(s):
    e = edge_distance(s.x, s.y)
    v = 0.7 * math.exp(-((s.y - 12.2 + 0.7 * (s.x - 8)) / 0.9) ** 2) + 0.35 * math.exp(-((s.y - 8.6) / 0.5) ** 2)
    return v * (0.6 + 0.4 * smooth(0.3, 0.1, e)) - 0.1


def in_vein(x, y):
    return Y_BLADE + 0.2 <= y <= Y_TIP - 1.15 and abs(x - 8) < vein_half(y) and in_blade(x, y)


def vein_glow(s, t):
    flow = max(0.0, math.sin(2 * math.pi * (t * 2 - s.y * 0.35))) ** 2
    core = 1 - abs(s.x - 8) / max(vein_half(s.y), 1e-6) * 0.6
    drip = 0.25 * smooth(0.82, 0.95, noise(s.x * 8, s.y * 2.5 - t * 10, 66))
    return core * (0.6 + 0.4 * flow) + drip


# The guard: a pair of gold dragon wings, three bony fingers each with the skin scalloped
# between them, rising to a claw at each tip.
_RIGHT = [(8.0, 5.0), (8.8, 5.08), (9.45, 4.86), (9.85, 5.28), (10.55, 5.04), (10.9, 5.5), (11.55, 5.42),
          (11.85, 5.95), (12.3, 6.3), (12.5, 7.1), (12.15, 7.75), (11.8, 7.05), (11.35, 6.6), (10.45, 6.3),
          (9.45, 6.18), (8.65, 6.28), (8.0, 6.38)]
WINGS = _RIGHT + [(16 - x, y) for x, y in reversed(_RIGHT[1:-1])]
BONES = [((8.7, 5.75), (12.15, 7.6)), ((8.7, 5.7), (11.6, 5.5)), ((8.7, 5.65), (10.5, 5.08))]


def wing_bones(s):
    """The wing's bony fingers, raised: 0..1."""
    a = abs(s.x - 8)
    x = 8 + a
    best = 9.0
    for (x0, y0), (x1, y1) in BONES:
        dx, dy = x1 - x0, y1 - y0
        t = clamp(((x - x0) * dx + (s.y - y0) * dy) / (dx * dx + dy * dy))
        best = min(best, math.hypot(x - x0 - t * dx, s.y - y0 - t * dy))
    return smooth(0.09, 0.02, best) * smooth(0.55, 0.8, a)


def guard_colour(s):
    c = GOLD(0.32 + 0.22 * fbm(s.x * 2.5, s.y * 5, 71))
    # thin wing skin between the bones: darker, with a green sheen of the blade
    c = mix(c, mix(GOLD(0.2), hexrgb('#1f6b3f'), 0.35), 0.45 * (1 - wing_bones(s)) * smooth(0.06, 0.2, s.d))
    c = mix(c, GOLD(0.9), smooth(0.08, 0.02, s.d))                 # polished rim
    return mix(c, GOLD(0.82), wing_bones(s))


def guard_height(s):
    return 0.1 * smooth(0.0, 0.18, s.d) + 0.05 * wing_bones(s) + 0.01 * fbm(s.x * 4, s.y * 8, 72)


EYE_X, EYE_Y, EYE_R = 8.0, 5.68, 0.6


def eye_colour(s):
    """The amber dragon's eye: fiery rings round a black slit pupil, a bright glint."""
    dx, dy = (s.x - EYE_X) / EYE_R, (s.y - EYE_Y) / EYE_R
    r = math.hypot(dx, dy)
    c = AMBER(clamp(0.85 - 0.6 * r + 0.12 * math.sin(math.atan2(dy, dx) * 14 + r * 9)))
    slit = abs(dx) - 0.11 * math.sqrt(max(0.0, 1 - (dy / 0.78) ** 2))
    c = mix(c, hexrgb('#0a0300'), smooth(0.04, -0.02, slit))
    return mix(c, (1.0, 1.0, 1.0), 0.8 * smooth(0.16, 0.0, math.hypot(dx + 0.32, dy - 0.38)))


def eye_glow(s, t):
    dx, dy = (s.x - EYE_X) / EYE_R, (s.y - EYE_Y) / EYE_R
    slit = abs(dx) - 0.11 * math.sqrt(max(0.0, 1 - (dy / 0.78) ** 2))
    return (0.3 + 0.3 * (0.5 + 0.5 * math.sin(2 * math.pi * t))) * smooth(0.0, 0.08, slit)


ORB_Y, ORB_R = 0.6, 0.5
_TALON = [(7.42, 1.12), (7.2, 0.95), (7.28, 0.55), (7.5, 0.18), (7.68, 0.08), (7.62, 0.3), (7.48, 0.6),
          (7.6, 0.98)]


_LEFT_TALON = shape(_TALON)
_RIGHT_TALON = shape([(16 - px, py) for px, py in _TALON])


def talons(x, y):
    """Three gold talons curling round the orb (two at the sides, one over the front)."""
    front = abs(x - 8) < 0.1 + 0.05 * (y - 0.6) and 0.28 < y < 1.1
    return front or _LEFT_TALON(x, y) or _RIGHT_TALON(x, y)


talons.box = (7.1, 0.05, 8.9, 1.15)


def build():
    w = Weapon('wyrmfang', grip=3.1)
    w.sheet('blade', in_blade, lambda s: 0.26 if edge_distance(s.x, s.y) < 0.34 else 0.44, blade_colour,
            box=(6.6, Y_BLADE, 9.4, Y_TIP), height=blade_height, relief=2.6, metal=0.15, gloss=0.85, spec=1.1,
            sheen=blade_sheen)
    w.sheet('venom vein', in_vein, None, '#0a3a1c', box=(7.7, Y_BLADE, 8.3, Y_TIP - 1.1),
            gloss=0.7, glow=vein_glow, glow_colours=VENOM, glow_strength=1.6)
    w.sheet('wings', shape(WINGS), 0.7, guard_colour, height=guard_height, relief=2.2, metal=0.9, gloss=0.65,
            spec=0.9)
    mask, _, height = gem(EYE_X, EYE_Y, EYE_R, EYE_R, AMBER, facets=8, table=0.6)
    w.sheet('dragon eye', mask, 1.0, eye_colour, height=height, relief=2.0, gloss=0.95, spec=1.3,
            glow=eye_glow, glow_colours=AMBER, glow_strength=0.7)
    w.rod('collar', 8.0, 4.78, 5.05, 0.66, lambda s: GOLD(0.6 + 0.2 * math.sin(s.a * 6) ** 2), caps=(True, True),
          metal=1.0, gloss=0.65, spec=0.9)
    w.rod('grip', 8.0, 1.42, 4.78, 0.5,
          lambda s: wrap(s, 0.4, 0.7, LEATHER, GOLD(0.62))[0],
          height=lambda s: wrap(s, 0.4, 0.7, LEATHER, None)[1], relief=2.0, gloss=0.4, spec=0.5,
          metal=lambda s: 0.0 if wrap(s, 0.4, 0.7, LEATHER, None)[1] else 0.95)
    w.rod('pommel collar', 8.0, 1.12, 1.42, 0.62, lambda s: GOLD(0.55), caps=(True, True), metal=1.0, gloss=0.65,
          spec=0.9)
    mask, colour, height = gem(8.0, ORB_Y, ORB_R, ORB_R, JADE, facets=8, table=0.3, depth=0.3)
    w.sheet('jade orb', mask, 0.95, colour, height=height, relief=2.2, gloss=0.95, spec=1.3,
            glow=lambda s, t: 0.18 + 0.15 * (0.5 + 0.5 * math.sin(2 * math.pi * (t + 0.5))),
            glow_colours=VENOM, glow_strength=0.4)
    w.sheet('talons', talons, 1.05, lambda s: GOLD(0.45 + 0.35 * smooth(0.12, 0.0, s.d)), relief=2.0,
            height=lambda s: 0.06 * smooth(0.0, 0.1, s.d), metal=1.0, gloss=0.7, spec=1.0)
    return w
