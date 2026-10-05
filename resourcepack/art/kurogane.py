"""Kurogane: a crimson-steel katana. Broad polished blade with a wavy temper line, a raised
ridge, a sunken crimson groove and a blackened spine; a thick octagonal guard with a crimson
inlay round a gold collar; black grip with a raised crimson wrap; silver pommel with a gem.
"""
from pix import Sprite

PAL = {
    'k': '#121117', 'q': '#260611',                       # outlines: steel / crimson
    'W': '#f7faff', 'E': '#dfe7f1', 'H': '#bcc6d4', 'S': '#929cad',   # polished steel
    'M': '#3a3d48', 'm': '#2a2c35', 'h': '#5b606e',       # blackened steel
    'R': '#c8162e', 'r': '#8a0d21', 'p': '#f0505f', 'x': '#560818',   # crimson
    'B': '#1b181d', 'b': '#2b252e', 'n': '#463e4a',       # black grip
    'G': '#e4b54c', 'g': '#9a6a1d', 'Y': '#fde7a0',       # gold
}
OUT = {k: 'k' for k in 'WEHSMmhBbnGgY'}
OUT.update({k: 'q' for k in 'Rrpx'})

HAMON = (0, 1, 1, 2, 1, 0, 0, 1, 2, 2, 1, 0)             # the wavy temper line, by half-step


def bend(s):
    return 0 if s <= 19.5 else 1                          # the blade curves back near the tip


def guard(sp, cx, cy):
    """An octagonal tsuba seen face on: silver rim, black face, crimson inlay, gold collar."""
    cells = {(dx, dy) for dx in range(-4, 5) for dy in range(-4, 5) if abs(dx) + abs(dy) <= 6}
    for dx, dy in cells:
        rim = any((dx + ex, dy + ey) not in cells for ex, ey in ((1, 0), (-1, 0), (0, 1), (0, -1)))
        lit = dx + dy < 0
        if rim:
            k = ('W' if dx + dy <= -4 else 'H') if lit else ('S' if dx + dy < 4 else 'h')
            d = 4
        elif abs(dx) + abs(dy) <= 1:
            k = {(0, 0): 'G', (-1, 0): 'Y', (0, -1): 'Y'}.get((dx, dy), 'g')
            d = 5
        elif max(abs(dx), abs(dy)) == 2 or abs(dx) + abs(dy) == 3:
            k = 'p' if dx + dy < -1 else ('R' if dx + dy < 2 else 'r')
            d = 5
        else:
            k = 'h' if lit and dx + dy <= -4 else ('M' if lit else 'm')
            d = 4
        sp.set(cx + dx, cy + dy, k, d)


def make():
    sp = Sprite(PAL, OUT)

    def fn(s, o, x, y):
        a = abs(o)
        if 1.5 <= s <= 3 and a <= {1.5: 1, 2: 2, 2.5: 3, 3: 2}[s]:    # pommel cap with a crimson gem
            if (s, o) in ((2, 0), (2.5, -1), (2.5, 1)):
                return ('p' if o < 0 else ('R' if o == 0 else 'r')), 5
            return {-3: 'W', -2: 'W', -1: 'H', 0: 'S', 1: 'h', 2: 'M', 3: 'M'}[o], 4
        if 3.5 <= s <= 8.5 and a <= 2:                        # black grip, raised crimson wrap
            u = int(2 * s)
            if (u + o) % 6 in (0, 1) or (u - o) % 6 in (0, 1):
                return ('p' if o <= -1 else ('r' if o >= 1 else 'R')), 4
            return ('n' if o == -2 else ('B' if o >= 1 else 'b')), 3
        if 11 <= s <= 12 and a <= 3:                          # gold collar
            return {-3: 'Y', -2: 'Y', -1: 'G', 0: 'G', 1: 'g', 2: 'g', 3: 'g'}[o], 3
        if 12 < s <= 28.5:                                     # blade
            c = o - bend(s)
            lo = -3 + max(0.0, s - 22) * 0.75                  # the point: the edge sweeps up to the spine
            hi = 3 - max(0.0, s - 24) * 0.4
            if not lo <= c <= hi:
                return None
            if c - lo < 1:
                return 'W', 1                                  # cutting edge
            if c - lo <= HAMON[int(2 * s) % len(HAMON)] and s <= 22:
                return 'E', 2                                  # hardened edge, under the temper line
            if c <= -1:
                return 'H', 2
            if c == 0:
                return 'W', 3                                  # the ridge
            if c == 1:
                return 'S', 3
            if c == 2:
                if 13.5 <= s <= 24:
                    return ('x' if int(2 * s) % 7 == 0 else 'r'), 2   # crimson groove, sunken
                return 'h', 3
            return 'M', 3                                      # blackened spine
        return None

    sp.paint(fn)
    guard(sp, 10, 21)
    sp.center()
    sp.outline()
    return sp
