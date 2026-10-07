"""Katana: an obsidian blade with a molten crimson edge. A long curved blade of black volcanic
glass, polished to a mirror: its cutting edge burns crimson behind a rolling temper line, a
crisp silver ridge runs its length, and a thin crimson vein glows in the groove along its back.
A gold collar, a round black iron guard inlaid with gold waves between gold washers, a handle
of black ray skin bound in crimson silk with gold ornaments, a black and gold pommel, and a
crimson silk tassel hanging from a gold ring.
"""
import math

from forge import Weapon
from looks import scratches
from paint import clamp, fbm, hexrgb, mix, noise, ramp, smooth

Y_BLADE, Y_KISSAKI, Y_TIP = 6.42, 14.3, 15.96
OBSIDIAN = ramp('#040406', '#0b0c10', '#16171e', '#262833', '#43465a')
CRIMSON = ramp('#160003', '#5a000f', '#b3001f', '#ff1d3c', '#ff8696', '#fff1f3')
GOLD = ramp('#5a3408', '#a87420', '#e6b84a', '#fff1bf')
IRON = hexrgb('#16161b')
SILK = ramp('#2a0006', '#6e0414', '#b3122a', '#e8344c', '#ff8a98')
RAY = ramp('#050507', '#101015', '#1d1d25', '#2e2e3a')
SILVER = ramp('#3a3e4a', '#8a92a2', '#d8dee8', '#ffffff')


# ---- the blade --------------------------------------------------------------------------------------


def bow(y):
    """How far the blade curves towards its back (right) at height y."""
    return 0.5 * max(0.0, (y - Y_BLADE) / (Y_TIP - Y_BLADE)) ** 2


def half(y):
    return 0.66 - 0.1 * clamp((y - Y_BLADE) / (Y_KISSAKI - Y_BLADE))


def spine(y):
    x = 8 + bow(y) + half(y)
    if y > Y_KISSAKI:
        x -= 0.26 * ((y - Y_KISSAKI) / (Y_TIP - Y_KISSAKI)) ** 2.4
    return x


def edge(y):
    x = 8 + bow(y) - half(y)
    if y > Y_KISSAKI:
        q = (y - Y_KISSAKI) / (Y_TIP - Y_KISSAKI)
        x += (spine(y) - x) * (1 - math.sqrt(max(0.0, 1 - q ** 1.25)))    # the edge sweeps up to the point
    return x


def across(x, y):
    """0 at the cutting edge, 1 at the back."""
    return (x - edge(y)) / max(spine(y) - edge(y), 1e-6)


def in_blade(x, y):
    return Y_BLADE <= y <= Y_TIP and edge(y) <= x <= spine(y)


def hamon(y):
    """How far the molten edge reaches in (a fraction of the way from the edge to the back): big
    rolling waves with small ripples on them, like flames licking into the glass. Narrower at
    the point."""
    h = 0.2 + 0.065 * abs(math.sin(y * 2.25)) + 0.025 * math.sin(y * 6.9 + 1.0) + 0.01 * math.sin(y * 18.0)
    if y > Y_KISSAKI:
        h = h + (0.13 - h) * smooth(Y_KISSAKI, Y_TIP, y)
    return h


def ridge(y):
    """The ridge line (shinogi): straight along the blade, rising to the back at the point."""
    if y <= Y_KISSAKI:
        return 0.66
    return 0.66 + 0.3 * (y - Y_KISSAKI) / (Y_TIP - Y_KISSAKI)


GROOVE = (0.74, 0.86)
GROOVE_TOP = 12.7


def groove(x, y):
    """0..1 across the groove along the back (outside it: < 0 or > 1), or None above its end."""
    top = GROOVE_TOP + 0.14 * math.cos((across(x, y) - 0.8) * 18)
    if not Y_BLADE + 0.35 <= y <= top:
        return None
    return (across(x, y) - GROOVE[0]) / (GROOVE[1] - GROOVE[0])


def blade_height(s):
    u = clamp(across(s.x, s.y))
    r = ridge(s.y)
    if u < r:
        h = 0.03 + 0.18 * (u / r) ** 0.85                 # the long bevel up from the edge
    else:
        h = 0.21 - 0.08 * (u - r) / (1 - r)
    h -= 0.02 * smooth(0.0, 0.05, 0.05 - s.d)              # rounded off at the very outline
    g = groove(s.x, s.y)
    if g is not None and 0 <= g <= 1:
        h -= 0.055 * math.sqrt(max(0.0, 1 - (2 * g - 1) ** 2))
    if s.y > Y_KISSAKI - 0.02:
        h += 0.02 * smooth(0.04, 0.0, abs(s.y - Y_KISSAKI))   # the crisp line across the point
    return h


def blade_colour(s):
    x, y = s.x, s.y
    u = across(x, y)
    hm = hamon(y)
    r = ridge(y)
    cloud = fbm(x * 1.6 + 3, y * 0.5, 42)
    if u < hm:
        # under the molten edge (mostly hidden by the glow): deep red glass
        return mix(hexrgb('#3a0008'), hexrgb('#7a0a1c'), smooth(hm, 0.0, u))
    if u < r:
        # polished black glass, faintly smoky, with a crimson mist along the temper line
        c = OBSIDIAN(0.32 + 0.22 * cloud)
        c = mix(c, hexrgb('#5e0716'), 0.45 * math.exp(-(u - hm) / 0.04))
        c = mix(c, OBSIDIAN(0.7), 0.3 * smooth(r - 0.12, r, u))
    elif y > Y_KISSAKI:
        c = OBSIDIAN(0.5 + 0.2 * cloud)                    # the back of the point
    else:
        # the flat above the ridge: darker, satin; a crisp silver ridge line
        c = OBSIDIAN(0.2 + 0.15 * cloud)
        g = groove(x, y)
        if g is not None and -0.12 < g < 1.12:
            c = mix(c, hexrgb('#2a0309'), smooth(-0.12, 0.05, g) * smooth(1.12, 0.95, g))
    c = mix(c, SILVER(0.85), smooth(0.022, 0.0, abs(u - r - 0.01)) * (y < Y_KISSAKI + 0.4))
    # tiny red flecks deep in the glass, and fine scratches
    if u > hm + 0.08 and noise(x * 58, y * 58, 43) > 0.93:
        c = mix(c, hexrgb('#ff3048'), 0.4)
    c = mix(c, OBSIDIAN(0.85), 0.35 * scratches(x, y, 44, 4.5))
    if y > Y_KISSAKI:
        c = mix(c, hexrgb('#a01020'), 0.35 * smooth(0.55, 0.2, u))
    return c


def blade_sheen(s):
    """Long bright streaks across the polished glass."""
    u = across(s.x, s.y)
    y = s.y
    v = 1.0 * math.exp(-((y - 11.2 + 1.5 * u) / 0.7) ** 2) + 0.5 * math.exp(-((y - 7.9 + 1.2 * u) / 0.4) ** 2)
    v += 0.6 * math.exp(-((y - 14.9 + 0.8 * u) / 0.32) ** 2)
    if u > ridge(y):
        v *= 0.45
    return v - 0.08


def in_edge(x, y):
    return in_blade(x, y) and across(x, y) < hamon(y)


def edge_glow(s, t):
    """The molten edge: white-hot at the very edge, cooling to deep red where it licks into the
    glass; waves of heat run up the blade."""
    u = across(s.x, s.y)
    q = u / max(hamon(s.y), 1e-6)
    pulse = max(0.0, math.sin(2 * math.pi * (t - s.y * 0.1))) ** 3
    v = 0.95 - 0.6 * q ** 0.8
    v += 0.2 * pulse * (1 - 0.6 * q)
    v += 0.06 * noise(s.y * 7, t * 9, 45)
    return v


def in_vein(x, y):
    if not in_blade(x, y):
        return False
    g = groove(x, y)
    return g is not None and abs(g - 0.5) < 0.17 and y < GROOVE_TOP - 0.2


def vein_glow(s, t):
    flow = max(0.0, math.sin(2 * math.pi * (t * 1.5 - s.y * 0.25))) ** 2
    return 0.35 + 0.35 * flow


# ---- the mounts ----------------------------------------------------------------------------------


def habaki_colour(s):
    if s.cap:
        return GOLD(0.6)
    # a gold collar with fine diagonal file marks
    marks = 0.5 + 0.5 * math.sin((s.y * 9 + s.a * 2.5) * 6)
    return GOLD(0.5 + 0.22 * marks ** 3 + 0.18 * smooth(0.08, 0.0, s.d))


def tsuba_colour(s):
    if s.cap == 0:
        # the rim: gold, with notches all round
        notch = 0.5 + 0.5 * math.cos(s.a * 24)
        return GOLD(0.5 + 0.25 * notch ** 4)
    rho = 1.7 - s.d
    if s.d < 0.13:
        return GOLD(0.62 + 0.28 * smooth(0.13, 0.0, s.d))      # the gold rim
    if rho < 0.86:
        return GOLD(0.55) if rho > 0.76 else IRON
    hammered = 0.75 + 0.5 * fbm(s.x * 5, s.z * 5, 46)
    c = tuple(v * hammered for v in mix(IRON, hexrgb('#2d1416'), 0.45 * fbm(s.x * 2, s.z * 2, 47)))
    return mix(c, GOLD(0.66), tsuba_waves(s))


def tsuba_waves(s):
    """Rolling waves inlaid in gold round the guard: 0..1."""
    rho = 1.7 - s.d
    if not 0.88 < rho < 1.55:
        return 0.0
    wave = 1.2 + 0.16 * math.sin(8 * s.a)
    curl = 1.2 + 0.16 * math.sin(8 * s.a + 1.2) - 0.12
    return max(smooth(0.035, 0.0, abs(rho - wave)), 0.7 * smooth(0.025, 0.0, abs(rho - curl)))


def tsuba_height(s):
    if s.cap == 0:
        return 0.01 * (0.5 + 0.5 * math.cos(s.a * 24)) ** 4
    rho = 1.7 - s.d
    h = 0.03 * smooth(0.17, 0.1, s.d) + 0.015 * fbm(s.x * 5, s.z * 5, 46)
    h += 0.02 * smooth(0.9, 0.82, rho)
    return h + 0.012 * tsuba_waves(s)


def cord_pattern(s):
    """The silk cord wound in diamonds round the handle: (on a cord, how far across the cord:
    0 middle .. 1 its edge)."""
    k = 1.0
    turn = s.a / (2 * math.pi)
    p1 = (s.y * k + turn) % 1.0
    p2 = (s.y * k - turn) % 1.0
    w = 0.3
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


def menuki(s):
    """The gold ornament under the cord on each side of the handle: 0..1 how far inside it."""
    a = math.atan2(math.sin(s.a), math.cos(s.a))
    side = min(abs(a), abs(abs(a) - math.pi))
    q = ((s.y - 3.55) / 0.55) ** 2 + (side / 0.55) ** 2
    return smooth(1.0, 0.72, q)


def handle_colour(s):
    on, d = cord_pattern(s)
    m = menuki(s)
    if m > 0 and not on:
        swirl = 0.5 + 0.5 * math.sin(s.y * 22 + s.a * 9)
        return GOLD(0.45 + 0.4 * swirl * m)
    if on:
        weave = 0.84 + 0.16 * math.sin((s.y * 1.0 + s.a / 6.28) * 120)
        return tuple(v * weave for v in SILK(0.5 + 0.35 * (1 - d)))
    # black ray skin: tight little nodules
    nod = noise(s.u * 30, s.y * 30, 48)
    return RAY(0.3 + 0.55 * nod - 0.25 * smooth(1.0, 1.6, d))


def handle_height(s):
    on, d = cord_pattern(s)
    if on:
        return 0.055 * math.sqrt(max(0.0, 1 - d * d))
    if menuki(s) > 0:
        return 0.04 * menuki(s)
    return 0.014 * noise(s.u * 30, s.y * 30, 48)


def kashira_colour(s):
    if s.cap:
        rho = 0.66 - s.d
        return GOLD(0.62) if s.d < 0.1 or rho < 0.18 else IRON
    band = s.y < Y_KASHIRA + 0.13 or s.y > Y_FUCHI_TOP - 0.13
    return GOLD(0.58 + 0.2 * smooth(0.06, 0.0, s.d)) if band else mix(IRON, GOLD(0.5), 0.12 * noise(s.a * 4, s.y * 9, 49))


Y_KASHIRA = 1.42
Y_FUCHI_TOP = 1.98


# ---- the tassel ------------------------------------------------------------------------------------------


RING_X, RING_Y, RING_R = 8.0, 1.2, 0.24


def ring(x, y):
    d = math.hypot(x - RING_X, y - RING_Y)
    return RING_R - 0.085 < d < RING_R + 0.02


ring.box = (RING_X - RING_R - 0.03, RING_Y - RING_R - 0.03, RING_X + RING_R + 0.03, RING_Y + RING_R + 0.03)

KNOT_Y = 0.78


def knot(x, y):
    return ((x - 8) / 0.24) ** 2 + ((y - KNOT_Y) / 0.2) ** 2 <= 1


knot.box = (7.74, KNOT_Y - 0.22, 8.26, KNOT_Y + 0.22)


def strand_half(y):
    """Half the tassel's width at height y: narrow under the knot, flaring out to the ends."""
    t = clamp((KNOT_Y - 0.1 - y) / (KNOT_Y - 0.12))
    return 0.15 + 0.2 * t ** 0.8


def tassel(x, y):
    if not 0.02 <= y <= KNOT_Y - 0.05:
        return False
    # the strands end raggedly
    end = 0.02 + 0.07 * abs(math.sin(x * 37))
    return y >= end and abs(x - 8 - 0.04 * (KNOT_Y - y)) <= strand_half(y)


tassel.box = (7.5, 0.0, 8.55, KNOT_Y)


def tassel_colour(s):
    strand = 0.5 + 0.5 * math.sin((s.x - 8) * 70 + noise(s.x * 30, s.y * 3, 50) * 3)
    c = SILK(0.35 + 0.35 * strand + 0.15 * smooth(0.1, 0.0, s.d))
    return mix(c, GOLD(0.7), smooth(0.05, 0.0, abs(s.y - (KNOT_Y - 0.14))))   # a gold band under the knot


def cord(x, y):
    """The silk cord from the ring down to the knot."""
    return abs(x - 8.0) < 0.07 and KNOT_Y < y < RING_Y - RING_R + 0.05


cord.box = (7.9, KNOT_Y, 8.1, RING_Y)


def build():
    w = Weapon('katana', grip=3.5)
    w.sheet('blade', in_blade, lambda s: 0.2 if across(s.x, s.y) < 0.3 else 0.34, blade_colour,
            box=(7.3, Y_BLADE, 9.4, Y_TIP), height=blade_height, relief=3.0, metal=0.2, gloss=0.9,
            spec=1.4, sheen=blade_sheen, coat=0.5)
    w.sheet('molten edge', in_edge, None, '#3a0008', box=(7.3, Y_BLADE, 9.4, Y_TIP),
            gloss=0.8, spec=0.8, glow=edge_glow, glow_colours=CRIMSON, glow_strength=1.45, bloom=0.3)
    w.sheet('groove vein', in_vein, None, '#2a0309', box=(8.0, Y_BLADE, 9.4, GROOVE_TOP),
            glow=vein_glow, glow_colours=CRIMSON, glow_strength=0.9, bloom=0.25)
    # gold collar, washers and guard
    w.rod('habaki', 8.0, 5.86, 6.55, 0.76, habaki_colour, caps=(False, True), metal=1.0, gloss=0.65, spec=0.9)
    w.rod('upper washer', 8.0, 5.72, 5.86, 0.98, lambda s: GOLD(0.62), caps=(True, True), metal=1.0, gloss=0.7,
          spec=0.9)
    w.rod('guard', 8.0, 5.34, 5.72, 1.7, tsuba_colour, sides=16, caps=(True, True), height=tsuba_height,
          relief=2.0, metal=0.75, gloss=0.6, spec=0.8)
    w.rod('lower washer', 8.0, 5.2, 5.34, 0.98, lambda s: GOLD(0.62), caps=(True, True), metal=1.0, gloss=0.7,
          spec=0.9)
    w.rod('fuchi', 8.0, 4.88, 5.2, 0.67, lambda s: GOLD(0.52 + 0.2 * smooth(0.06, 0.0, s.d)) if s.d < 0.1
          else IRON, caps=(True, False), metal=0.85, gloss=0.6, spec=0.8)
    # the handle and pommel
    w.rod('handle', 8.0, Y_FUCHI_TOP, 4.88, 0.6, handle_colour, height=handle_height, relief=2.5,
          gloss=0.5, spec=0.55)
    w.rod('pommel', 8.0, Y_KASHIRA, Y_FUCHI_TOP, 0.66, kashira_colour, caps=(True, False), metal=0.8,
          gloss=0.6, spec=0.8)
    # the tassel
    w.sheet('ring', ring, 0.2, lambda s: GOLD(0.55 + 0.3 * smooth(0.05, 0.0, s.d)),
            height=lambda s: 0.04 * smooth(0.0, 0.05, s.d), relief=2.0, metal=1.0, gloss=0.7, spec=1.0)
    w.sheet('cord', cord, 0.14, lambda s: SILK(0.55), gloss=0.5, spec=0.5)
    w.sheet('knot', knot, 0.36, lambda s: SILK(0.45 + 0.3 * (0.5 + 0.5 * math.sin((s.x + s.y) * 30))),
            height=lambda s: 0.08 * smooth(0.0, 0.15, s.d), relief=2.0, gloss=0.55, spec=0.6)
    w.sheet('tassel', tassel, 0.26, tassel_colour, height=lambda s: 0.03 * smooth(0.0, 0.1, s.d)
            + 0.01 * math.sin((s.x - 8) * 70), relief=2.0, gloss=0.55, spec=0.6)
    return w
