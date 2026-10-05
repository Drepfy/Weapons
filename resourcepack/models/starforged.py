"""Starforged: a double-bladed axe of deep navy metal. Thin glowing cyan edges, a lightning
inlay and a few stars in each blade, an ice crystal standing out of a gold setting between them,
gold rings on the haft, an ice shard on top and an ice crystal under the pommel.
"""
import math

from mesh import Model
from paint import bevel, crystal, fbm, gold, hexrgb, leather, metal, mix, ramp, smooth
from shape import bezier, inside, line_dist, mirror_x, worst_fit

NAVY_LIGHT, NAVY, NAVY_DARK = hexrgb('#4a6bb0'), hexrgb('#24386b'), hexrgb('#111a38')
FROST = hexrgb('#b9d4f5')
CYAN, CYAN_HOT = hexrgb('#4fe9ff'), hexrgb('#e2fdff')

# the right blade (the left one is its mirror image): a fan from the core out to a curved edge
EDGE = bezier((12.9, 13.9), (14.6, 12.6), (14.6, 9.4), (12.9, 8.1), 24)
RIGHT = [(8.9, 10.4), (10.6, 9.6)] + [(12.9, 8.1)] + EDGE[::-1][1:-1] + [(12.9, 13.9), (10.6, 12.4), (8.9, 11.6)]
LEFT = mirror_x(RIGHT)
BOLT = [(9.3, 11.0), (10.4, 11.5), (10.9, 10.7), (12.2, 11.4), (13.4, 10.9)]
STARS = [(11.3, 12.7), (12.4, 9.4), (10.2, 10.1), (13.1, 12.1)]


def blade_colour(x, y):
    xr = x if x >= 8 else 16 - x                           # paint both blades from the right one
    yr = y if x >= 8 else y + 0.25                         # (the left one a little different)
    d = line_dist(EDGE, xr, yr)
    n = (fbm(x * 2, y * 2, 81) - 0.5) * 14
    if d < 0.22:
        c = mix(CYAN_HOT, CYAN, d / 0.22)                  # the glowing edge
    elif d < 0.6:
        c = mix(FROST, NAVY_LIGHT, smooth((d - 0.22) / 0.38))
    else:
        c = ramp([(0, NAVY_LIGHT), (0.35, NAVY), (1, NAVY_DARK)], (d - 0.6) / 4.0)
    c = tuple(v + n for v in c)
    k = line_dist(BOLT, xr, yr)
    if k < 0.16:
        c = mix(CYAN_HOT, CYAN, k / 0.16)                  # the lightning inlay
    elif k < 0.4:
        c = mix(c, CYAN, (0.4 - k) / 0.24 * 0.45)
    for sx, sy in STARS:
        r = math.hypot(xr - sx, yr - sy)
        if r < 0.22:
            c = mix(c, CYAN_HOT, 1 - r / 0.22)
    return c


def blade(face, s, t, p):
    x, y, z = p
    if not (inside(RIGHT, x, y) or inside(LEFT, x, y)):
        return None
    return blade_colour(x, y)


def plate(face, s, t, p):
    x, y, z = p
    if face.dir in ('south', 'north'):
        return blade_colour(x, y)
    return bevel(face, s, t, NAVY_LIGHT if face.dir in ('up', 'west') else NAVY_DARK)


def build():
    assert max(worst_fit(RIGHT), worst_fit(LEFT)) <= 11.3
    m = Model()
    navy = metal(NAVY_DARK, NAVY, NAVY_LIGHT, seed=14)
    gilt = gold()
    ice = crystal(hexrgb('#123d8a'), hexrgb('#4fd8ff'), hexrgb('#f2feff'))
    # the two blades: thin cards for their shape, thicker plates towards the core
    m.card(1.3, 8.0, 14.7, 14.0, 0.24, blade, tag='blades')
    m.box((8.9, 10.1, 7.72), (10.4, 11.9, 8.28), plate, tag='blades')
    m.box((5.6, 10.1, 7.72), (7.1, 11.9, 8.28), plate, tag='blades')
    # gold setting and the ice crystal standing out of it
    m.box((6.95, 9.95, 7.1), (9.05, 12.05, 8.9), gilt, tag='core')
    m.box((7.15, 10.15, 6.85), (8.85, 11.85, 9.15), ice, rot=('z', 45.0, (8, 11.0, 8)), tag='core')
    # haft with gold rings, the ice shard on top
    m.rod(3.4, 15.9, 0.46, navy, tag='haft')
    for y in (3.4, 7.7, 13.9):
        m.rod(y, y + 0.4, 0.6, gilt, tag='ring')
    m.box((7.7, 15.9, 7.7), (8.3, 16.9, 8.3), ice, tag='shard')
    m.box((7.58, 16.4, 7.58), (8.42, 17.24, 8.42), ice, rot=('z', 45.0, (8, 16.82, 8)), tag='shard')
    # grip, pommel, crystal
    m.rod(-2.0, 3.4, 0.58, leather(hexrgb('#151c35'), hexrgb('#36426a'), hexrgb('#7d9cd3'), turns=2.4), tag='grip')
    m.rod(-2.6, -2.0, 0.68, gilt, tag='pommel')
    m.box((7.6, -3.1, 7.6), (8.4, -2.3, 8.4), ice, rot=('z', 45.0, (8, -2.7, 8)), tag='pommel')
    return m
