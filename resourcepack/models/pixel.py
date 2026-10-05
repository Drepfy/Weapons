"""Painting a weapon as detailed pixel art (128 x 128) from shapes and height maps.

A weapon is a stack of parts (blade, edge, guard, grip, gem...). Each part has an outline (a
mask), a height map (how its surface bulges: a ridge down a blade, a dome on a gem, bands on a
grip), a colour ramp from dark to light, and a thickness for the 3D model. The light falls from
the left (top-left once the weapon is turned onto the diagonal in the inventory); each pixel's
brightness comes from its slope and is rounded to one of the ramp's colours, which gives crisp,
consistent pixel-art shading. Silhouettes get a dark outline and parts cast a shadow on the
ones under them. Glowing parts are not shaded but animated.

Coordinates are model units: x 0..16 left to right, y 0..16 bottom to top (8 pixels per unit).
"""
import math

SIZE = 128
PX = SIZE / 16.0                                     # pixels per model unit
LIGHT = (-0.82, 0.32, 0.74)                          # from the left and a little above, towards the viewer


def hexrgb(h):
    h = h.lstrip('#')
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def ramp(*hexes):
    return [hexrgb(h) for h in hexes]


def _norm(v):
    n = math.sqrt(sum(c * c for c in v)) or 1.0
    return tuple(c / n for c in v)


class Part:
    def __init__(self, name, mask, height, colours, depth, relief=3.0, glow=None, outline=True, shine=0.0,
                 shadow=True, detail=None, tone=None):
        self.name = name
        self.mask = mask              # (x, y) -> bool
        self.height = height          # (x, y) -> 0..1
        self.colours = colours        # dark -> light
        self.depth = depth            # thickness in pixels (1 pixel = 1/8 unit)
        self.relief = relief          # how strongly the height map tilts the surface
        self.glow = glow              # (x, y, t) -> 0..1 for glowing parts, t = 0..1 through the animation
        self.outline = outline
        self.shine = shine            # extra highlight on surfaces facing the light (metal, gems)
        self.shadow = shadow          # casts a shadow on the parts below it
        self.detail = detail          # (x, y) -> -1..1, nudges the colour a step (grain, scratches)
        self.tone = tone              # (x, y) -> 0..1: the colour chosen directly instead of from the slope


class Art:
    def __init__(self):
        self.parts = []

    def add(self, *args, **kw):
        p = Part(*args, **kw)
        self.parts.append(p)
        return p

    def paint(self, frames=1):
        """Returns (frames: list of 128x128 RGBA rows, depth grid, glow grid)."""
        n = SIZE
        owner = [[-1] * n for _ in range(n)]
        hgt = [[0.0] * n for _ in range(n)]
        for k, part in enumerate(self.parts):
            for j in range(n):
                y = 16 - (j + 0.5) / PX
                for i in range(n):
                    x = (i + 0.5) / PX
                    if part.mask(x, y):
                        owner[j][i] = k
                        hgt[j][i] = part.height(x, y)
        depth = [[self.parts[owner[j][i]].depth if owner[j][i] >= 0 else 0 for i in range(n)] for j in range(n)]
        glowing = [[owner[j][i] >= 0 and self.parts[owner[j][i]].glow is not None for i in range(n)] for j in range(n)]
        lx, ly, lz = _norm(LIGHT)
        hx, hy, hz = _norm((lx, ly, lz + 1.0))       # half vector for highlights
        base = [[None] * n for _ in range(n)]
        for j in range(n):
            for i in range(n):
                k = owner[j][i]
                if k < 0:
                    continue
                part = self.parts[k]
                if part.glow is not None:
                    continue
                cols = part.colours
                # slope from neighbours of the same part
                def h(ii, jj):
                    if 0 <= ii < n and 0 <= jj < n and owner[jj][ii] == k:
                        return hgt[jj][ii]
                    return hgt[j][i]
                dx = (h(i + 1, j) - h(i - 1, j)) * 0.5 * part.relief
                dy = (h(i, j - 1) - h(i, j + 1)) * 0.5 * part.relief      # rows go down, y goes up
                nx, ny, nz = _norm((-dx, -dy, 1.0))
                diffuse = max(0.0, nx * lx + ny * ly + nz * lz)
                v = 0.1 + 0.8 * diffuse
                if part.shine:
                    v += part.shine * max(0.0, nx * hx + ny * hy + nz * hz) ** 24
                if part.tone:
                    v = part.tone((i + 0.5) / PX, 16 - (j + 0.5) / PX)
                idx = v * (len(cols) - 1)
                if part.detail:
                    idx += part.detail((i + 0.5) / PX, 16 - (j + 0.5) / PX) * 0.9
                idx = int(round(idx))
                # outline and cast shadows
                edge = False
                shade = 0
                for di, dj in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    ii, jj = i + di, j + dj
                    o = owner[jj][ii] if 0 <= ii < n and 0 <= jj < n else -1
                    if o < 0:
                        edge = True
                    elif o > k and self.parts[o].shadow:
                        # a part on top of this one: shadow if the light comes from it
                        if (di, dj) in ((-1, 0), (0, -1)):
                            shade = max(shade, 2)
                        else:
                            shade = max(shade, 1)
                if edge and part.outline:
                    idx = 0
                else:
                    idx = max(1 if part.outline else 0, min(len(cols) - 1, idx - shade))
                base[j][i] = cols[idx]
        out = []
        for f in range(frames):
            t = f / frames
            rows = []
            for j in range(n):
                row = []
                y = 16 - (j + 0.5) / PX
                for i in range(n):
                    k = owner[j][i]
                    if k < 0:
                        row.append(None)
                        continue
                    part = self.parts[k]
                    if part.glow is None:
                        row.append(base[j][i] + (255,))
                        continue
                    g = min(1.0, max(0.0, part.glow((i + 0.5) / PX, y, t)))
                    cols = part.colours
                    row.append(cols[min(len(cols) - 1, int(g * len(cols)))] + (255,))
                rows.append(row)
            out.append(rows)
        return out, depth, glowing


# ---- shapes ------------------------------------------------------------------------------------------

def inside(poly, x, y):
    c = False
    m = len(poly)
    for a in range(m):
        (x1, y1), (x2, y2) = poly[a], poly[(a + 1) % m]
        if (y1 > y) != (y2 > y) and x < (x2 - x1) * (y - y1) / (y2 - y1) + x1:
            c = not c
    return c


def seg_dist(px, py, a, b):
    (x1, y1), (x2, y2) = a, b
    dx, dy = x2 - x1, y2 - y1
    t = max(0.0, min(1.0, ((px - x1) * dx + (py - y1) * dy) / float(dx * dx + dy * dy or 1)))
    return math.hypot(px - x1 - t * dx, py - y1 - t * dy)


def line_dist(pts, x, y):
    return min(seg_dist(x, y, pts[a], pts[a + 1]) for a in range(len(pts) - 1))


def bezier(p0, p1, p2, p3, n=32):
    out = []
    for a in range(n + 1):
        t = a / n
        u = 1 - t
        out.append((u ** 3 * p0[0] + 3 * u * u * t * p1[0] + 3 * u * t * t * p2[0] + t ** 3 * p3[0],
                    u ** 3 * p0[1] + 3 * u * u * t * p1[1] + 3 * u * t * t * p2[1] + t ** 3 * p3[1]))
    return out


def poly_dist(poly, x, y):
    """Distance to a closed outline."""
    return line_dist(poly + [poly[0]], x, y)


def _hash(ix, iy, seed):
    v = (ix * 374761393 + iy * 668265263 + seed * 2147483647) & 0xFFFFFFFF
    v = ((v ^ (v >> 13)) * 1274126177) & 0xFFFFFFFF
    return ((v ^ (v >> 16)) & 0xFFFF) / 65535.0


def noise(x, y, seed=0):
    ix, iy = math.floor(x), math.floor(y)
    fx, fy = x - ix, y - iy
    fx, fy = fx * fx * (3 - 2 * fx), fy * fy * (3 - 2 * fy)
    a, b = _hash(ix, iy, seed), _hash(ix + 1, iy, seed)
    c, d = _hash(ix, iy + 1, seed), _hash(ix + 1, iy + 1, seed)
    return a + (b - a) * fx + (c - a) * fy + (a - b - c + d) * fx * fy


def dome(cx, cy, r):
    """Height of a round bulge (gems, pommels, rivets)."""
    def h(x, y):
        d = math.hypot(x - cx, y - cy) / r
        return math.sqrt(max(0.0, 1 - d * d))
    return h


def flat(v=0.5):
    return lambda x, y: v


def column(x):
    """The pixel column a point is in (for lines exactly one pixel wide)."""
    return math.floor(x * PX)
