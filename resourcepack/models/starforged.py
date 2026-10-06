"""Starforged: a double-bladed battle axe of deep navy star-steel. Each crescent blade is trimmed
in gold, has a frosted bevel and a cutting edge crackling with cyan lightning, a lightning bolt
and a scatter of stars set into it; a cut ice crystal pulses in a gold setting between them.
A round navy haft with gold rings, a navy grip wound with gold thread, an ice shard on top and
a gold pommel with an ice crystal.
"""
import math

from forge import Weapon, shape
from looks import gem, scratches, wrap
from paint import bezier, clamp, fbm, hexrgb, line_dist, line_pos, mix, noise, ramp, seg_dist, smooth

NAVY = ramp('#050817', '#0b1430', '#15224d', '#22346e', '#34509a', '#5a7cc4')
FROST = ramp('#3d5f9e', '#7ea4dc', '#c4dcf6', '#f2f8ff')
CYAN = ramp('#021a2c', '#0a5f8a', '#25c4ef', '#9af3ff', '#ffffff')
GOLD = ramp('#4a2c06', '#8f5d14', '#d29a33', '#f6d27a', '#fff6d0')
ICE = ramp('#06204f', '#1659a8', '#3fa8ea', '#a6ecff', '#ffffff')

Y_HEAD = 11.8
EDGE_R = bezier((12.35, 15.0), (14.25, 13.6), (14.25, 10.0), (12.35, 8.6), 40)
TOP_R = bezier((8.9, 12.55), (10.3, 13.1), (11.4, 14.0), (12.35, 15.0), 20)
BOTTOM_R = bezier((12.35, 8.6), (11.4, 9.6), (10.3, 10.5), (8.9, 11.05), 20)
RIGHT = [(8.0, 12.55)] + TOP_R + EDGE_R[1:] + BOTTOM_R[1:] + [(8.0, 11.05)]
LEFT = [(16 - x, y) for x, y in RIGHT]
BOLT_R = [(9.3, 11.85), (10.35, 12.35), (10.85, 11.45), (12.25, 12.05), (13.35, 11.6)]
STARS = [(11.3, 13.4), (12.6, 10.05), (10.45, 10.8), (13.0, 13.25), (11.9, 11.0)]


def mirror(pts):
    return [(16 - x, y) for x, y in pts]


def right(x, y):
    """Folds the left blade onto the right one (they are mirror images)."""
    return (x if x >= 8 else 16 - x), y


def blade_height(s):
    x, y = right(s.x, s.y)
    de = line_dist(EDGE_R, x, y)
    h = 0.03 + 0.15 * smooth(0.0, 0.7, de)
    return h - 0.03 * smooth(0.06, 0.0, s.d)


def blade_colour(s):
    x, y = right(s.x, s.y)
    de = line_dist(EDGE_R, x, y)
    rim = min(line_dist(TOP_R, x, y), line_dist(BOTTOM_R, x, y))
    nebula = fbm(x * 1.3, y * 1.3, 41)
    c = NAVY(0.3 + 0.45 * nebula + (0.08 if s.x < 8 else 0.0))
    c = mix(c, hexrgb('#4b2a8a'), 0.25 * smooth(0.55, 0.8, fbm(x * 2.1, y * 2.1, 43)))   # violet dust
    c = mix(c, FROST(0.25 + 0.6 * smooth(0.75, 0.15, de)), smooth(0.8, 0.6, de))      # frosted bevel
    c = mix(c, GOLD(0.62 + 0.25 * smooth(0.12, 0.0, rim)), smooth(0.19, 0.15, rim))    # gold trim
    # a fine gold inlay line inside the trim, beaded with little gold dots
    inlay = smooth(0.03, 0.012, abs(rim - 0.31)) * smooth(0.9, 0.7, de)
    _, along = line_pos(TOP_R if line_dist(TOP_R, x, y) < line_dist(BOTTOM_R, x, y) else BOTTOM_R, x, y)
    bead = smooth(0.07, 0.03, math.hypot((along * 40) % 1.0 - 0.5, (rim - 0.31) * 8)) * smooth(0.9, 0.7, de)
    c = mix(c, GOLD(0.7), max(inlay, bead))
    # the constellation: faint lines joining the stars
    for a, b in zip(STARS, STARS[1:]):
        c = mix(c, hexrgb('#5f9be8'), 0.55 * smooth(0.022, 0.006, seg_dist(x, y, a, b)))
    return mix(c, FROST(0.6), 0.3 * scratches(x, y, 47, 6.0))


def blade_metal(s):
    x, y = right(s.x, s.y)
    rim = min(line_dist(TOP_R, x, y), line_dist(BOTTOM_R, x, y))
    return 1.0 if rim < 0.17 else 0.75


def edge_glow(s, t):
    x, y = right(s.x, s.y)
    de = line_dist(EDGE_R, x, y)
    flicker = noise(y * 2.5, t * 10 + (s.x < 8) * 5, 43)
    return 0.45 + 0.45 * flicker + 0.2 * smooth(0.15, 0.0, de)


def bolt_glow(s, t):
    x, y = right(s.x, s.y)
    d, along = line_pos(BOLT_R, x, y)
    return 0.3 + 0.7 * max(0.0, math.sin(2 * math.pi * (t * 1.5 - along * 0.9))) ** 3 - d * 4


def star_mask(x, y):
    x, y = right(x, y)
    for sx, sy in STARS:
        dx, dy = abs(x - sx), abs(y - sy)
        if dx * dy < 0.0035 and dx + dy < 0.2 or math.hypot(dx, dy) < 0.05:
            return True                                  # a little four-pointed star
    return False


def build():
    w = Weapon('starforged', grip=2.6)
    blades = (shape(RIGHT), shape(LEFT))
    in_blades = lambda x, y: blades[0](x, y) or blades[1](x, y)
    in_blades.box = (1.6, 8.5, 14.4, 15.1)
    w.sheet('blades', in_blades, 0.36,
            blade_colour, height=blade_height, relief=2.5, metal=blade_metal, gloss=0.7, spec=0.9,
            sheen=lambda s: 0.45 * math.exp(-((s.y - 13.2 + 0.25 * abs(s.x - 8)) / 0.9) ** 2))
    w.sheet('edge', lambda x, y: in_blades(x, y) and line_dist(EDGE_R, *right(x, y)) < 0.16, None, '#0a3a5a',
            box=in_blades.box, glow=edge_glow, glow_colours=CYAN, glow_strength=1.4)
    w.sheet('lightning', lambda x, y: line_dist(BOLT_R, *right(x, y)) < 0.065, None, '#0a3a5a',
            box=(2.5, 10.9, 13.5, 12.8), glow=bolt_glow, glow_colours=CYAN, glow_strength=1.4)
    w.sheet('stars', star_mask, None, '#0a3a5a', box=(2.8, 9.8, 13.2, 13.6),
            glow=lambda s, t: 0.5 + 0.5 * math.sin(2 * math.pi * (t + s.x * 0.37 + s.y * 0.21)),
            glow_colours=CYAN, glow_strength=1.2)
    # the core: a gold setting and a cut ice crystal
    setting = lambda x, y: abs(x - 8) + abs(y - Y_HEAD) < 1.32
    setting.box = (6.6, Y_HEAD - 1.4, 9.4, Y_HEAD + 1.4)
    setting.solid = (4, 8.0, Y_HEAD, 1.32, 0.0)
    w.sheet('setting', setting, 1.05,
            lambda s: mix(GOLD(0.55 + 0.25 * smooth(0.1, 0.0, s.d)), GOLD(0.15), smooth(0.03, 0.0, abs(s.d - 0.2))),
            height=lambda s: 0.12 * smooth(0.0, 0.3, s.d), relief=2.5, metal=1.0, gloss=0.7, spec=1.0)
    mask, colour, height = gem(8.0, Y_HEAD, 0.74, 0.74, ICE, facets=4)
    w.sheet('ice', mask, 1.35, colour, height=height, relief=2.5, gloss=0.95, spec=1.4,
            glow=lambda s, t: 0.3 + 0.3 * (0.5 + 0.5 * math.sin(2 * math.pi * t)) * smooth(0.5, 0.0, s.d) + 0.15,
            glow_colours=ICE, glow_strength=0.8)
    # haft, rings, grip, pommel, top shard
    w.rod('haft', 8.0, 4.2, 14.85, 0.4, lambda s: NAVY(0.45 + 0.15 * fbm(s.u * 3, s.y * 0.5, 47)),
          metal=0.6, gloss=0.75, spec=0.9)
    for y0, y1 in ((4.1, 4.42), (8.25, 8.57), (14.55, 14.87)):
        w.rod('ring', 8.0, y0, y1, 0.5, lambda s: GOLD(0.6), caps=(True, True), metal=1.0, gloss=0.7, spec=1.0)
    w.rod('grip', 8.0, 1.0, 4.1, 0.5, lambda s: wrap(s, 0.42, 0.78, NAVY, GOLD(0.6))[0],
          height=lambda s: wrap(s, 0.42, 0.78, NAVY, None)[1], relief=2.0, gloss=0.45, spec=0.5,
          metal=lambda s: 0.2 if wrap(s, 0.42, 0.78, NAVY, None)[1] else 1.0)
    w.rod('pommel', 8.0, 0.6, 1.02, 0.6, lambda s: GOLD(0.6), caps=(True, True), metal=1.0, gloss=0.7, spec=1.0)
    mask, colour, height = gem(8.0, 0.36, 0.38, 0.38, ICE, facets=4)
    w.sheet('pommel crystal', mask, 0.8, colour, height=height, relief=2.5, gloss=0.95, spec=1.4,
            glow=lambda s, t: 0.35, glow_colours=ICE, glow_strength=0.6)
    w.sheet('top shard', shape([(7.58, 14.8), (8.0, 16.0), (8.42, 14.8)]), 0.5,
            lambda s: ICE(0.5 + 0.4 * smooth(0.0, 0.15, s.d)), height=lambda s: 0.03 + 0.12 * smooth(0.0, 0.2, s.d),
            relief=3.0, gloss=0.9, spec=1.3, emissive=True)
    return w
