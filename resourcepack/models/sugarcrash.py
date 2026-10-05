"""Sugarcrash: a candy war-scythe. A long crescent blade sweeps out from the top of the shaft: an
ivory spine, a crimson body and a cutting edge that glows pink, shimmering along its length. A
silver collar with a crimson gem holds it to a glossy candy-striped shaft with a silver ferrule.
"""
import math

from pixel import Art, bezier, dome, inside, line_dist, ramp

IVORY = ramp('#6e5550', '#a8888a', '#d6bdbd', '#f2e2df', '#fff6f2', '#ffffff')
CRIMSON = ramp('#2c0310', '#55071b', '#850d29', '#b5153a', '#dc2a50', '#f4607e')
PINK = ramp('#7a1046', '#c0266f', '#ff5aa5', '#ff9ccb', '#ffd6ea', '#ffffff')
CANDY_RED = ramp('#3d0410', '#7a0a20', '#b3122f', '#e02a45', '#ff6a7e')
CANDY_WHITE = ramp('#6b5e64', '#a99aa2', '#d9cfd4', '#f6f0f2', '#ffffff')
SILVER = ramp('#22252c', '#454a55', '#6f7683', '#9ea6b3', '#ccd3dc', '#f2f5f9')
GEM = ramp('#3a0310', '#7a0a20', '#c8162e', '#ff5068', '#ffb3bd', '#ffffff')

# the blade: an outer (top) curve and an inner (cutting) curve meeting in the point
OUTER = bezier((8.8, 14.5), (7.4, 16.55), (3.2, 16.2), (1.0, 11.1), 40)
INNER = bezier((1.0, 11.1), (2.9, 12.9), (5.9, 13.25), (8.05, 12.85), 40)
BLADE = OUTER + INNER[1:]


def build():
    art = Art()

    def stripes(x, y):
        return (y * 1.35 + (x - 8) * 1.1) % 1.0 < 0.5

    rod = lambda x, y: math.sqrt(max(0.0, 1 - ((x - 8) / 0.46) ** 2))
    art.add('shaft white', lambda x, y: abs(x - 8) <= 0.42 and 0.85 <= y <= 12.8 and not stripes(x, y), rod,
            CANDY_WHITE, depth=4, relief=2.4, shine=0.9)
    art.add('shaft red', lambda x, y: abs(x - 8) <= 0.42 and 0.85 <= y <= 12.8 and stripes(x, y), rod,
            CANDY_RED, depth=4, relief=2.4, shine=0.9, shadow=False)
    art.add('ferrule', lambda x, y: abs(x - 8) <= 0.55 and 0.35 <= y <= 0.9,
            lambda x, y: math.sqrt(max(0.0, 1 - ((x - 8) / 0.58) ** 2)), SILVER, depth=5, relief=2.2, shine=0.8)
    art.add('tip', lambda x, y: 0.0 <= y < 0.35 and abs(x - 8) <= 0.42 * y / 0.35, rod, SILVER, depth=4,
            relief=2.2, shine=0.8)

    def blade_tone(x, y):
        d_out = line_dist(OUTER, x, y)
        d_in = line_dist(INNER, x, y)
        q = d_out / max(d_out + d_in, 1e-6)              # 0 at the spine .. 1 at the cutting edge
        return q

    def in_blade(x, y):
        return inside(BLADE, x, y)

    def band(lo, hi):
        def mask(x, y):
            if not in_blade(x, y):
                return False
            d_out = line_dist(OUTER, x, y)
            d_in = line_dist(INNER, x, y)
            q = d_out / max(d_out + d_in, 1e-6)
            return lo <= q < hi
        return mask

    spine_h = lambda x, y: 0.5
    art.add('spine', band(0.0, 0.34), spine_h, IVORY, depth=4,
            tone=lambda x, y: 0.95 - blade_tone(x, y) * 1.1)
    art.add('body', band(0.34, 0.74), spine_h, CRIMSON, depth=3,
            tone=lambda x, y: 0.85 - (blade_tone(x, y) - 0.34) * 1.3, shadow=False)
    art.add('cutting edge', band(0.74, 1.01), spine_h, PINK, depth=2, shadow=False,
            glow=lambda x, y, t: 0.45 + 0.4 * max(0.0, math.sin(2 * math.pi * (t * 1.5 + x * 0.16))) ** 2
            + 0.25 * blade_tone(x, y))
    # collar with a crimson gem
    art.add('collar', lambda x, y: abs(x - 8) <= 0.66 and 12.75 <= y <= 14.55,
            lambda x, y: math.sqrt(max(0.0, 1 - ((x - 8) / 0.7) ** 2)), SILVER, depth=6, relief=2.2, shine=0.8)
    art.add('gem', lambda x, y: math.hypot(x - 8, y - 13.65) < 0.4, dome(7.92, 13.73, 0.44), GEM, depth=7,
            relief=3.0, shine=1.0)
    return art, 3.0
