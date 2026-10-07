"""Crush: a war axe with a hammer back. A great crescent blade of dark hammered steel with a
freshly ground, mirror-bright edge and a bronze-inlaid border; azure light breaks out of a
crystal core in the middle of the head and runs through the steel in glowing cracks. Behind
the haft a heavy square hammer with a bevelled striking face, cracked by the same light. Bronze
bands and rivets, a top spike, a long dark steel haft, a grip bound in dark blue leather with
bronze wire, and a bronze pommel.
"""
import math

from forge import Weapon, shape
from looks import gem, hammered, scratches, wrap
from paint import bezier, fbm, hexrgb, line_dist, line_pos, mix, noise, ramp, smooth

STEEL = ramp('#15181e', '#262b35', '#434b59', '#848ea0', '#e2e8f2')
BRONZE = ramp('#3a2108', '#7a4a1a', '#b77a36', '#e8b878', '#fff0d0')
AZURE = ramp('#00101f', '#00488a', '#1a9cff', '#82d6ff', '#ffffff')
LEATHER = ramp('#04070d', '#0b1424', '#16263d', '#243a58')
IRON = hexrgb('#262a32')

CORE_X, CORE_Y = 8.0, 12.25

EDGE = bezier((4.7, 15.6), (1.25, 14.55), (1.2, 9.55), (4.7, 8.55), 56)
EDGE_LENGTH = sum(math.hypot(EDGE[k + 1][0] - EDGE[k][0], EDGE[k + 1][1] - EDGE[k][1]) for k in range(len(EDGE) - 1))
TOP = bezier((7.45, 13.8), (6.3, 13.95), (5.3, 14.55), (4.7, 15.6), 24)
BOTTOM = bezier((4.7, 8.55), (5.3, 9.6), (6.3, 10.55), (7.45, 10.7), 24)
HEAD = EDGE + BOTTOM[1:] + [(8.1, 10.7), (8.1, 13.8)] + TOP[:-1]

NECK = [(8.4, 11.4), (9.75, 11.25), (9.75, 13.25), (8.4, 13.1)]
HAMMER = [(9.6, 10.85), (11.05, 10.85), (11.05, 13.65), (9.6, 13.65)]
FACE = [(10.95, 10.62), (11.5, 10.75), (11.5, 13.75), (10.95, 13.88)]

CRACKS = [
    [(7.35, 12.35), (6.6, 12.65), (5.85, 12.45), (5.1, 13.05), (4.25, 13.15), (3.35, 13.75)],
    [(7.35, 12.1), (6.6, 11.7), (5.75, 11.9), (4.95, 11.3), (4.05, 11.45), (3.15, 10.85)],
    [(5.85, 12.45), (5.25, 12.15), (4.5, 12.3), (3.55, 12.2), (2.85, 12.45)],
    [(5.1, 13.05), (4.95, 13.8), (5.15, 14.35)],
    [(4.95, 11.3), (5.2, 10.55), (5.0, 10.0)],
]
HAMMER_CRACKS = [
    [(8.65, 12.3), (9.35, 12.15), (10.0, 12.5), (10.55, 12.2), (11.05, 12.4)],
    [(10.0, 12.5), (10.25, 13.1)],
    [(10.55, 12.2), (10.4, 11.5)],
]


def _near(lines, x, y):
    """(distance to the nearest of the lines, how far along it: 0..1)."""
    best, where = 9.0, 0.0
    for line in lines:
        d, along = line_pos(line, x, y)
        if d < best:
            best, where = d, along
    return best, where


# ---- the head --------------------------------------------------------------------------------------------


def border(s):
    """The bronze-inlaid line that follows the blade's outline a little way in: 0..1."""
    de = line_dist(EDGE, s.x, s.y)
    inner = min(line_dist(TOP, s.x, s.y), line_dist(BOTTOM, s.x, s.y))
    return max(smooth(0.035, 0.0, abs(de - 0.9)) * smooth(7.0, 6.2, s.x),
               smooth(0.03, 0.0, abs(inner - 0.3)) * smooth(0.75, 0.95, de) * smooth(7.3, 6.9, s.x))


def head_height(s):
    de = line_dist(EDGE, s.x, s.y)
    h = 0.03 + 0.16 * smooth(0.0, 0.75, de)                         # the ground bevel
    h += 0.02 * fbm(s.x * 1.5, s.y * 1.5, 91) * smooth(0.6, 1.0, de)  # broad hammer facets
    h -= 0.03 * border(s)
    d, _ = _near(CRACKS, s.x, s.y)
    h -= 0.03 * smooth(0.14, 0.04, d)                               # the cracks split the steel
    h -= 0.006 * scratches(s.x, s.y, 92)
    return h - 0.02 * smooth(0.05, 0.0, s.d)


def head_colour(s):
    de, along = line_pos(EDGE, s.x, s.y)
    if de < 0.7:
        # the freshly ground edge: mirror-bright, with fine grind lines across it
        grind = 0.93 + 0.07 * noise(along * 160, de * 3, 93)
        c = STEEL(0.66 + 0.32 * smooth(0.7, 0.0, de))
        return tuple(v * grind for v in c)
    f = fbm(s.x * 1.5, s.y * 1.5, 91)
    base = mix(STEEL(0.28), hexrgb('#141820'), smooth(1.0, 4.0, de))
    base = mix(base, hexrgb('#1d2a40'), 0.3 * f)                    # a little blue temper colour
    c = tuple(v * (0.88 + 0.16 * f + 0.05 * noise(s.x * 2, s.y * 50, 94)) for v in base)
    c = mix(c, STEEL(0.55), 0.4 * smooth(1.0, 0.7, de))              # the bevel line catches the light
    c = mix(c, STEEL(0.72), 0.45 * scratches(s.x, s.y, 92))
    c = mix(c, BRONZE(0.62), 0.9 * border(s))
    d, _ = _near(CRACKS, s.x, s.y)
    return mix(c, hexrgb('#0a2a4a'), 0.7 * smooth(0.3, 0.05, d))       # lit blue round the cracks


def head_sheen(s):
    de = line_dist(EDGE, s.x, s.y)
    return 0.8 * math.exp(-((s.y - 13.2 + 0.35 * s.x) / 0.8) ** 2) * smooth(1.0, 0.3, de)


def in_cracks(lines, width):
    def mask(x, y):
        d, along = _near(lines, x, y)
        return d < width * (1.0 - 0.55 * along)
    xs = [p[0] for line in lines for p in line]
    ys = [p[1] for line in lines for p in line]
    mask.box = (min(xs) - width, min(ys) - width, max(xs) + width, max(ys) + width)
    return mask


def crack_glow(lines):
    def glow(s, t):
        d, along = _near(lines, s.x, s.y)
        surge = max(0.0, math.sin(2 * math.pi * (t * 1.2 - along * 0.9))) ** 2
        flicker = noise(along * 10, t * 8, 95)
        return 0.5 + 0.35 * surge + 0.15 * flicker - 0.25 * along
    return glow


def hammer_colour(s):
    c = hammered(s.x, s.y, IRON, 96, 0.3)
    c = mix(c, STEEL(0.62), smooth(0.12, 0.0, s.d))                   # worn bright edges
    c = mix(c, STEEL(0.7), 0.35 * scratches(s.x, s.y, 97, 6.0))
    d, _ = _near(HAMMER_CRACKS, s.x, s.y)
    return mix(c, hexrgb('#0a2a4a'), 0.7 * smooth(0.28, 0.05, d))


def hammer_height(s):
    d, _ = _near(HAMMER_CRACKS, s.x, s.y)
    return 0.1 * smooth(0.0, 0.2, s.d) + 0.02 * fbm(s.x * 3, s.y * 3, 96) - 0.03 * smooth(0.12, 0.03, d)


def face_colour(s):
    # the striking face: bright, battered steel
    c = STEEL(0.5 + 0.2 * fbm(s.x * 4, s.y * 4, 98))
    return mix(c, STEEL(0.8), smooth(0.1, 0.0, s.d))


def rivet(cx, cy, r=0.17):
    def mask(x, y):
        return math.hypot(x - cx, y - cy) < r
    mask.box = (cx - r, cy - r, cx + r, cy + r)
    return mask


RIVETS = [(6.75, 13.45), (6.75, 11.05), (10.3, 13.3), (10.3, 11.2)]


# ---- the haft ---------------------------------------------------------------------------------------------


def haft_colour(s):
    # dark steel with a long spiral groove
    p = (s.y / 1.4 + s.a / (2 * math.pi)) % 1.0
    groove = smooth(0.04, 0.0, abs(p - 0.5) - 0.03)
    c = STEEL(0.22 + 0.08 * noise(s.a * 4, s.y * 2, 99))
    return mix(c, hexrgb('#0d0f14'), 0.8 * groove)


def haft_height(s):
    p = (s.y / 1.4 + s.a / (2 * math.pi)) % 1.0
    return -0.02 * smooth(0.04, 0.0, abs(p - 0.5) - 0.03)


def band_colour(s):
    if s.cap:
        return BRONZE(0.55)
    return BRONZE(0.48 + 0.25 * smooth(0.08, 0.0, s.d) + 0.08 * math.sin(s.a * 8) ** 2)


def build():
    w = Weapon('crush', grip=2.9)
    w.sheet('head', shape(HEAD), 0.46, head_colour, height=head_height, relief=2.6, metal=0.9, gloss=0.7,
            spec=0.95, sheen=head_sheen)
    w.sheet('cracks', in_cracks(CRACKS, 0.075), None, '#04203d', glow=crack_glow(CRACKS), glow_colours=AZURE,
            glow_strength=1.6, bloom=0.7)
    # the hammer back
    w.sheet('neck', shape(NECK), 0.86, lambda s: hammered(s.x, s.y, IRON, 100, 0.3), relief=2.0,
            height=lambda s: 0.06 * smooth(0.0, 0.15, s.d), metal=0.8, gloss=0.5, spec=0.7)
    w.sheet('hammer', shape(HAMMER), 1.2, hammer_colour, height=hammer_height, relief=2.4, metal=0.85,
            gloss=0.55, spec=0.8)
    w.sheet('hammer cracks', in_cracks(HAMMER_CRACKS, 0.065), None, '#04203d', glow=crack_glow(HAMMER_CRACKS),
            glow_colours=AZURE, glow_strength=1.5, bloom=0.7)
    w.sheet('face', shape(FACE), 1.42, face_colour, height=lambda s: 0.08 * smooth(0.0, 0.18, s.d), relief=2.5,
            metal=0.9, gloss=0.65, spec=0.9)
    for cx, cy in RIVETS:
        w.sheet('rivet', rivet(cx, cy), 1.0 if cx > 9 else 0.58, lambda s: BRONZE(0.6),
                height=lambda s, cx=cx, cy=cy: 0.08 * math.sqrt(max(0.0, 1 - (math.hypot(s.x - cx, s.y - cy) / 0.18) ** 2)),
                relief=2.0, metal=1.0, gloss=0.7, spec=1.0)
    w.sheet('top spike', shape([(7.5, 14.3), (8.0, 15.95), (8.5, 14.3)]), 0.5,
            lambda s: STEEL(0.42 + 0.2 * smooth(0.0, 0.2, s.d)),
            height=lambda s: 0.03 + 0.12 * smooth(0.0, 0.25, s.d), relief=3.0, metal=0.9, gloss=0.7, spec=0.9)
    # the socket with its crystal core and bronze bands
    w.rod('socket', 8.0, 10.55, 14.35, 0.72, lambda s: hammered(s.u, s.y, IRON, 101, 0.25), caps=(False, True),
          metal=0.75, gloss=0.45, spec=0.7)
    for y0, y1 in ((10.4, 10.8), (14.1, 14.5)):
        w.rod('socket band', 8.0, y0, y1, 0.8, band_colour, caps=(True, True), metal=1.0, gloss=0.65, spec=0.9)
    w.sheet('bezel', rivet(CORE_X, CORE_Y, 0.66), 1.7, lambda s: BRONZE(0.5 + 0.35 * smooth(0.1, 0.0, s.d)),
            height=lambda s: 0.06 * smooth(0.0, 0.12, s.d), relief=2.0, metal=1.0, gloss=0.7, spec=1.0)
    mask, colour, height = gem(CORE_X, CORE_Y, 0.48, 0.48, AZURE, facets=8, table=0.5)
    w.sheet('core', mask, 1.9, colour, height=height, relief=2.5, gloss=0.95, spec=1.4, coat=0.4,
            glow=lambda s, t: 0.35 + 0.3 * (0.5 + 0.5 * math.sin(2 * math.pi * t)), glow_colours=AZURE,
            glow_strength=0.9, bloom=0.6)
    # the haft, grip and pommel
    w.rod('haft', 8.0, 4.75, 10.55, 0.43, haft_colour, height=haft_height, relief=2.0, metal=0.85, gloss=0.55,
          spec=0.7)
    for y0, y1 in ((7.3, 7.65), (9.55, 9.9)):
        w.rod('band', 8.0, y0, y1, 0.52, band_colour, caps=(True, True), metal=1.0, gloss=0.65, spec=0.9)
    w.rod('grip top', 8.0, 4.45, 4.8, 0.56, band_colour, caps=(True, True), metal=1.0, gloss=0.65, spec=0.9)
    w.rod('grip', 8.0, 1.25, 4.45, 0.5, lambda s: wrap(s, 0.42, 0.78, LEATHER, BRONZE(0.6))[0],
          height=lambda s: wrap(s, 0.42, 0.78, LEATHER, None)[1], relief=2.0, gloss=0.4, spec=0.5,
          metal=lambda s: 0.0 if wrap(s, 0.42, 0.78, LEATHER, None)[1] else 0.95)
    w.rod('pommel', 8.0, 0.5, 1.25, 0.64, lambda s: BRONZE(0.45 + 0.3 * smooth(0.1, 0.0, s.d)), caps=(True, True),
          metal=1.0, gloss=0.65, spec=0.9)
    mask, colour, height = gem(8.0, 0.87, 0.28, 0.28, AZURE, facets=8, table=0.5)
    w.sheet('pommel gem', mask, 1.42, colour, height=height, relief=2.0, gloss=0.95, spec=1.3,
            glow=lambda s, t: 0.25 + 0.2 * (0.5 + 0.5 * math.sin(2 * math.pi * (t + 0.5))), glow_colours=AZURE,
            glow_strength=0.6, bloom=0.4)
    w.sheet('pommel spike', shape([(7.6, 0.52), (8.0, 0.02), (8.4, 0.52)]), 0.46, lambda s: STEEL(0.45),
            height=lambda s: 0.03 + 0.12 * smooth(0.0, 0.25, s.d), relief=3.0, metal=0.9, gloss=0.7, spec=0.9)
    return w
