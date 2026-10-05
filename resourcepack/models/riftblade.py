"""Riftblade: a broad sword of dark violet steel split down the middle by a glowing rift, which
opens into a forked point. The rift is sunk between the two raised halves of the blade and lights
their inner walls. A crescent guard with a violet crystal, a leather grip bound with violet wire,
and a crystal hanging from the pommel.
"""
import math

from mesh import Model
from paint import bevel, crystal, fbm, hexrgb, leather, metal, mix, ramp, smooth
from shape import inside, worst_fit

EDGE, EDGE_DARK = hexrgb('#cfc8ee'), hexrgb('#8a82b3')
STEEL, STEEL_DARK = hexrgb('#4a4266'), hexrgb('#231e33')
RIFT, RIFT_DEEP, RIFT_HOT = hexrgb('#b55cff'), hexrgb('#5d18b0'), hexrgb('#f6dcff')
SPARK = hexrgb('#8ff0ff')

Y0, Y_FORK = 5.0, 15.2
# outline: the left prong is the long one, the right one hooks off shorter; the rift opens between
OUTLINE = [(6.75, Y0), (9.25, Y0), (9.25, 16.3), (8.95, 17.6), (8.62, 16.9), (8.3, Y_FORK + 0.9),
           (8.0, Y_FORK), (7.7, Y_FORK + 0.9), (7.42, 18.6), (6.75, 17.3)]


def rift_half(y):
    """Half the rift's width: a narrow channel, opening into the fork."""
    return 0.24 if y < Y_FORK else 0.24 + (y - Y_FORK) * 0.32


def blade_colour(x, y):
    d = abs(x - 8)
    w = rift_half(y)
    n = (fbm(x * 2.5, y * 3, 61) - 0.5) * 16
    if d < w:
        q = d / w                                       # the rift: white-hot core, violet walls
        c = ramp([(0, RIFT_HOT), (0.35, RIFT), (1, RIFT_DEEP)], q)
        sparks = fbm(x * 6, y * 1.6, 67)
        if sparks > 0.72:
            c = mix(c, SPARK, (sparks - 0.72) * 3.5)
        return c
    edge = min(x - 6.75, 9.25 - x)                      # distance to the blade's outer edge
    if edge < 0.28:
        c = mix(EDGE, EDGE_DARK, edge / 0.28)           # sharpened bevel, catching the light
    else:
        glow = max(0.0, 1 - (d - w) / 0.45)            # rift light spilling onto the steel
        c = mix(STEEL, STEEL_DARK, smooth((d - w) / 1.0))
        c = mix(c, RIFT, glow * 0.45)
    return tuple(v + n for v in c)


def blade(face, s, t, p):
    x, y, z = p
    if not inside(OUTLINE, x, y):
        return None
    return blade_colour(x, y)


def half(face, s, t, p):
    """A raised half of the blade: its inner wall glows with the rift."""
    x, y, z = p
    if face.dir in ('south', 'north'):
        return blade_colour(x, y)
    if (face.dir == 'east' and x < 8) or (face.dir == 'west' and x > 8):
        return mix(RIFT, RIFT_HOT, 0.3 + 0.3 * math.sin(y * 3))
    return bevel(face, s, t, EDGE_DARK if face.dir in ('up', 'west') else STEEL)


def build():
    assert worst_fit(OUTLINE) <= 11.3
    m = Model()
    dark = metal(hexrgb('#1b1626'), hexrgb('#3e3557'), hexrgb('#9d95c4'), seed=8)
    guard = metal(hexrgb('#3b3453'), hexrgb('#8a82b3'), hexrgb('#e4defa'), seed=9)
    gem = crystal(hexrgb('#3b0a73'), hexrgb('#a54bff'), hexrgb('#f3d2ff'))
    m.card(6.75, Y0, 9.25, 18.6, 0.24, blade, tag='blade')
    # the two raised halves of the blade, the rift sunk between them, and the prongs
    m.box((7.0, Y0, 7.69), (8.0 - 0.24, Y_FORK, 8.31), half, tag='blade')
    m.box((8.0 + 0.24, Y0, 7.69), (9.0, Y_FORK, 8.31), half, tag='blade')
    m.box((7.0, Y_FORK, 7.74), (7.5, 17.3, 8.26), half, tag='prong')
    m.box((8.5, Y_FORK, 7.74), (9.0, 16.4, 8.26), half, tag='prong')
    # crescent guard: a block, wings sweeping up, a crystal on each face
    m.box((6.8, 3.85, 7.3), (9.2, 4.95, 8.7), guard, tag='guard')
    for sign in (-1, 1):
        x = lambda v: 8 + sign * (v - 8)
        m.segment((x(6.9), 4.4), (x(5.0), 5.05), 0.62, 0.8, guard, tag='guard')
        m.segment((x(5.15), 4.85), (x(4.1), 6.5), 0.55, 0.7, guard, tag='guard')
        m.segment((x(4.25), 6.3), (x(4.05), 7.3), 0.42, 0.55, guard, tag='guard')
    for z in (8.78, 7.22):
        m.box((7.38, 3.78, z - 0.24), (8.62, 5.02, z + 0.24), gem, rot=('z', 45.0, (8, 4.4, z)), tag='crystal')
    # grip, pommel, hanging crystal
    m.rod(-1.8, 3.85, 0.55, leather(hexrgb('#1c1626'), hexrgb('#7c25d6'), hexrgb('#c98bff'), turns=2.2), tag='grip')
    m.rod(-2.35, -1.8, 0.66, dark, tag='pommel')
    m.box((7.45, -3.3, 7.45), (8.55, -2.2, 8.55), gem, rot=('z', 45.0, (8, -2.75, 8)), tag='crystal')
    return m
