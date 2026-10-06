"""Gravebreaker: a bearded battle axe. A broad head of dark forged steel with a polished,
freshly ground cutting edge; a raised plate of hammered iron held by rivets and split by a crack
that smoulders like embers; a back spike and a top spike; a round iron socket on a long dark
walnut haft with iron bands, a leather-wrapped grip and a spiked iron pommel.
"""
import math

from forge import Weapon, shape
from looks import hammered, runes, scratches, wood, wrap
from paint import bezier, fbm, hexrgb, line_dist, line_pos, mix, noise, ramp, smooth

STEEL = ramp('#2a2e36', '#4c525e', '#7c8492', '#b9c0cc', '#eef2f6')
IRON = hexrgb('#3a3d44')
EMBER = ramp('#200400', '#7a1d04', '#e0500c', '#ffad42', '#fff4d0')
WALNUT = ramp('#1c0f07', '#34200f', '#4f3219', '#6e4a28', '#8d6337')
LEATHER = ramp('#140c08', '#2a1b12', '#45301f', '#634631')

EDGE = bezier((3.25, 15.35), (1.55, 13.9), (1.45, 10.2), (3.0, 8.55), 40)
EDGE_LENGTH = sum(math.hypot(EDGE[i + 1][0] - EDGE[i][0], EDGE[i + 1][1] - EDGE[i][1]) for i in range(len(EDGE) - 1))
BEARD = bezier((3.0, 8.55), (4.4, 9.0), (6.0, 9.6), (7.35, 10.75), 20)
TOP = bezier((7.35, 14.35), (6.0, 14.35), (4.6, 14.6), (3.25, 15.35), 20)
HEAD = EDGE + BEARD[1:] + [(8.4, 10.75), (8.4, 14.35)] + TOP[:-1]
FORGE = [(4.2, 10.4), (5.6, 10.25), (7.35, 10.75), (7.35, 14.35), (6.0, 14.3), (4.6, 14.5), (4.05, 12.4)]
CRACK = [(4.55, 10.55), (5.15, 11.2), (4.85, 11.9), (5.45, 12.5), (5.3, 13.1), (5.85, 13.65), (5.75, 14.2)]
SPIKE = [(8.5, 13.35), (11.2, 12.3), (8.5, 11.55)]
TOP_SPIKE = [(7.5, 14.5), (8.0, 16.0), (8.5, 14.5)]
RIVETS = [(6.6, 13.65), (6.6, 11.3), (4.85, 13.95), (4.75, 11.0)]


def forged(s, seed=5):
    """Forged steel: broad, shallow hammer facets (not grit), a fine brushed grain, and a little
    blue-black temper colour."""
    facets = fbm(s.x * 1.4, s.y * 1.4, seed)
    grain = noise(s.x * 2.0 + s.y * 0.3, s.y * 60, seed + 3)
    return facets, grain


def head_runes(s):
    """A line of runes engraved along the head, following the bevel a little way in."""
    de, along = line_pos(EDGE, s.x, s.y)
    if not 0.12 < along < 0.9:
        return 0.0
    return runes(along * EDGE_LENGTH, (de - 0.98) / 0.2, cell=0.4, width=0.05, seed=19)


def head_height(s):
    de = line_dist(EDGE, s.x, s.y)
    h = 0.03 + 0.15 * smooth(0.0, 0.6, de)          # the ground bevel of the cutting edge
    facets, _ = forged(s)
    h += 0.018 * facets * smooth(0.5, 0.9, de)       # shallow hammer facets on the flat
    h -= 0.03 * head_runes(s)                        # the engraving is cut into the steel
    h -= 0.006 * scratches(s.x, s.y, 23)
    return h - 0.02 * smooth(0.05, 0.0, s.d)


def head_colour(s):
    de, along = line_pos(EDGE, s.x, s.y)
    if de < 0.62:
        # freshly ground edge: mirror bright, with fine grind lines running across it
        grind = 0.94 + 0.06 * noise(along * 140, de * 3, 11)
        c = STEEL(0.7 + 0.28 * smooth(0.62, 0.0, de))
        return tuple(v * grind for v in c)
    facets, grain = forged(s)
    # dark blued steel, a little lighter towards the edge, with the brushed grain and facets
    base = mix(STEEL(0.36), hexrgb('#1f2430'), smooth(0.8, 3.6, de))
    base = mix(base, hexrgb('#2c3550'), 0.25 * facets)
    shade = 0.9 + 0.12 * facets + 0.05 * grain
    c = tuple(v * shade for v in base)
    c = mix(c, STEEL(0.55), 0.35 * smooth(0.9, 0.62, de))     # the bevel line catches the light
    c = mix(c, STEEL(0.75), 0.45 * scratches(s.x, s.y, 23))    # bright scratches from use
    rune = head_runes(s)
    return mix(c, hexrgb('#120d0c'), 0.85 * rune)              # the runes, dark in their grooves


def head_sheen(s):
    de = line_dist(EDGE, s.x, s.y)
    return (0.7 * math.exp(-((s.y - 13.4 + 0.4 * s.x) / 0.8) ** 2)) * smooth(0.9, 0.3, de)


def plate_colour(s):
    facets, grain = forged(s, 8)
    c = tuple(v * (0.88 + 0.16 * facets + 0.04 * grain) for v in hexrgb('#24262d'))
    c = mix(c, hexrgb('#8a909c'), smooth(0.1, 0.0, s.d))                 # worn bright rim
    c = mix(c, hexrgb('#0e0d10'), 0.8 * smooth(0.03, 0.0, abs(s.d - 0.2)))   # an engraved border inside it
    c = mix(c, hexrgb('#6a6f7a'), 0.35 * scratches(s.x, s.y, 29, 6.0))
    glow = smooth(0.35, 0.0, line_dist(CRACK, s.x, s.y))
    return mix(c, hexrgb('#5a1a08'), 0.6 * glow)                          # scorched round the crack


def ember_glow(s, t):
    d, along = line_pos(CRACK, s.x, s.y)
    flicker = noise(along * 9, t * 8, 37)
    pulse = 0.5 + 0.5 * math.sin(2 * math.pi * (t + along))
    return 0.35 + 0.35 * pulse + 0.3 * flicker - d * 3


def rivet(cx, cy):
    def mask(x, y):
        return math.hypot(x - cx, y - cy) < 0.2
    mask.box = (cx - 0.2, cy - 0.2, cx + 0.2, cy + 0.2)
    return mask


def spike_height(axis):
    def h(s):
        return 0.03 + 0.12 * smooth(0.0, 0.25, s.d)
    return h


def build():
    w = Weapon('gravebreaker', grip=2.6)
    head = shape(HEAD)
    w.sheet('head', head, 0.42, head_colour,
            height=head_height, relief=2.5, metal=0.9, gloss=0.7, spec=0.9, sheen=head_sheen)
    w.sheet('plate', shape(FORGE), 0.74, plate_colour,
            height=lambda s: 0.08 * smooth(0.0, 0.15, s.d) + 0.015 * forged(s, 8)[0]
            - 0.02 * smooth(0.03, 0.0, abs(s.d - 0.2)),
            relief=2.5, metal=0.7, gloss=0.45, spec=0.6)
    w.sheet('crack', lambda x, y: line_dist(CRACK, x, y) < 0.085, None, '#2a0800', box=(4.4, 10.4, 6.0, 14.35),
            glow=ember_glow, glow_colours=EMBER, glow_strength=1.5)
    for cx, cy in RIVETS:
        w.sheet('rivet', rivet(cx, cy), 0.9, lambda s: STEEL(0.5),
                height=lambda s, cx=cx, cy=cy: 0.1 * math.sqrt(max(0.0, 1 - (math.hypot(s.x - cx, s.y - cy) / 0.21) ** 2)),
                relief=2.0, metal=0.9, gloss=0.7, spec=1.0)
    w.sheet('back spike', shape(SPIKE), 0.44, lambda s: STEEL(0.42 + 0.15 * smooth(0.0, 0.2, s.d)),
            height=spike_height('x'), relief=3.0, metal=0.85, gloss=0.65, spec=0.9)
    w.sheet('top spike', shape(TOP_SPIKE), 0.44, lambda s: STEEL(0.42 + 0.15 * smooth(0.0, 0.2, s.d)),
            height=spike_height('y'), relief=3.0, metal=0.85, gloss=0.65, spec=0.9)
    # socket, haft, bands, grip, pommel
    w.rod('socket', 8.0, 10.6, 14.55, 0.68, lambda s: hammered(s.u, s.y, IRON, 9, 0.22), caps=(False, True),
          metal=0.7, gloss=0.4, spec=0.6)
    for y0, y1 in ((10.45, 10.8), (14.3, 14.65)):
        w.rod('socket band', 8.0, y0, y1, 0.75, lambda s: STEEL(0.5), caps=(True, True), metal=0.9, gloss=0.6,
              spec=0.9)
    w.rod('haft', 8.0, 4.3, 10.6, 0.44, lambda s: wood(s, WALNUT), gloss=0.35, spec=0.35)
    for y0, y1 in ((4.25, 4.6), (8.6, 8.95)):
        w.rod('band', 8.0, y0, y1, 0.53, lambda s: hammered(s.u, s.y, IRON, 12, 0.3), caps=(True, True),
              metal=0.8, gloss=0.5, spec=0.8)
    w.rod('grip', 8.0, 0.98, 4.25, 0.52, lambda s: wrap(s, 0.5, 0.86, LEATHER, LEATHER(0.05))[0],
          height=lambda s: wrap(s, 0.5, 0.86, LEATHER, None)[1], relief=2.0, gloss=0.3, spec=0.35)
    w.rod('pommel', 8.0, 0.45, 0.98, 0.62, lambda s: hammered(s.u, s.y + s.z, IRON, 13, 0.3),
          caps=(True, True), metal=0.8, gloss=0.5, spec=0.8)
    w.sheet('pommel spike', shape([(7.55, 0.47), (8.0, 0.0), (8.45, 0.47)]), 0.44,
            lambda s: STEEL(0.45), height=spike_height('y'), relief=3.0, metal=0.85, gloss=0.65, spec=0.9)
    return w
