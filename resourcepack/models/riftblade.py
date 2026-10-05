"""Riftblade: a broad greatsword of dark violet steel, split down the middle by a glowing rift that
flows up the blade and tears its point in two. A crescent guard with a violet crystal, a grip
bound in violet wire, and a crystal pommel.
"""
import math

from pixel import Art, dome, inside, noise, ramp

STEEL = ramp('#0d0a15', '#1b1528', '#2b2240', '#3d3259', '#544774', '#6f6293', '#958ab6', '#c3bbe0', '#ece8fb')
RIFT = ramp('#2a0756', '#4d129c', '#7a2be0', '#a457ff', '#c98bff', '#ead2ff', '#ffffff')
GUARD = ramp('#100d18', '#231d33', '#3a3152', '#564b74', '#7a6e9b', '#a69cc4', '#d9d3ee')
GEM = ramp('#1d0540', '#3b0a73', '#6a1bb8', '#9b45f0', '#c78cff', '#f1dcff')
LEATHER = ramp('#0b0910', '#17121f', '#251d31', '#362b46')
WIRE = ramp('#2a0b52', '#5a1aa6', '#8c3ae6', '#c08aff')

Y_BLADE, Y_FORK, Y_TIP = 5.9, 13.7, 15.9
HALF = 1.15                                           # half the blade's width


def outline_x(y):
    """Half width of the blade at height y (a slight taper towards the point)."""
    return HALF - 0.12 * (y - Y_BLADE) / (Y_TIP - Y_BLADE)


def rift_half(y):
    return 0.2 if y < Y_FORK else 0.2 + (y - Y_FORK) * 0.38


def in_blade(x, y):
    if not (Y_BLADE <= y <= Y_TIP):
        return False
    d = x - 8
    w = outline_x(y)
    if abs(d) > w:
        return False
    if y > Y_FORK and abs(d) < rift_half(y) - 0.05:
        return False                                   # the rift has torn the point open
    # points of the two prongs: the left one long, the right one shorter
    if d < 0 and y > 15.0 and -d > w - (y - 15.0) * 1.3:
        return False
    if d > 0 and y > 14.6 and d > w - (y - 14.6) * 1.9:
        return False
    if d > 0 and y > 15.25:
        return False
    return True


def build():
    art = Art()

    def blade_tone(x, y):
        d = x - 8
        w = outline_x(y)
        a = abs(d)
        r = rift_half(y)
        e = w - a                                       # distance from the outer edge
        if e < 0.13:
            return 0.95 if d < 0 else 0.55              # edges: the left one catches the light
        if e < 0.38:
            return 0.8 if d < 0 else 0.4                # bevel
        if a < r + 0.14:
            return 0.12                                 # the dark walls of the rift
        return (0.58 if d < 0 else 0.3) + 0.06 * math.sin(y * 1.7)

    art.add('blade', in_blade, lambda x, y: 0.5, STEEL, depth=3, tone=blade_tone,
            detail=lambda x, y: (noise(x * 5, y * 0.6, 11) - 0.5) * 0.6)

    def rift_glow(x, y, t):
        a = abs(x - 8) / max(rift_half(y), 1e-6)
        core = 1.0 - a * 0.75
        flow = 0.5 + 0.5 * math.sin(2 * math.pi * (t * 2 - y * 0.55))
        spark = 0.35 if noise(x * 7, y * 3 - t * 12, 17) > 0.78 else 0.0
        return core * (0.55 + 0.45 * flow) + spark

    art.add('rift', lambda x, y: in_blade(x, y) and abs(x - 8) < rift_half(y) and y < Y_FORK + 0.01
            or (Y_FORK <= y <= 15.3 and abs(x - 8) < rift_half(y) + 0.13 and in_blade(x, y)),
            lambda x, y: 0.5, RIFT, depth=2, outline=False, shadow=False, glow=rift_glow)
    # crescent guard: a bar whose wings sweep up, a crystal in the middle
    wing = [(4.9, 5.05), (5.6, 5.35), (6.6, 5.25), (8.0, 5.3), (9.4, 5.25), (10.4, 5.35), (11.1, 5.05),
            (11.6, 5.75), (11.75, 6.75), (11.35, 6.6), (10.85, 6.0), (10.0, 5.95), (8.0, 6.1),
            (6.0, 5.95), (5.15, 6.0), (4.65, 6.6), (4.25, 6.75), (4.4, 5.75)]
    art.add('guard', lambda x, y: inside(wing, x, y),
            lambda x, y: math.sqrt(max(0.0, 1 - ((y - 5.65 - 0.06 * abs(x - 8)) / 0.55) ** 2)), GUARD,
            depth=6, relief=2.4, shine=0.6)
    art.add('crystal', lambda x, y: abs(x - 8) + abs(y - 5.65) * 0.8 < 0.62,
            dome(7.85, 5.75, 0.7), GEM, depth=8, relief=3.0, shine=1.0)
    # grip with violet wire, pommel crystal
    art.add('grip', lambda x, y: abs(x - 8) <= 0.5 and 1.25 <= y <= 5.0,
            lambda x, y: math.sqrt(max(0.0, 1 - ((x - 8) / 0.55) ** 2)), LEATHER, depth=4, relief=2.0)
    art.add('wire', lambda x, y: abs(x - 8) <= 0.5 and 1.35 <= y <= 4.9 and (y * 2.1 + (x - 8) * 1.6) % 1.0 < 0.3,
            lambda x, y: math.sqrt(max(0.0, 1 - ((x - 8) / 0.55) ** 2)), WIRE, depth=5, relief=2.0, outline=False)
    art.add('pommel cap', lambda x, y: abs(x - 8) <= 0.62 and 0.95 <= y <= 1.3,
            lambda x, y: math.sqrt(max(0.0, 1 - ((x - 8) / 0.65) ** 2)), GUARD, depth=5, relief=2.0, shine=0.6)
    art.add('pommel', lambda x, y: abs(x - 8) + abs(y - 0.55) * 0.75 < 0.5,
            dome(7.88, 0.62, 0.55), GEM, depth=6, relief=3.0, shine=1.0)
    return art, 3.1
