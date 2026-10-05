"""Gravebreaker: a bearded battle axe. A broad head that goes from a thin polished edge to a
thick forged-iron centre with rivets and a glowing crack, a back spike and a top spike, on a dark
wooden haft with iron bands, a leather grip and a heavy spiked pommel.
"""
import math

from mesh import Model
from paint import bevel, fbm, hexrgb, leather, metal, mix, ramp, scale, smooth, wood
from shape import bezier, inside, line_dist, worst_fit

EDGE_LIGHT, EDGE_MID = hexrgb('#f4f7fa'), hexrgb('#cdd5df')
STEEL, STEEL_DARK = hexrgb('#a3adba'), hexrgb('#6c7684')
IRON, IRON_DARK = hexrgb('#4c515c'), hexrgb('#262931')
EMBER, EMBER_HOT, EMBER_DARK = hexrgb('#ff7a1f'), hexrgb('#ffd27a'), hexrgb('#8f2a08')

# the head, seen from the front: the cutting edge bulges out to the left, the beard hangs down
EDGE = bezier((3.0, 14.0), (0.5, 12.4), (0.6, 7.6), (2.4, 5.5), 24)
BEARD = bezier((2.4, 5.5), (4.4, 7.4), (6.0, 8.6), (7.3, 10.2), 12)
HEAD = EDGE + BEARD[1:] + [(7.3, 14.9), (5.4, 14.1)]
CRACK = [(4.6, 9.9), (5.1, 10.6), (4.9, 11.2), (5.5, 11.9), (5.4, 12.5), (6.0, 13.1)]


def head_colour(x, y):
    d = line_dist(EDGE, x, y)
    n = (fbm(x * 2.2, y * 2.2, 71) - 0.5)
    if d < 0.35:
        c = mix(EDGE_LIGHT, EDGE_MID, d / 0.35)
    elif d < 1.2:
        c = mix(EDGE_MID, STEEL, smooth((d - 0.35) / 0.85))
        c = mix(c, EDGE_LIGHT, max(0.0, 1 - abs(d - 1.15) / 0.06) * 0.5)   # the bevel line
    elif d < 2.9:
        c = mix(STEEL, STEEL_DARK, smooth((d - 1.2) / 1.7))
    else:
        c = scale(mix(IRON, IRON_DARK, smooth((d - 2.9) / 2.5)), 0.85 + 0.35 * fbm(x * 3.5, y * 3.5, 73))
    c = tuple(v + n * 18 for v in c)
    k = line_dist(CRACK, x, y)
    if k < 0.22:                                        # a glowing crack in the forged iron
        q = k / 0.22
        c = ramp([(0, EMBER_HOT), (0.45, EMBER), (1, EMBER_DARK)], q)
    elif k < 0.5:
        c = mix(c, EMBER_DARK, (0.5 - k) / 0.28 * 0.6)
    return c


def head(face, s, t, p):
    x, y, z = p
    if not inside(HEAD, x, y):
        return None
    return head_colour(x, y)


def cheek(face, s, t, p):
    x, y, z = p
    if face.dir in ('south', 'north'):
        return head_colour(x, y)
    return bevel(face, s, t, STEEL if face.dir in ('up', 'west') else IRON)


def rivet(face, s, t, p):
    r = math.hypot(s - 0.35, t - 0.35)
    return mix(hexrgb('#f2f4f8'), hexrgb('#5b616d'), min(1.0, r * 1.6))


def build():
    assert worst_fit(HEAD) <= 11.3
    m = Model()
    iron = metal(IRON_DARK, IRON, hexrgb('#a9b1bd'), seed=10)
    steel = metal(hexrgb('#606a78'), hexrgb('#b2bcc8'), hexrgb('#f2f5f9'), seed=12)
    # head: a thin card for the whole shape, thicker plates towards the haft
    m.card(0.4, 5.4, 7.4, 15.0, 0.24, head, tag='head')
    m.box((3.9, 8.6, 7.7), (7.3, 13.7, 8.3), cheek, tag='head')
    m.box((5.5, 9.5, 7.55), (7.3, 13.5, 8.45), cheek, tag='head')
    for (x, y) in ((6.3, 12.6), (6.3, 10.3)):
        for z0, z1 in ((8.45, 8.62), (7.38, 7.55)):
            m.box((x - 0.22, y - 0.22, z0), (x + 0.22, y + 0.22, z1), rivet, tag='rivet')
    # socket round the haft, back spike, top spike
    m.box((7.2, 10.1, 7.25), (8.8, 15.2, 8.75), iron, tag='socket')
    m.box((8.8, 12.05, 7.62), (10.4, 13.15, 8.38), steel, tag='spike')
    m.box((10.15, 12.22, 7.7), (10.91, 12.98, 8.3), steel, rot=('z', 45.0, (10.53, 12.6, 8)), tag='spike')
    m.box((7.6, 15.2, 7.6), (8.4, 16.4, 8.4), steel, tag='spike')
    m.box((7.68, 16.12, 7.68), (8.32, 16.76, 8.32), steel, rot=('z', 45.0, (8, 16.44, 8)), tag='spike')
    # haft, bands, grip, pommel
    m.rod(3.6, 10.1, 0.48, wood(hexrgb('#2a180c'), hexrgb('#5a3820'), hexrgb('#8a5c35')), tag='haft')
    m.rod(4.1, 4.55, 0.56, iron, tag='band')
    m.rod(8.6, 9.05, 0.56, iron, tag='band')
    m.rod(-2.05, 3.6, 0.6, leather(hexrgb('#1d1714'), hexrgb('#4a3c34'), hexrgb('#8a7564'), turns=2.6), tag='grip')
    m.rod(-2.75, -2.05, 0.74, iron, tag='pommel')
    m.box((7.62, -3.18, 7.62), (8.38, -2.42, 8.38), steel, rot=('z', 45.0, (8, -2.8, 8)), tag='pommel')
    return m
