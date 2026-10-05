"""Kurogane: a crimson-steel katana. A polished blade with a wavy temper line, a raised spine
section with a crimson groove, a gold collar, an octagonal guard ringed in crimson and gold, a
handle bound in crimson cord and a black pommel set with a crimson gem.
"""
import math

from mesh import Model
from paint import bevel, crystal, fbm, gold, hexrgb, metal, mix, ramp, scale, smooth

EDGE_GLINT = hexrgb('#f6f9ff')
HAMON = hexrgb('#e3eaf3')
BODY_LIGHT, BODY_DARK = hexrgb('#b9c3d0'), hexrgb('#7f8a9b')
GROOVE, GROOVE_LIGHT = hexrgb('#6e0a1b'), hexrgb('#c41d36')
SPINE, SPINE_LIGHT = hexrgb('#2c3039'), hexrgb('#5a6170')
CORD, CORD_LIGHT, CORD_DARK = hexrgb('#b5142c'), hexrgb('#f0475b'), hexrgb('#5e0614')
SAME = hexrgb('#1a171b')

Y0, Y_KISSAKI, Y_TIP = 5.5, 15.9, 18.1     # blade from the collar to the point


def left(y):
    """The cutting edge (left side); it sweeps up to meet the spine in the point."""
    x = 8 - 0.98 + 0.12 * (y - Y0) / (Y_TIP - Y0)
    if y > Y_KISSAKI:
        q = (y - Y_KISSAKI) / (Y_TIP - Y_KISSAKI)
        x += (right(y) - x) * q ** 1.6
    return x


def right(y):
    """The spine (right side), curving in a little at the point."""
    x = 8 + 0.98 - 0.06 * (y - Y0) / (Y_TIP - Y0)
    if y > Y_KISSAKI:
        x -= 0.38 * ((y - Y_KISSAKI) / (Y_TIP - Y_KISSAKI)) ** 2
    return x


def blade_colour(x, y, z):
    xl, xr = left(y), right(y)
    u = (x - xl) / max(xr - xl, 1e-6)                    # 0 at the edge, 1 at the spine
    hamon = 0.30 + 0.05 * math.sin(y * 2.7) + 0.03 * math.sin(y * 6.1 + 1)
    streak = (fbm(u * 3, y * 6, 21) - 0.5) * 16
    sheen = 0.5 + 0.5 * math.sin(y * 0.9 - u * 2.2)
    if y > Y_KISSAKI - 0.05 and u < 0.82:
        c = mix(HAMON, EDGE_GLINT, 0.35 + 0.4 * sheen)    # the polished point
        if abs(y - Y_KISSAKI) < 0.06:
            c = EDGE_GLINT                                 # the line where the point begins
    elif u < 0.06:
        c = EDGE_GLINT
    elif u < hamon:
        c = mix(HAMON, EDGE_GLINT, 0.25 * sheen)
        c = mix(c, EDGE_GLINT, max(0.0, 1 - abs(u - hamon) / 0.05) * 0.7)   # the misty temper line
    elif u < 0.62:
        c = ramp([(0, BODY_LIGHT), (1, BODY_DARK)], (u - hamon) / (0.62 - hamon) * 0.7 + 0.3 * (1 - sheen))
    elif u < 0.82 and Y0 + 0.8 < y < Y_KISSAKI - 0.6:
        g = abs((u - 0.72) / 0.1)                          # the groove: deep in the middle, lit at its rims
        c = mix(GROOVE, GROOVE_LIGHT, smooth(g) * 0.8)
    elif u < 0.82:
        c = BODY_DARK
    else:
        c = mix(SPINE_LIGHT, SPINE, smooth((u - 0.82) / 0.18))
    return tuple(v + streak for v in c)


def blade(face, s, t, p):
    x, y, z = p
    if not (Y0 - 0.01 <= y <= Y_TIP and left(y) <= x <= right(y)):
        return None
    return blade_colour(x, y, z)


def raised(face, s, t, p):
    """The thicker spine half of the blade: same colours on its front, bevelled sides."""
    x, y, z = p
    if face.dir in ('south', 'north'):
        return blade_colour(x, y, z)
    if face.dir == 'west':
        return EDGE_GLINT if t < 0.5 else HAMON          # the ridge line, catching the light
    return bevel(face, s, t, SPINE_LIGHT if face.dir == 'up' else SPINE)


def handle(face, s, t, p):
    """Crimson cord crossing over black ray skin, leaving diamond windows."""
    x, y, z = p
    around = math.atan2(z - 8, x - 8) / (2 * math.pi)
    a = (y * 0.85 + around * 2) % 1.0
    b = (y * 0.85 - around * 2) % 1.0
    d = min(a, b)
    if d < 0.32:
        q = d / 0.32
        c = mix(CORD_LIGHT, CORD, smooth(q * 1.6))
        c = mix(c, CORD_DARK, smooth((q - 0.7) / 0.3))
    else:
        dots = fbm(x * 9 + z * 9, y * 9, 31)
        c = mix(SAME, hexrgb('#4a454c'), max(0.0, dots - 0.55) * 2.5)
    return bevel(face, s, t, c, 0.06)


def guard(face, s, t, p):
    """The tsuba, a square plate: black iron, a gold rim and a crimson ring round the blade."""
    x, y, z = p
    edge = max(abs(x - 8), abs(z - 8))
    iron = scale(hexrgb('#26272e'), 0.85 + 0.3 * fbm(x * 3, z * 3, 41))
    if face.dir in ('up', 'down'):
        if edge > 1.8:
            return gold()(face, s, t, p)
        r = math.hypot(x - 8, z - 8)
        if 1.05 < r < 1.5:
            return mix(GROOVE_LIGHT, GROOVE, abs(r - 1.27) / 0.22)
        return iron
    if abs(t - 0.5) > 0.25:
        return gold()(face, s, t, p)
    return bevel(face, s, t, mix(GROOVE, GROOVE_LIGHT, 0.5 + 0.5 * math.sin(s * math.pi)), 0.0)


def build():
    m = Model()
    steel = metal(hexrgb('#4a505c'), hexrgb('#8e98a8'), hexrgb('#e2e8f0'), seed=2)
    dark = metal(hexrgb('#141418'), hexrgb('#2e3038'), hexrgb('#5d6270'), seed=4)
    gilt = gold()
    # blade: a thin polished card with its shape cut out, and a thicker spine half
    m.card(left(Y0) - 0.01, Y0, right(Y0) + 0.01, Y_TIP, 0.24, blade, tag='blade')
    xm = left(Y0) + 0.55 * (right(Y0) - left(Y0))
    m.box((xm, Y0, 7.7), (right(Y_KISSAKI) - 0.02, Y_KISSAKI - 0.3, 8.3), raised, tag='spine')
    # gold collar (habaki)
    m.box((left(Y0) - 0.18, 4.65, 7.45), (right(Y0) + 0.18, Y0 + 0.45, 8.55), gilt, tag='habaki')
    # octagonal guard (tsuba)
    m.box((5.9, 3.9, 5.9), (10.1, 4.65, 10.1), guard, tag='tsuba')
    # collar, handle, pommel
    m.rod(3.45, 3.95, 0.74, gilt, tag='fuchi')
    m.rod(-2.15, 3.45, 0.62, handle, tag='tsuka')
    m.rod(-2.9, -2.15, 0.76, dark, tag='kashira')
    gem = crystal(hexrgb('#4a0612'), hexrgb('#c8162e'), hexrgb('#ffb3bd'))
    for z in (8.72, 7.28):                                   # a crimson gem set in each face of the pommel
        m.box((7.68, -2.85, z - 0.16), (8.32, -2.21, z + 0.16), gem, rot=('z', 45.0, (8, -2.53, z)), tag='gem')
    return m
