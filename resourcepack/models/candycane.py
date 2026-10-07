"""Candy Cane: a peppermint broadsword. A broad blade of hard candy, glossy white twisted with
bold red stripes and thin ones beside them, its edges turning to clear pink sugar glass that
glows; a crisp ridge runs up its middle. The guard is two candy canes curling down round the
hand, a pink rock-candy crystal set where they meet; a silver collar, a grip bound in red and
white ribbon, and a peppermint candy as the pommel.
"""
import math

from forge import Weapon
from looks import gem
from paint import clamp, hexrgb, mix, noise, ramp, smooth

Y_BLADE, Y_TIP = 6.5, 15.95
WHITE = hexrgb('#fff8f6')
RED = ramp('#5a0010', '#a3061f', '#e01434', '#ff4a66')
PINK = ramp('#3a0620', '#b0185e', '#ff4fa0', '#ffaad4', '#ffffff')
SUGAR = ramp('#7a0a3a', '#d43a82', '#ff8cc2', '#ffd0e6')
SILVER = ramp('#2a2d35', '#5f6672', '#a3abb8', '#e4e9f0', '#ffffff')
MINT = hexrgb('#3fcf8e')


# ---- the blade ------------------------------------------------------------------------------------------


def half(y):
    """Half the blade's width: broad, swelling a little a third of the way up, then a long point."""
    t = clamp((y - Y_BLADE) / (Y_TIP - Y_BLADE))
    w = 0.94 + 0.12 * math.sin(math.pi * min(1.0, t / 0.62))
    if t > 0.66:
        w *= ((1 - t) / 0.34) ** 0.9
    return w


def in_blade(x, y):
    return Y_BLADE <= y <= Y_TIP and abs(x - 8) <= half(y)


def edge_distance(x, y):
    return half(y) - abs(x - 8)


BEVEL = 0.26


def stripe(x, y):
    """The twist of the stripes: (on a bold red stripe 0..1, on a thin stripe 0..1). They run
    diagonally up the blade like the stripes round a candy cane."""
    p = (y * 0.62 + (x - 8) * 0.5) % 1.0
    bold = smooth(0.02, -0.01, abs(p - 0.3) - 0.165)
    thin = smooth(0.012, -0.004, abs(p - 0.62) - 0.03)
    return bold, thin


def blade_height(s):
    e = edge_distance(s.x, s.y)
    a = abs(s.x - 8)
    h = 0.04 + 0.15 * smooth(0.0, BEVEL + 0.05, e)               # the sugar-glass bevels
    h += 0.07 * smooth(0.5, 0.0, a)                               # a rounded ridge up the middle
    bold, thin = stripe(s.x, s.y)
    h += 0.008 * bold                                             # the stripes stand a hair proud
    return h - 0.02 * smooth(0.05, 0.0, s.d)


def blade_colour(s):
    x, y = s.x, s.y
    e = edge_distance(x, y)
    bold, thin = stripe(x, y)
    c = WHITE
    c = mix(c, RED(0.62 + 0.15 * noise(x * 3, y * 3, 81)), bold)
    c = mix(c, RED(0.55), thin)
    # milky white: a soft pink blush deep in the candy, brighter along the ridge
    c = mix(c, hexrgb('#ffd6e4'), 0.25 * (1 - bold) * smooth(0.0, 0.6, abs(x - 8)))
    c = mix(c, (1.0, 1.0, 1.0), 0.35 * math.exp(-((x - 8) / 0.06) ** 2))
    # the edges turn to clear pink sugar glass
    glass = smooth(BEVEL + 0.04, BEVEL - 0.04, e)
    c = mix(c, SUGAR(0.35 + 0.5 * smooth(BEVEL, 0.0, e)), glass)
    if noise(x * 60, y * 60, 82) > 0.93 and (bold < 0.5 or glass > 0.5):
        c = mix(c, (1.0, 1.0, 1.0), 0.7)                          # sparkling sugar crystals
    return c


def in_edge(x, y):
    return in_blade(x, y) and edge_distance(x, y) < 0.13


def edge_glow(s, t):
    e = edge_distance(s.x, s.y)
    run = max(0.0, math.sin(2 * math.pi * (t * 1.4 - s.y * 0.13))) ** 2
    return 0.45 + 0.3 * run + 0.25 * smooth(0.13, 0.0, e)


# ---- the guard: two candy canes ------------------------------------------------------------------------

TUBE = 0.23
HOOK_C, HOOK_R = (10.62, 5.42), 0.56


def _hook():
    """The centre line of the right-hand cane: out from the middle, over the top of the hook and
    down round it, curling back in."""
    pts = [(8.0, 5.98), (9.0, 5.98), (9.9, 5.98)]
    for k in range(25):
        a = math.radians(90 - k * 9.5)
        pts.append((HOOK_C[0] + HOOK_R * math.cos(a), HOOK_C[1] + HOOK_R * math.sin(a)))
    return pts


RIGHT = _hook()
LENGTH = sum(math.hypot(RIGHT[k + 1][0] - RIGHT[k][0], RIGHT[k + 1][1] - RIGHT[k][1]) for k in range(len(RIGHT) - 1))


def tube(x, y):
    """(signed distance across the cane, distance along it) for either cane (mirrored)."""
    px = 8 + abs(x - 8)
    best, where, side, run = 1e9, 0.0, 1.0, 0.0
    for k in range(len(RIGHT) - 1):
        (x1, y1), (x2, y2) = RIGHT[k], RIGHT[k + 1]
        dx, dy = x2 - x1, y2 - y1
        ln = math.hypot(dx, dy) or 1e-9
        t = max(0.0, min(1.0, ((px - x1) * dx + (y - y1) * dy) / (ln * ln)))
        d = math.hypot(px - x1 - t * dx, y - y1 - t * dy)
        if d < best:
            best, where = d, run + t * ln
            side = 1.0 if (dx * (y - y1) - dy * (px - x1)) >= 0 else -1.0
        run += ln
    return best * side, where


def in_canes(x, y):
    if not (4.7 < y < 6.3):
        return False
    d, along = tube(x, y)
    if along >= LENGTH - 1e-6:
        return math.hypot(8 + abs(x - 8) - RIGHT[-1][0], y - RIGHT[-1][1]) < TUBE    # a round end
    return abs(d) < TUBE


in_canes.box = (16 - HOOK_C[0] - HOOK_R - TUBE - 0.05, 4.7, HOOK_C[0] + HOOK_R + TUBE + 0.05, 6.3)


def cane_colour(s):
    d, along = tube(s.x, s.y)
    q = d / TUBE
    p = (along * 1.05 + q * 0.32) % 1.0
    bold = smooth(0.03, -0.01, abs(p - 0.3) - 0.14)
    thin = smooth(0.015, -0.005, abs(p - 0.62) - 0.035)
    c = mix(WHITE, RED(0.62), bold)
    c = mix(c, MINT, thin)                                    # a thin mint stripe for a little colour
    return mix(c, (1.0, 1.0, 1.0), 0.45 * math.exp(-((q - 0.35) / 0.18) ** 2))


def cane_height(s):
    d, _ = tube(s.x, s.y)
    q = clamp(abs(d) / TUBE)
    return 0.2 * math.sqrt(max(0.0, 1 - q * q))


# ---- the pommel: a peppermint ----------------------------------------------------------------------------

MINT_X, MINT_Y, MINT_R = 8.0, 0.9, 0.86


def peppermint(x, y):
    return math.hypot(x - MINT_X, y - MINT_Y) <= MINT_R


peppermint.box = (MINT_X - MINT_R, MINT_Y - MINT_R, MINT_X + MINT_R, MINT_Y + MINT_R)


def peppermint_colour(s):
    dx, dy = s.x - MINT_X, s.y - MINT_Y
    r = math.hypot(dx, dy) / MINT_R
    a = math.atan2(dy, dx)
    swirl = ((a + r * 1.6) / (2 * math.pi) * 8) % 1.0          # eight red swirls curving round
    red = smooth(0.06, 0.0, abs(swirl - 0.5) - 0.22) * smooth(0.12, 0.2, r) * smooth(0.97, 0.9, r)
    c = mix(WHITE, RED(0.66), red)
    c = mix(c, hexrgb('#ffe2ea'), 0.4 * smooth(0.85, 1.0, r))   # the clear rim
    if noise(s.x * 50, s.y * 50, 83) > 0.9:
        c = mix(c, (1.0, 1.0, 1.0), 0.6)
    return c


def peppermint_height(s):
    r = math.hypot(s.x - MINT_X, s.y - MINT_Y) / MINT_R
    return 0.16 * math.sqrt(max(0.0, 1 - r ** 2.4)) + 0.006 * (peppermint_colour(s)[1] < 0.5)


# ---- the grip ------------------------------------------------------------------------------------------


def ribbon(s):
    """Red and white ribbon wound round the grip, overlapping: (colour, height)."""
    p = (s.y / 0.5 + s.a / (2 * math.pi)) % 1.0
    red = p < 0.5
    edge = min(p % 0.5, 0.5 - p % 0.5) / 0.5
    c = RED(0.5 + 0.2 * smooth(0.0, 0.25, edge)) if red else mix(WHITE, hexrgb('#f0d8dc'), 0.3 * smooth(0.25, 0.0, edge))
    shine = 0.9 + 0.1 * noise(s.a * 10, s.y * 40, 84)
    return tuple(v * shine for v in c), 0.03 * smooth(0.0, 0.2, edge) + 0.02 * (p % 0.5) / 0.5


def build():
    w = Weapon('candycane', grip=3.15)
    w.sheet('blade', in_blade, lambda s: 0.24 if edge_distance(s.x, s.y) < BEVEL else 0.42, blade_colour,
            box=(6.8, Y_BLADE, 9.2, Y_TIP), height=blade_height, relief=2.6, metal=0.0, gloss=0.88, spec=1.2,
            coat=0.55)
    w.sheet('sugar edge', in_edge, None, '#9a1250', box=(6.8, Y_BLADE, 9.2, Y_TIP), gloss=0.9, spec=1.0,
            glow=edge_glow, glow_colours=PINK, glow_strength=1.2, bloom=0.45, coat=0.4)
    # the collar and the canes
    w.rod('collar', 8.0, 6.18, 6.72, 0.7, lambda s: SILVER(0.55 + 0.25 * math.sin(s.a * 4 + s.y * 10) ** 2),
          caps=(False, True), metal=1.0, gloss=0.75, spec=1.0)
    w.sheet('canes', in_canes, 0.46, cane_colour, height=cane_height, relief=2.4, gloss=0.88, spec=1.2,
            coat=0.5)
    mask, colour, height = gem(8.0, 5.98, 0.42, 0.42, PINK, facets=8, table=0.5)
    w.sheet('crystal', mask, 1.0, colour, height=height, relief=2.5, gloss=0.95, spec=1.4, coat=0.5,
            glow=lambda s, t: 0.25 + 0.2 * (0.5 + 0.5 * math.sin(2 * math.pi * t)), glow_colours=PINK,
            glow_strength=0.55, bloom=0.4)
    w.rod('guard collar', 8.0, 5.1, 5.7, 0.62, lambda s: SILVER(0.5 + 0.3 * smooth(0.08, 0.0, s.d)),
          caps=(True, True), metal=1.0, gloss=0.75, spec=1.0)
    # the grip and pommel
    w.rod('grip', 8.0, 1.62, 5.1, 0.5, lambda s: ribbon(s)[0], height=lambda s: ribbon(s)[1], relief=2.0,
          gloss=0.6, spec=0.7)
    w.rod('pommel collar', 8.0, 1.42, 1.66, 0.58, lambda s: SILVER(0.6), caps=(True, True), metal=1.0,
          gloss=0.75, spec=1.0)
    w.sheet('peppermint', peppermint, 0.62, peppermint_colour, height=peppermint_height, relief=2.2,
            gloss=0.9, spec=1.3, coat=0.6)
    return w
