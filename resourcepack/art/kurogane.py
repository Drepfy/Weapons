"""Kurogane: a katana. Polished blade with a wavy temper line, blackened spine and a crimson
groove; a face-on tsuba with a crimson ring round a gold collar; black grip with crimson wrap.
"""
from pix import Sprite

PAL = {
    'k': '#121117', 'q': '#260611',              # outlines: steel / crimson
    'W': '#f7faff', 'E': '#dfe7f1', 'H': '#bcc6d4', 'S': '#929cad',
    'M': '#3a3d48', 'm': '#2a2c35', 'h': '#5b606e',  # blackened steel spine
    'R': '#c8162e', 'r': '#8a0d21', 'p': '#f0505f', 'x': '#560818',
    'B': '#1b181d', 'b': '#2b252e', 'n': '#463e4a',
    'G': '#e4b54c', 'g': '#9a6a1d', 'Y': '#fde7a0',
}
OUT = {k: 'k' for k in 'WEHSMmhBbnGgY'}
OUT.update({k: 'q' for k in 'Rrpx'})

def bend(s):
    return 0 if s <= 19.5 else 1

HAMON = (0, 1, 1, 2, 1, 0, 0, 1, 2, 2, 1, 0)             # the wavy temper line, by half-step

# the kissaki: the edge sweeps up to meet the spine
TIP = [
    '...........WE',    # row 3 (x 18..)
    '........WWES.',    # 4
    '......WWEHSM.',    # 5
    '.....WEHShM..',    # 6
    '....WHHShM...',    # 7
]
# tsuba seen face on: blackened steel, silver rim, crimson inlay round the gold collar
TSUBA = [
    '..HWW..',          # row 18 (x 7..)
    '.WMMMH.',
    'HMpRMMS',
    'WMRGRMh',
    'HMMRrMh',
    '.hMMmm.',
    '..Shh..',
]

def make():
    sp = Sprite(PAL, OUT)

    def fn(s, o, x, y):
        a = abs(o)
        if 1 <= s <= 2.5 and a <= {1: 0, 1.5: 1, 2: 2, 2.5: 2}[s]:   # pommel cap with a crimson stone
            if (s, o) in ((2, 0), (1.5, -1)):
                return 'p' if s == 1.5 else 'R'
            if (s, o) in ((2.5, 1), (1.5, 1)):
                return 'r'
            return {-2: 'W', -1: 'H', 0: 'S', 1: 'h', 2: 'M'}[o]
        if 3 <= s <= 8.5 and a <= 2:                          # black grip, crimson diamond wrap
            u = int(2 * s)
            if (u + o) % 6 in (0, 1) or (u - o) % 6 in (0, 1):
                return 'p' if o <= -1 else ('r' if o >= 1 else 'R')
            return 'n' if o == -2 else ('B' if o >= 1 else 'b')
        if s == 9 and a <= 2:                                 # silver collar
            return 'W' if o <= -1 else ('H' if o <= 1 else 'S')
        if 9 <= s <= 11 and (o / 6.5) ** 2 + ((s - 10) / 1.25) ** 2 <= 1:    # tsuba: an oval plate
            r = (o / 6.5) ** 2 + ((s - 10) / 1.25) ** 2
            if r > 0.62:                                      # silver rim, lit from the top left
                return ('W' if s >= 10 else 'H') if o < 0 else ('H' if s >= 10.5 else 'S')
            if 0.25 < r <= 0.62 and abs(o) >= 2:
                return 'p' if o < 0 else ('R' if s >= 10 else 'r')   # crimson inlay ring
            return 'h' if o < -2 else ('M' if o < 2 else 'm')
        if 11 <= s <= 11.5 and a <= 3:                        # gold blade collar
            if a == 3:
                return None if s == 11.5 else ('Y' if o < 0 else 'g')
            return {-2: 'Y', -1: 'G', 0: 'G', 1: 'g', 2: 'g'}[o]
        if 11.5 < s <= 28:                                    # blade
            c = o - bend(s)
            lo = -3
            if s >= 23.5:
                lo = -3 + round(((s - 23.5) / 4.5) ** 2 * 5.5)   # the tip curves up to the spine
            if not (lo <= c <= 2):
                return None
            if c == lo:
                return 'W'                                    # the cutting edge
            h = HAMON[int(2 * s) % len(HAMON)]
            if c - lo <= h:
                return 'E'                                    # hardened edge, below the temper line
            if c == 2:
                return 'M'                                    # blackened spine
            if c == 1:
                return 'r' if 13.5 <= s <= 23.5 else 'h'      # crimson groove
            if c == 0 and 13.5 <= s <= 23.5:
                return 'x' if int(2 * s) % 7 == 0 else 'S'
            return 'H' if c < 0 else 'S'
        return None

    sp.paint(fn)
    for y in range(0, 8):                                     # the tip is drawn by hand
        for x in range(32):
            sp.set(x, y, None)
    sp.draw(TIP, 18, 3)
    sp.draw(TSUBA, 7, 18)
    sp.center()
    sp.outline()
    return sp
