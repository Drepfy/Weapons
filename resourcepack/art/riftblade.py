"""Riftblade: a broad dark metal sword split down the middle by a sunken violet rift, which
opens into a forked tip. Crescent guard with a raised rift crystal; crystal pommel; leather grip
with raised violet wire.
"""
from pix import Sprite

PAL = {
    'k': '#0e0a16', 'v': '#26073f',                       # outlines: metal / energy
    'L': '#d9d4f2', 'H': '#9d95c4', 'h': '#615884', 'M': '#3b3453', 'm': '#29233b',   # dark metal
    'V': '#b55cff', 'U': '#7c25d6', 'u': '#4d128f', 'C': '#f3d2ff', 'c': '#6ee9ff',   # rift energy
    'B': '#1c1626', 'b': '#2c2339', 'n': '#463a58',          # leather
    'w': '#7c25d6', 'z': '#4d128f',                       # violet wire on the grip
}
OUT = {k: 'k' for k in 'LHhMmBbn'}
OUT.update({k: 'v' for k in 'VUuCc'})
OUT.update({'w': 'k', 'z': 'k'})

BLADE = 'LHhUVuMmh'                     # stripes across the blade, o = -4 .. 4
BLADE_DEPTH = (1, 2, 3, 3, 2, 3, 3, 2, 1)  # thin edges, thick walls, the rift sunk between them
SPARKS = {11: 'C', 14: 'c', 17: 'C'}    # bright points inside the rift channel (by row)

# the split tip: the upper prong carries on, the lower one hooks away; the rift opens between.
# Each pixel: (x, colour, thickness)
TIP = {
    1: [(26, 'L', 1)],
    2: [(25, 'L', 1), (26, 'H', 2)],
    3: [(24, 'L', 1), (25, 'H', 2), (26, 'h', 2)],
    4: [(23, 'L', 1), (24, 'H', 2), (25, 'h', 3), (26, 'V', 2), (30, 'L', 1)],
    5: [(22, 'L', 1), (23, 'H', 2), (24, 'h', 3), (25, 'V', 2), (29, 'M', 2), (30, 'h', 1)],
    6: [(21, 'L', 1), (22, 'H', 2), (23, 'h', 3), (24, 'V', 2), (28, 'U', 2), (29, 'M', 3), (30, 'h', 1)],
    7: [(20, 'L', 1), (21, 'H', 2), (22, 'h', 3), (23, 'V', 2), (26, 'U', 2), (27, 'M', 3), (28, 'm', 2),
        (29, 'h', 1)],
    8: [(19, 'L', 1), (20, 'H', 2), (21, 'h', 3), (22, 'V', 2), (24, 'U', 2), (25, 'M', 3), (26, 'm', 2),
        (27, 'h', 1)],
    9: [(18, 'L', 1), (19, 'H', 2), (20, 'h', 3), (21, 'U', 3), (22, 'C', 2), (23, 'U', 3), (24, 'M', 3),
        (25, 'm', 2), (26, 'h', 1)],
}

# crescent guard, upper horn (the lower horn is its mirror image across the blade, in shadow)
HORN = {
    (7, 13): 'L',
    (7, 14): 'L', (8, 14): 'H',
    (7, 15): 'H', (8, 15): 'L',
    (7, 16): 'h', (8, 16): 'L', (9, 16): 'H',
    (8, 17): 'h', (9, 17): 'L', (10, 17): 'H',
    (9, 18): 'h', (10, 18): 'L', (11, 18): 'H',
    (9, 19): 'M', (10, 19): 'h',
}
SHADE = {'L': 'H', 'H': 'h', 'h': 'M', 'M': 'm'}
# rift crystal set in the middle of the guard
CORE = {(11, 19): 'L', (12, 19): 'H', (10, 20): 'C', (11, 20): 'V', (12, 20): 'U', (11, 21): 'u',
        (13, 20): 'h', (12, 21): 'M', (11, 22): 'm', (10, 21): 'M'}
CRYSTAL = {(10, 20), (11, 20), (12, 20), (11, 21)}

def make():
    sp = Sprite(PAL, OUT)

    def fn(s, o, x, y):
        a = abs(o)
        if 1.5 <= s <= 3.5 and a <= {1.5: 0, 2: 1, 2.5: 2, 3: 1, 3.5: 0}[s]:   # pommel: a rift crystal
            gem = {(-1, 2.5): 'C', (0, 2): 'V', (0, 3): 'V', (1, 2.5): 'U', (0, 2.5): 'V'}.get((o, s))
            if gem:
                return gem, 5
            return ('L' if o < 0 else ('m' if s < 3 else 'h')), 4
        if 4 <= s <= 9.5 and -2 <= o <= 2:                    # wrapped leather grip, raised violet wire
            if int(2 * s) % 4 == 0:
                return ('w' if o < 0 else 'z'), 4
            return ('n' if o == -2 else ('B' if o >= 1 else 'b')), 3
        return None

    sp.paint(fn)
    for y in range(10, 20):                                    # blade body
        for i, ch in enumerate(BLADE):
            o = i - 4
            sp.set(31 - y + o, y, SPARKS.get(y, 'V') if o == 0 else ch, BLADE_DEPTH[i])
    for y, row in TIP.items():
        for x, ch, d in row:
            sp.set(x, y, ch, d)
    for (x, y), ch in HORN.items():
        d = 2 if y <= 14 else 3                                # the horns thin out towards their tips
        sp.set(x, y, ch, d)
        sp.set(31 - y, 31 - x, SHADE[ch], d)
    for (x, y), ch in CORE.items():
        sp.set(x, y, ch, 5 if (x, y) in CRYSTAL else 4)
    sp.center()
    sp.outline()
    return sp
