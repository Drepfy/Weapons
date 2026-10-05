"""Gravebreaker: a bearded battle axe. A broad head with a polished cutting edge, a forged-iron
centre held by rivets and split by a smouldering crack, a back spike and a top spike, on a long
dark-wood haft with iron bands, a leather grip and a spiked iron pommel.
"""
import math

from pixel import Art, bezier, dome, inside, line_dist, noise, ramp

STEEL = ramp('#1b1e24', '#353a44', '#535a66', '#78808d', '#a0a8b4', '#c8cfd8', '#eef2f6', '#ffffff')
IRON = ramp('#0d0e11', '#1b1d22', '#2b2e35', '#3e424b', '#565b66', '#737985')
EMBER = ramp('#3a0a02', '#7a1d04', '#c43d08', '#f2701a', '#ffad42', '#ffe39a', '#fffbe8')
WOOD = ramp('#1f1209', '#341f10', '#4c2f18', '#6a4424', '#8a5c33', '#a8774a')
LEATHER = ramp('#120d0a', '#231a14', '#3a2c22', '#554234', '#735c4a')
RIVET = ramp('#2b2e35', '#565b66', '#9aa1ad', '#e6eaef')

EDGE = bezier((3.25, 15.35), (1.55, 13.9), (1.45, 10.2), (3.0, 8.55), 32)
BEARD = bezier((3.0, 8.55), (4.4, 9.0), (6.0, 9.6), (7.35, 10.75), 16)
TOP = bezier((7.35, 14.35), (6.0, 14.35), (4.6, 14.6), (3.25, 15.35), 16)
HEAD = EDGE + BEARD[1:] + TOP[:-1]
CRACK = [(4.6, 10.6), (5.15, 11.2), (4.85, 11.9), (5.45, 12.5), (5.3, 13.1), (5.85, 13.6)]
SPIKE = [(8.65, 13.35), (11.1, 12.25), (8.65, 11.55)]
TOP_SPIKE = [(7.55, 14.55), (8.0, 16.0), (8.45, 14.55)]


def build():
    art = Art()
    # haft, bands, grip, pommel
    art.add('haft', lambda x, y: abs(x - 8) <= 0.45 and 4.3 <= y <= 14.6,
            lambda x, y: math.sqrt(max(0.0, 1 - ((x - 8) / 0.5) ** 2)), WOOD, depth=4, relief=2.2,
            detail=lambda x, y: (noise(x * 10, y * 0.7, 21) - 0.5) * 1.6)
    for yb in (4.35, 8.7):
        art.add('band', lambda x, y, yb=yb: abs(x - 8) <= 0.56 and yb <= y <= yb + 0.35,
                lambda x, y: math.sqrt(max(0.0, 1 - ((x - 8) / 0.6) ** 2)), IRON, depth=5, relief=2.2, shine=0.6)
    art.add('grip', lambda x, y: abs(x - 8) <= 0.53 and 0.95 <= y <= 4.35,
            lambda x, y: math.sqrt(max(0.0, 1 - ((x - 8) / 0.58) ** 2)) * (0.75 + 0.25 * ((y * 2.4 + (x - 8) * 1.2) % 1.0)),
            LEATHER, depth=5, relief=2.6)
    art.add('pommel', lambda x, y: abs(x - 8) <= 0.62 and 0.45 <= y <= 0.98,
            lambda x, y: math.sqrt(max(0.0, 1 - ((x - 8) / 0.65) ** 2)), IRON, depth=6, relief=2.2, shine=0.6)
    art.add('pommel spike', lambda x, y: 0.0 <= y < 0.45 and abs(x - 8) <= 0.45 * y / 0.45,
            lambda x, y: math.sqrt(max(0.0, 1 - ((x - 8) / 0.5) ** 2)), STEEL, depth=4, relief=2.0, shine=0.6)
    # head: the blade, then the forged centre over it
    def head_tone(x, y):
        d = line_dist(EDGE, x, y)
        if d < 0.16:
            return 1.0                                  # the honed edge
        if d < 0.62:
            return 0.86 - (d - 0.16) * 0.25
        if d < 0.72:
            return 0.95                                 # the bevel line
        if d < 2.0:
            return 0.58 - (d - 0.72) * 0.14 + 0.05 * math.sin(y * 1.3 + x)
        return 0.38

    art.add('head', lambda x, y: inside(HEAD, x, y), lambda x, y: 0.5, STEEL, depth=3, tone=head_tone,
            detail=lambda x, y: (noise(x * 3, y * 3, 23) - 0.5) * 0.7)
    forge = [(4.2, 10.4), (5.6, 10.25), (7.35, 10.75), (7.35, 14.35), (6.0, 14.3), (4.6, 14.5), (4.05, 12.4)]
    art.add('forged iron', lambda x, y: inside(forge, x, y),
            lambda x, y: 0.5 + 0.5 * noise(x * 1.6, y * 1.6, 29), IRON, depth=5, relief=2.2,
            detail=lambda x, y: (noise(x * 4, y * 4, 31) - 0.5) * 0.6)
    art.add('crack', lambda x, y: line_dist(CRACK, x, y) < 0.11, lambda x, y: 0.5, EMBER, depth=4,
            outline=False, shadow=False,
            glow=lambda x, y, t: 0.5 + 0.3 * math.sin(2 * math.pi * (t + y * 0.3))
            + 0.25 * (noise(y * 3, t * 8, 37) - 0.5))
    art.add('crack glow', lambda x, y: 0.11 <= line_dist(CRACK, x, y) < 0.2, lambda x, y: 0.5, EMBER, depth=5,
            outline=False, shadow=False,
            glow=lambda x, y, t: 0.15 + 0.12 * math.sin(2 * math.pi * (t + y * 0.3)))
    for rx, ry in ((6.55, 13.6), (6.55, 11.35), (4.85, 13.95)):
        art.add('rivet', lambda x, y, rx=rx, ry=ry: math.hypot(x - rx, y - ry) < 0.24, dome(rx - 0.04, ry + 0.04, 0.26),
                RIVET, depth=7, relief=3.0, shine=1.0)
    # socket round the haft, spikes
    art.add('socket', lambda x, y: abs(x - 8) <= 0.68 and 10.55 <= y <= 14.6,
            lambda x, y: math.sqrt(max(0.0, 1 - ((x - 8) / 0.72) ** 2)), IRON, depth=7, relief=2.2, shine=0.5)
    art.add('socket bands', lambda x, y: abs(x - 8) <= 0.74 and (10.55 <= y <= 10.85 or 14.3 <= y <= 14.6),
            lambda x, y: math.sqrt(max(0.0, 1 - ((x - 8) / 0.78) ** 2)), STEEL, depth=8, relief=2.2, shine=0.8)
    art.add('back spike', lambda x, y: inside(SPIKE, x, y),
            lambda x, y: 1 - abs(y - (12.45 - (x - 8.65) * 0.08)) / 0.9, STEEL, depth=4, relief=2.6, shine=0.7)
    art.add('top spike', lambda x, y: inside(TOP_SPIKE, x, y),
            lambda x, y: 1 - abs(x - 8) / 0.45, STEEL, depth=4, relief=2.6, shine=0.7)
    return art, 2.6
