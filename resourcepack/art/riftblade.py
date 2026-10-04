"""Riftblade: a dark metal sword split down the middle by a violet rift, which opens into a
forked tip. Crescent guard with a rift crystal; crystal pommel; leather grip with violet wire.
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

BLADE = 'LHUVuMm'                       # stripes across the blade, o = -3 .. 3
SPARKS = {11: 'C', 14: 'c', 17: 'C'}    # bright points inside the rift channel (by row)

# the split tip: the upper prong carries on, the lower one hooks away; the rift opens between
TIP = {
    1: {27: 'L'},
    2: {26: 'L', 27: 'H'},
    3: {25: 'L', 26: 'H', 27: 'h'},
    4: {24: 'L', 25: 'H', 26: 'h', 30: 'L'},
    5: {23: 'L', 24: 'H', 25: 'V', 29: 'h', 30: 'H'},
    6: {22: 'L', 23: 'H', 24: 'V', 28: 'U', 29: 'M', 30: 'h'},
    7: {21: 'L', 22: 'H', 23: 'V', 26: 'U', 27: 'M', 28: 'm'},
    8: {20: 'L', 21: 'H', 22: 'V', 24: 'U', 25: 'M', 26: 'm'},
    9: {19: 'L', 20: 'H', 21: 'U', 22: 'C', 23: 'U', 24: 'M', 25: 'm'},
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

def make():
    sp = Sprite(PAL, OUT)

    def fn(s, o, x, y):
        a = abs(o)
        if 1.5 <= s <= 3.5 and a <= {1.5: 0, 2: 1, 2.5: 2, 3: 1, 3.5: 0}[s]:   # pommel: a rift crystal
            return {(-1, 2.5): 'C', (0, 2): 'V', (0, 3): 'V', (1, 2.5): 'U', (0, 2.5): 'V'}.get(
                (o, s), 'L' if o < 0 else ('m' if s < 3 else 'h'))
        if 4 <= s <= 9.5 and -2 <= o <= 2:                    # wrapped leather grip, violet wire
            if int(2 * s) % 4 == 0:
                return 'w' if o < 0 else 'z'
            return 'n' if o == -2 else ('B' if o >= 1 else 'b')
        return None

    sp.paint(fn)
    for y in range(10, 20):                                    # blade body
        for i, ch in enumerate(BLADE):
            o = i - 3
            sp.set(31 - y + o, y, SPARKS.get(y, 'V') if o == 0 else ch)
    for y, row in TIP.items():
        for x, ch in row.items():
            sp.set(x, y, ch)
    for (x, y), ch in HORN.items():
        sp.set(x, y, ch)
        sp.set(31 - y, 31 - x, SHADE[ch])
    for (x, y), ch in CORE.items():
        sp.set(x, y, ch)
    sp.outline()
    return sp
