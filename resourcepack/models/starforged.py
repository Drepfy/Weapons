"""Starforged: a double-bladed battle axe of deep navy steel. Each crescent blade has a crackling
cyan edge, a lightning bolt and a few stars set in it; a pulsing ice crystal sits in a gold
setting between them. A navy haft with gold rings, a wrapped grip, an ice shard on top and a
gold pommel with an ice crystal.
"""
import math

from pixel import Art, bezier, dome, inside, line_dist, noise, ramp

NAVY = ramp('#070b1a', '#0f1834', '#18244d', '#233569', '#30488a', '#4562ac', '#6a88cc', '#a3bce8')
CYAN = ramp('#06304a', '#0b5f86', '#1aa3cf', '#4fe9ff', '#a8f6ff', '#effeff')
GOLD = ramp('#3b2204', '#6b410c', '#9e6719', '#d29a33', '#f3cb63', '#fff0b0')
ICE = ramp('#0b2a63', '#14509e', '#2a86d6', '#56c5f5', '#a6ecff', '#effdff')
WRAP = ramp('#090d1c', '#131a33', '#1f2a4f', '#2f3e70')

Y_HEAD = 11.8
EDGE_R = bezier((12.35, 15.0), (14.25, 13.6), (14.25, 10.0), (12.35, 8.6), 32)
TOP_R = bezier((8.9, 12.55), (10.3, 13.1), (11.4, 14.0), (12.35, 15.0), 16)
BOTTOM_R = bezier((12.35, 8.6), (11.4, 9.6), (10.3, 10.5), (8.9, 11.05), 16)
RIGHT = TOP_R + EDGE_R[1:] + BOTTOM_R[1:]
LEFT = [(16 - x, y) for x, y in RIGHT]
BOLT_R = [(9.15, 11.85), (10.35, 12.35), (10.85, 11.45), (12.3, 12.05), (13.45, 11.55)]
STARS = [(11.3, 13.4), (12.6, 10.0), (10.4, 10.75), (13.05, 13.2)]


def mirror(pts):
    return [(16 - x, y) for x, y in pts]


def build():
    art = Art()
    # haft, rings, grip, pommel
    art.add('haft', lambda x, y: abs(x - 8) <= 0.42 and 4.2 <= y <= 15.0,
            lambda x, y: math.sqrt(max(0.0, 1 - ((x - 8) / 0.46) ** 2)), NAVY, depth=4, relief=2.2, shine=0.5)
    art.add('grip', lambda x, y: abs(x - 8) <= 0.52 and 1.0 <= y <= 4.2,
            lambda x, y: math.sqrt(max(0.0, 1 - ((x - 8) / 0.56) ** 2)) * (0.7 + 0.3 * ((y * 2.4 - (x - 8) * 1.2) % 1.0)),
            WRAP, depth=5, relief=2.6)
    for yb in (4.15, 8.3, 14.55):
        art.add('ring', lambda x, y, yb=yb: abs(x - 8) <= 0.58 and yb <= y <= yb + 0.32,
                lambda x, y: math.sqrt(max(0.0, 1 - ((x - 8) / 0.62) ** 2)), GOLD, depth=5, relief=2.2, shine=0.8)
    art.add('pommel', lambda x, y: abs(x - 8) <= 0.62 and 0.6 <= y <= 1.02,
            lambda x, y: math.sqrt(max(0.0, 1 - ((x - 8) / 0.65) ** 2)), GOLD, depth=6, relief=2.2, shine=0.8)
    art.add('pommel crystal', lambda x, y: abs(x - 8) + abs(y - 0.3) * 0.9 < 0.42 and y < 0.62,
            dome(7.9, 0.4, 0.45), ICE, depth=5, relief=3.0, shine=1.0)
    # the two blades
    def blade_tone(x, y):
        xr = x if x >= 8 else 16 - x
        d = line_dist(EDGE_R, xr, y)
        if d < 0.75:
            return 0.92 - d * 0.4                        # frosted bevel next to the glowing edge
        lit = 0.08 if x < 8 else -0.08                   # the left blade faces the light
        return 0.5 + lit - min(d - 0.75, 3.0) * 0.08 + 0.04 * math.sin(y * 2 + xr)

    in_blades = lambda x, y: inside(RIGHT, x, y) or inside(LEFT, x, y)
    art.add('blades', in_blades, lambda x, y: 0.5, NAVY, depth=3, tone=blade_tone,
            detail=lambda x, y: (noise(x * 3, y * 3, 41) - 0.5) * 0.6)

    def edge_glow(x, y, t):
        xr = x if x >= 8 else 16 - x
        flicker = noise(y * 2.5, t * 10 + (x < 8) * 5, 43)
        return 0.55 + 0.45 * flicker

    art.add('edge', lambda x, y: in_blades(x, y) and line_dist(EDGE_R, x if x >= 8 else 16 - x, y) < 0.2,
            lambda x, y: 0.5, CYAN, depth=2, outline=True, shadow=False, glow=edge_glow)

    def bolt_glow(x, y, t):
        xr = x if x >= 8 else 16 - x
        run = (xr - 9.0) / 4.5                           # the bolt flashes outwards from the core
        return 0.35 + 0.65 * max(0.0, math.sin(2 * math.pi * (t * 1.5 - run * 0.8))) ** 3

    bolt_l = mirror(BOLT_R)
    art.add('lightning', lambda x, y: line_dist(BOLT_R, x, y) < 0.1 or line_dist(bolt_l, x, y) < 0.1,
            lambda x, y: 0.5, CYAN, depth=3, outline=False, shadow=False, glow=bolt_glow)
    stars = STARS + mirror(STARS[:3])
    art.add('stars', lambda x, y: any(math.hypot(x - sx, y - sy) < 0.12 for sx, sy in stars),
            lambda x, y: 0.5, CYAN, depth=3, outline=False, shadow=False,
            glow=lambda x, y, t: 0.5 + 0.5 * math.sin(2 * math.pi * (t + x * 0.37 + y * 0.21)))
    # the core: a gold setting and a glowing ice crystal
    art.add('setting', lambda x, y: abs(x - 8) <= 0.95 and abs(y - Y_HEAD) <= 0.95 and abs(x - 8) + abs(y - Y_HEAD) < 1.6,
            dome(7.9, Y_HEAD + 0.1, 1.2), GOLD, depth=6, relief=2.6, shine=0.8)

    def ice_glow(x, y, t):
        d = (abs(x - 8) + abs(y - Y_HEAD)) / 0.75
        pulse = 0.5 + 0.5 * math.sin(2 * math.pi * t)
        light = max(0.0, 1 - math.hypot(x - 7.8, y - Y_HEAD - 0.2) / 0.5)
        return (1 - d) * 0.6 + 0.25 * pulse + light * 0.5

    art.add('ice', lambda x, y: abs(x - 8) + abs(y - Y_HEAD) < 0.72, lambda x, y: 0.5, ICE, depth=8,
            outline=True, glow=ice_glow)
    art.add('top shard', lambda x, y: 14.85 <= y <= 16.0 and abs(x - 8) <= 0.42 * (16.0 - y) / 1.15,
            lambda x, y: 1 - abs(x - 8) / 0.42, ICE, depth=4, relief=3.0, shine=1.0)
    return art, 2.6
