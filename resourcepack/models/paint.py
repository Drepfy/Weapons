"""Smooth painting: colours, light and materials for the weapon textures.

Nothing here is pixel art. Colours blend continuously, every texel is lit from its own slope
(so a rounded grip or a bevelled edge shades smoothly), metal reflects a soft studio light, and
glowing parts bloom onto the surfaces round them. Colours are given as normal (sRGB) hex codes,
the light is worked out in linear space and turned back at the end, so highlights stay clean.

Coordinates are model units: x 0..16 left to right, y 0..16 bottom to top, z towards the viewer.
"""
import math

# ---- colours -------------------------------------------------------------------------------------


def hexrgb(h):
    """'#rrggbb' -> (r, g, b) floats 0..1."""
    h = h.lstrip('#')
    return tuple(int(h[i:i + 2], 16) / 255.0 for i in (0, 2, 4))


def mix(a, b, t):
    return tuple(a[i] + (b[i] - a[i]) * t for i in range(3))


def clamp(v, lo=0.0, hi=1.0):
    return lo if v < lo else hi if v > hi else v


def smooth(e0, e1, x):
    """0 below e0, 1 above e1, a smooth S between."""
    if e0 == e1:
        return 0.0 if x < e0 else 1.0
    t = clamp((x - e0) / (e1 - e0))
    return t * t * (3 - 2 * t)


def ramp(*hexes):
    """A continuous colour gradient: t = 0 the first colour .. t = 1 the last."""
    cols = [hexrgb(h) if isinstance(h, str) else h for h in hexes]
    m = len(cols) - 1

    def at(t):
        t = clamp(t) * m
        k = min(int(t), m - 1)
        return mix(cols[k], cols[k + 1], t - k)
    return at


def lin(c):
    return tuple(v * v * (0.8 + 0.2 * v) for v in c)          # close to sRGB -> linear, cheap


def _to_srgb(v):
    if v <= 0:
        return 0
    # soft shoulder: no harsh clipping of bright highlights
    if v > 0.75:
        v = 0.75 + 0.25 * (1 - math.exp(-(v - 0.75) / 0.25))
    return int(round(255 * min(1.0, v ** (1 / 2.2))))


def finish(c, alpha=1.0):
    """Linear colour -> (r, g, b, a) bytes."""
    return (_to_srgb(c[0]), _to_srgb(c[1]), _to_srgb(c[2]), int(round(255 * clamp(alpha))))


# ---- light ---------------------------------------------------------------------------------------


def norm(v):
    n = math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]) or 1.0
    return (v[0] / n, v[1] / n, v[2] / n)


# the key light comes from the left and a little above (in the inventory, where the weapon lies
# on the diagonal, that is the top left), a cool fill from the right, a bright softbox and a rim
# strip that metal reflects
LIGHT = norm((-0.72, 0.4, 0.58))
FILL = norm((0.75, -0.25, 0.45))
HALF = norm((LIGHT[0], LIGHT[1], LIGHT[2] + 1.0))
BOX = norm((-0.55, 0.55, 0.63))
RIM = norm((0.62, 0.45, 0.64))
AMBIENT, KEY, FILL_K = 0.13, 1.12, 0.26


def env(r):
    """How bright the studio is in the direction r (what polished metal reflects): a dark floor,
    a crisp horizon, a bright sky, a softbox and a rim strip. The sharp horizon is what makes
    polished steel read as mirror-bright rather than grey."""
    y = r[1]
    sky = 0.03 + 0.12 * smooth(-0.7, -0.05, y) + 0.62 * smooth(-0.04, 0.22, y) + 0.1 * smooth(0.4, 0.95, y)
    sky += 0.12 * smooth(-0.2, 0.2, -r[0])
    box = r[0] * BOX[0] + r[1] * BOX[1] + r[2] * BOX[2]
    rim = r[0] * RIM[0] + r[1] * RIM[1] + r[2] * RIM[2]
    return sky + 2.6 * smooth(0.82, 0.95, box) + 1.0 * smooth(0.87, 0.96, rim)


def shade(albedo, n, metal=0.0, gloss=0.3, spec=0.5, sheen=0.0, occl=1.0, coat=0.0):
    """Lights one texel. albedo: sRGB floats; n: unit surface normal. Returns linear RGB.
    metal 0..1 (reflects the studio instead of scattering light), gloss 0..1 (how tight the
    highlight is), spec (how strong), sheen: extra reflected light painted on (streaks), coat
    0..1: a clear glossy coat over the colour that reflects the studio untinted (black glass,
    hard candy, lacquer)."""
    a = lin(albedo)
    nx, ny, nz = n
    nl = nx * LIGHT[0] + ny * LIGHT[1] + nz * LIGHT[2]
    fl = nx * FILL[0] + ny * FILL[1] + nz * FILL[2]
    diffuse = (AMBIENT + KEY * max(0.0, nl) + FILL_K * max(0.0, fl)) * occl
    nh = max(0.0, nx * HALF[0] + ny * HALF[1] + nz * HALF[2])
    power = 6 + 380 * gloss ** 3
    s = spec * (0.25 + gloss) * nh ** power * occl
    reflect = 0.0
    if metal:
        r = (2 * nz * nx, 2 * nz * ny, 2 * nz * nz - 1)
        reflect = (env(r) + sheen) * (0.35 + 0.65 * occl)
    clear = 0.0
    if coat:
        r = (2 * nz * nx, 2 * nz * ny, 2 * nz * nz - 1)
        fresnel = 0.06 + 0.5 * (1 - max(0.0, nz)) ** 5
        clear = coat * (fresnel * 2.2 * env(r) + 0.3 * max(0.0, sheen)) * (0.35 + 0.65 * occl)
    out = []
    for c in a:
        v = c * diffuse * (1 - metal) + c * reflect * metal
        v += s * ((1 - metal) + metal * (0.35 + 0.65 * c))
        out.append(v + clear)
    return out


# ---- shapes --------------------------------------------------------------------------------------


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


def line_pos(pts, x, y):
    """(distance to a polyline, how far along it the nearest point is: 0..1)."""
    total = sum(math.hypot(pts[a + 1][0] - pts[a][0], pts[a + 1][1] - pts[a][1]) for a in range(len(pts) - 1))
    best, where, run = 1e9, 0.0, 0.0
    for a in range(len(pts) - 1):
        (x1, y1), (x2, y2) = pts[a], pts[a + 1]
        dx, dy = x2 - x1, y2 - y1
        ln = math.hypot(dx, dy) or 1e-9
        t = max(0.0, min(1.0, ((x - x1) * dx + (y - y1) * dy) / (ln * ln)))
        d = math.hypot(x - x1 - t * dx, y - y1 - t * dy)
        if d < best:
            best, where = d, (run + t * ln) / total
        run += ln
    return best, where


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
    """Smooth value noise, 0..1."""
    ix, iy = math.floor(x), math.floor(y)
    fx, fy = x - ix, y - iy
    fx, fy = fx * fx * (3 - 2 * fx), fy * fy * (3 - 2 * fy)
    a, b = _hash(ix, iy, seed), _hash(ix + 1, iy, seed)
    c, d = _hash(ix, iy + 1, seed), _hash(ix + 1, iy + 1, seed)
    return a + (b - a) * fx + (c - a) * fy + (a - b - c + d) * fx * fy


def fbm(x, y, seed=0, octaves=4):
    """Layered noise (clouds, grain, wear), 0..1."""
    v, amp, total = 0.0, 1.0, 0.0
    for o in range(octaves):
        v += noise(x, y, seed + o * 7) * amp
        total += amp
        x, y, amp = x * 2.03, y * 2.03, amp * 0.5
    return v / total


# ---- grids ---------------------------------------------------------------------------------------


def blur(grid, r, passes=3):
    """A soft (nearly Gaussian) blur of a 2D list of floats, radius r cells; returns a new grid."""
    if r < 1:
        return [row[:] for row in grid]
    h, w = len(grid), len(grid[0])
    g = [row[:] for row in grid]
    k = 2 * r + 1
    for _ in range(passes):
        for row in g:                                   # horizontal
            acc = sum(row[0:r + 1]) + row[0] * r
            out = [0.0] * w
            for i in range(w):
                out[i] = acc / k
                acc += row[min(w - 1, i + r + 1)] - row[max(0, i - r)]
            row[:] = out
        for i in range(w):                              # vertical
            col = [g[j][i] for j in range(h)]
            acc = sum(col[0:r + 1]) + col[0] * r
            for j in range(h):
                g[j][i] = acc / k
                acc += col[min(h - 1, j + r + 1)] - col[max(0, j - r)]
    return g


def distance(mask):
    """For each True cell of a 2D bool grid: the distance (in cells) to the nearest False cell,
    measured to its edge (0.5 for a cell on the border). False cells get 0."""
    h, w = len(mask), len(mask[0])
    big = 1e9
    d = [[big if mask[j][i] else 0.0 for i in range(w)] for j in range(h)]
    r2 = math.sqrt(2)
    for j in range(h):
        row, up = d[j], d[j - 1] if j else None
        for i in range(w):
            if row[i]:
                v = row[i]
                if i:
                    v = min(v, row[i - 1] + 1)
                else:
                    v = min(v, 1.0)
                if up is not None:
                    v = min(v, up[i] + 1, (up[i - 1] if i else 0.0) + r2, (up[i + 1] if i + 1 < w else 0.0) + r2)
                else:
                    v = min(v, 1.0)
                row[i] = v
    for j in range(h - 1, -1, -1):
        row, dn = d[j], d[j + 1] if j + 1 < h else None
        for i in range(w - 1, -1, -1):
            if row[i]:
                v = row[i]
                if i + 1 < w:
                    v = min(v, row[i + 1] + 1)
                else:
                    v = min(v, 1.0)
                if dn is not None:
                    v = min(v, dn[i] + 1, (dn[i - 1] if i else 0.0) + r2, (dn[i + 1] if i + 1 < w else 0.0) + r2)
                else:
                    v = min(v, 1.0)
                row[i] = v
    for row in d:
        for i in range(w):
            if row[i]:
                row[i] -= 0.5
    return d
