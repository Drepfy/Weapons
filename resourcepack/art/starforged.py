"""Starforged: a double-bladed axe of deep navy metal. Thin cyan edges, a lightning inlay in
each blade, an ice crystal set in gold between them, an ice shard on top and gold rings.
"""
from pix import Sprite, inside, line_dist, bezier

PAL = {
    'k': '#090c18', 'v': '#06213b', 'q': '#2a1a06',       # outlines: navy / crystal / gold
    'N': '#4a6bb0', 'n': '#2c437e', 'd': '#1c2a55', 'D': '#121b3a',   # deep navy metal
    'I': '#b9d4f5', 'i': '#7d9cd3',                       # frosted steel along the edge
    'c': '#4fe9ff', 'C': '#d4fdff', 'b': '#2b8fe6', 'B': '#1a56b0',   # ice and lightning
    'G': '#f2c14e', 'g': '#a8741f', 'Y': '#fff0b0',       # gold
    'l': '#151c35', 'm': '#232d50', 'u': '#36426a',       # wrapped grip
}
OUT = {k: 'k' for k in 'NndDIilmu'}
OUT.update({k: 'v' for k in 'cCbB'})
OUT.update({k: 'q' for k in 'GgY'})

# upper blade (the lower one is its mirror image across the haft). The blade is symmetric
# about its own axis y = x - 9; the haft runs along x + y = 31.
EDGE = bezier((21.0, 1.0), (17.0, 0.5), (9.5, 8.0), (10.0, 12.0), 16)
TOP = bezier((20.5, 7.5), (19.5, 5.5), (19.5, 3.5), (21.0, 1.0), 6)
BOTTOM = bezier((10.0, 12.0), (12.5, 10.5), (14.5, 10.5), (16.5, 11.5), 6)
BLADE = TOP + EDGE[1:] + BOTTOM[1:]
BOLT = [(19, 9), (18, 9), (17, 8), (16, 8), (16, 7), (15, 6), (14, 6), (13, 5)]   # lightning inlay

def mirror(x, y):
    return 31 - y, 31 - x

def make():
    sp = Sprite(PAL, OUT)

    def blade(cx, cy, lit):
        d = line_dist(EDGE, cx, cy)                            # thin glowing edge, thicker towards the core
        if d < 0.85:
            return ('c' if lit else 'b'), 1
        if d < 1.9:
            return ('I' if lit else 'i'), 2
        if lit:
            return ('N' if d < 3.6 else ('n' if d < 6.5 else 'd')), (2 if d < 3.6 else 3)
        return ('n' if d < 3.6 else ('d' if d < 6.5 else 'D')), (2 if d < 3.6 else 3)

    def fn(s, o, x, y):
        cx, cy = x + 0.5, y + 0.5
        if inside(BLADE, cx, cy):
            return blade(cx, cy, True)
        mx, my = mirror(cx, cy)
        if inside(BLADE, mx, my):
            return blade(mx, my, False)
        a = abs(o)
        if 22 <= s <= 25 and a <= 1:                           # haft above the head
            return ('N' if o < 0 else ('n' if o == 0 else 'd')), 3
        if 25.5 <= s <= 28.5 and a <= (1 if s <= 27 else 0):   # ice shard on top
            return ('C' if o < 0 else ('c' if o == 0 else 'b')), (3 if s <= 27 else 2)
        if 3.5 <= s <= 10 and a <= 2:                          # thick wrapped grip, raised bands
            if (int(2 * s) - o) % 4 in (0, 1):
                return ('N' if o < 0 else 'n'), 4
            return ('u' if o <= -1 else ('m' if o == 0 else 'l')), 3
        if 10.5 <= s <= 18.5 and a <= 1:                       # haft with gold rings
            if s in (11, 17.5, 18):
                return ('Y' if o < 0 else ('G' if o == 0 else 'g')), 4
            return ('N' if o < 0 else ('n' if o == 0 else 'd')), 3
        if 1.5 <= s <= 3 and a <= {1.5: 1, 2: 2, 2.5: 2, 3: 2}[s]:   # gold pommel with an ice crystal
            if (s, o) in ((2, 0), (2.5, -1), (2.5, 1)):
                return ('C' if o < 0 else ('c' if o == 0 else 'b')), 5
            return ('Y' if o < -1 else ('G' if o <= 0 else 'g')), 4
        return None

    sp.paint(fn)
    for x, y in BOLT:                                          # lightning inlays, sunk into the blades
        sp.set(x, y, 'c', 1)
        sp.set(*map(int, mirror(x + 0.5, y + 0.5)), 'b', 1)
    # the ice core, set in gold, where the blades meet
    core = {(20, 9): 'G', (19, 10): 'C', (20, 10): 'C', (21, 10): 'c', (18, 11): 'G', (19, 11): 'c',
            (20, 11): 'b', (21, 11): 'b', (22, 11): 'g', (19, 12): 'b', (20, 12): 'B', (21, 12): 'B',
            (20, 13): 'g', (18, 10): 'Y', (21, 9): 'G', (22, 12): 'g', (19, 13): 'g'}
    for (x, y), ch in core.items():
        sp.set(x, y, ch, 6 if ch in 'CcbB' else 5)              # the crystal stands out of its setting
    for (x, y), ch in {(19, 6): 'C', (14, 10): 'C', (18, 4): 'C', (21, 14): 'c', (24, 12): 'c',
                       (21, 17): 'c'}.items():
        sp.set(x, y, ch, sp.depth(x, y))                       # a few stars in the night-blue metal
    sp.center()
    sp.outline()
    return sp
