"""Each legendary's own tooltip: a dark background tinted in the weapon's colours and a thin
two-tone frame that shades from one colour to another, with small gems in the corners. Used
through the item's tooltip_style (1.21.2+) as legendary:<weapon>.

The sprites follow the vanilla tooltip's layout (100 x 100, nine-sliced): the background fills
from pixel 8 inwards, the frame is a 2 pixel line just outside it with cut corners.
"""
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), 'models'))
from paint import fbm, hexrgb, mix  # noqa: E402

SIZE = 100

# weapon: (background top, background bottom, frame top, frame bottom, corner gem)
STYLES = {
    'katana': ('#1a0609', '#08030a', '#ff3352', '#6a0a18', '#ffd36b'),
    'candycane': ('#22091a', '#0e050b', '#ff9ad2', '#ff2d55', '#ffffff'),
    'crush': ('#0a1424', '#04070e', '#82d6ff', '#1f4f8a', '#e8b878'),
    'reaper': ('#0d0a1c', '#05030a', '#5affd2', '#4a2a7a', '#ece3cb'),
}


def _byte(c, a=255):
    return tuple(int(round(255 * max(0.0, min(1.0, v)))) for v in c) + (a,)


def background(name):
    top, bottom, accent, _, _ = STYLES[name]
    rows = []
    for y in range(SIZE):
        row = []
        for x in range(SIZE):
            inside = 8 <= x <= 91 and 8 <= y <= 91 and not ((x in (8, 91)) and (y in (8, 91)))
            if not inside:
                row.append((0, 0, 0, 0))
                continue
            t = (y - 8) / 83
            c = mix(hexrgb(top), hexrgb(bottom), t)
            c = tuple(v * (0.92 + 0.16 * fbm(x * 0.15, y * 0.15, 3)) for v in c)
            edge = min(x - 8, 91 - x, y - 8, 91 - y)
            c = mix(c, hexrgb(accent), 0.10 * max(0.0, 1 - edge / 5))      # a faint glow at the edge
            row.append(_byte(c, 242))
        rows.append(row)
    return rows


def frame(name):
    _, _, top, bottom, gem = STYLES[name]
    rows = [[(0, 0, 0, 0)] * SIZE for _ in range(SIZE)]
    lo, hi = 7, 92

    def colour(y, outer):
        c = mix(hexrgb(top), hexrgb(bottom), max(0.0, min(1.0, (y - lo) / (hi - lo))))
        return c if outer else mix(c, (0.0, 0.0, 0.0), 0.35)

    for y in range(lo, hi + 1):
        for x in range(lo, hi + 1):
            on_side = x in (lo, lo + 1, hi - 1, hi)
            on_top = y in (lo, lo + 1, hi - 1, hi)
            if not (on_side or on_top):
                continue
            # cut corners, like the vanilla frame
            if (x in (lo, hi) and y in (lo, lo + 1, hi - 1, hi)) or (y in (lo, hi) and x in (lo, lo + 1, hi - 1, hi)):
                continue
            outer = x in (lo, hi) or y in (lo, hi)
            rows[y][x] = _byte(colour(y, outer))
    # a small gem on each corner
    for cx, cy in ((lo + 1, lo + 1), (hi - 1, lo + 1), (lo + 1, hi - 1), (hi - 1, hi - 1)):
        for dx, dy, shade in ((0, 0, 1.0), (1, 0, 0.7), (-1, 0, 0.7), (0, 1, 0.7), (0, -1, 0.7)):
            x, y = cx + dx, cy + dy
            if 0 <= x < SIZE and 0 <= y < SIZE:
                rows[y][x] = _byte(mix(colour(y, True), hexrgb(gem), shade))
    return rows


def meta(border):
    return {'gui': {'scaling': {'type': 'nine_slice', 'width': SIZE, 'height': SIZE, 'border': border,
                                'stretch_inner': True}}}
