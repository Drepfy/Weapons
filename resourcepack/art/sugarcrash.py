"""Sugarcrash: a crescent blade grown out of a candy-striped grip. Ivory back, crimson inner
layers and a pink glow along the cutting edge; slim silver crossguard with a crimson gem.
"""
import math
from pix import Sprite

PAL = {
    'k': '#140b10', 'q': '#2b0512', 'v': '#3d0a24',          # outlines: dark / crimson / glow
    'W': '#fff6f2', 'w': '#ecd9d6', 'u': '#c9aeae',            # ivory
    'R': '#d0173a', 'r': '#95102b', 'x': '#5c0a1d', 'c': '#ff5a72',   # crimson
    'P': '#ff9fd0', 'Q': '#ff6fb3',                           # pink glow (cutting edge)
    'H': '#d4dbe5', 'S': '#9aa4b4', 'D': '#5a6170',            # silver fittings
    'B': '#2a1f26', 'b': '#3e2f38',                            # dark leather
}
OUT = {k: 'k' for k in 'WwuHSDBb'}
OUT.update({k: 'q' for k in 'Rrxc'})
OUT.update({k: 'v' for k in 'PQ'})

def bezier(p0, p1, p2, p3, t):
    a = (1 - t) ** 3
    b = 3 * (1 - t) ** 2 * t
    c = 3 * (1 - t) * t ** 2
    d = t ** 3
    return (a * p0[0] + b * p1[0] + c * p2[0] + d * p3[0], a * p0[1] + b * p1[1] + c * p2[1] + d * p3[1])

def _rot(p, origin=(12.5, 18.0), angle=24, scale=0.9):
    """Turn the curve so it grows out of the diagonal handle."""
    a = math.radians(angle)
    dx, dy = p[0] - origin[0], p[1] - origin[1]
    return (origin[0] + scale * (dx * math.cos(a) - dy * math.sin(a)),
            origin[1] + scale * (dx * math.sin(a) + dy * math.cos(a)))

CURVE = [_rot(p) for p in [(12.5, 18.0), (10.5, 4.5), (27.5, 0.5), (27.0, 13.0)]]
SAMPLES = [bezier(*CURVE, i / 400) for i in range(401)]

def nearest(x, y):
    best = None
    for i, (px, py) in enumerate(SAMPLES):
        d = (px - x) ** 2 + (py - y) ** 2
        if best is None or d < best[0]:
            best = (d, i)
    i = best[1]
    t = i / 400
    px, py = SAMPLES[i]
    qx, qy = SAMPLES[min(400, i + 1)] if i < 400 else SAMPLES[i]
    ox, oy = SAMPLES[max(0, i - 1)]
    tx, ty = qx - ox, qy - oy
    # signed distance: positive on the outer (convex) side of the curve
    cross = tx * (y - py) - ty * (x - px)
    return t, math.sqrt(best[0]), cross

def make():
    sp = Sprite(PAL, OUT)
    for y in range(32):
        for x in range(32):
            t, dist, cross = nearest(x + 0.5, y + 0.5)
            # a crescent: swells through the middle, sharp at the point
            half = 2.6 + 1.2 * math.sin(math.pi * min(t / 0.75, 1.0)) if t < 0.62 else \
                3.7 * max(0.0, 1 - ((t - 0.62) / 0.36)) ** 1.35
            if half <= 0.3 or dist > half or t >= 0.98:
                continue
            side = dist if cross > 0 else -dist       # negative = outer (spine) side
            rel = side / max(half, 0.01)              # -1 outer .. +1 inner
            # layers from the thick ivory spine down to the thin glowing edge
            if rel < -0.45:
                k, d = 'W', 3
            elif rel < -0.12:
                k, d = ('w' if t < 0.6 else 'u'), 3
            elif rel < 0.12:
                k, d = 'x', 2                        # dark inlay between ivory and crimson
            elif rel < 0.45:
                k, d = 'R', 2
            elif rel < 0.75:
                k, d = 'r', 2
            else:
                k, d = ('P' if 0.2 < t < 0.85 else 'Q'), 1
            sp.set(x, y, k, d)

    def fn(s, o, x, y):
        if 1 <= s <= 2.5 and abs(o) <= {1: 1, 1.5: 2, 2: 3, 2.5: 2}[s]:   # pommel: silver cap, crimson stone
            if (s, o) in ((2, -1), (2, 1), (1.5, 0)):
                return ('c' if o == -1 else ('R' if o == 0 else 'r')), 5
            return ('H' if o < 0 else ('S' if o <= 1 else 'D')), 4
        if 3 <= s <= 8.5 and -2 <= o <= 2:                      # ivory grip, raised crimson spiral
            band = (int(2 * s) + o) % 4
            if band in (0, 1):
                return ('c' if o <= -1 else ('R' if o <= 1 else 'r')), 4
            return ('W' if o <= -1 else ('w' if o <= 1 else 'u')), 3
        a = abs(o)
        if 9 <= s <= 11.5 and a <= 7:                           # silver crossguard, curled tips, crimson gem
            if a <= 1 and s <= 10.5:
                return {(-1, 9.5): 'c', (0, 10): 'c', (1, 10.5): 'R', (0, 9): 'R', (1, 9.5): 'r',
                        (-1, 10.5): 'R'}.get((o, s), 'R'), 5
            if a <= 5 and s <= 10:
                return (('W' if s == 10 else 'H') if o < 0 else ('S' if s == 10 else 'D')), 3
            if a == 6 and 9.5 <= s <= 11:
                return (('W' if s >= 10.5 else 'H') if o < 0 else ('S' if s >= 10.5 else 'D')), 2
            if a == 7 and 10.5 <= s <= 11.5:
                return ('W' if o < 0 else 'S'), 2
        return None

    sp.paint(fn)
    for (x, y), ch in {(10, 19): 'W', (11, 20): 'H', (12, 20): 'S', (12, 21): 'D'}.items():
        sp.set(x, y, ch, 3)                                    # collar into the blade
    sp.center()
    sp.outline()
    return sp
