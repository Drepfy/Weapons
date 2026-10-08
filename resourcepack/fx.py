"""The abilities' effect textures and models (the Katana's glint and cuts, the Sugar Trap's goo,
the ground a Crush breaks, the Reaper's spectral scythe), painted in code like the weapons. The plugin shows them with display entities
(legendary:fx/<name>) and animates them.

Kinds of model:
    flat     lying on the ground, seen from above (texture top = north, bottom = the way it faces)
    upright  standing, facing the viewer (south); billboards always turn to face the camera

    python3 fx.py out.png      a contact sheet of every effect
"""
import math
import os
import random
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), 'models'))
from paint import bezier, clamp, fbm, hexrgb, line_pos, mix, noise, seg_dist, smooth  # noqa: E402

TAU = math.pi * 2


def _paint(size, shade, ss=2):
    """size x size RGBA rows from shade(u, v) -> (r, g, b, a) floats (u, v from -1 to 1,
    v growing downwards), supersampled."""
    rows = []
    for j in range(size):
        row = []
        for i in range(size):
            acc = [0.0, 0.0, 0.0, 0.0]
            for sj in range(ss):
                for si in range(ss):
                    u = ((i + (si + 0.5) / ss) / size) * 2 - 1
                    v = ((j + (sj + 0.5) / ss) / size) * 2 - 1
                    r, g, b, a = shade(u, v)
                    a = clamp(a)
                    acc[0] += r * a
                    acc[1] += g * a
                    acc[2] += b * a
                    acc[3] += a
            n = ss * ss
            alpha = acc[3] / n
            if alpha <= 0.004:
                row.append((0, 0, 0, 0))
                continue
            row.append(tuple(int(round(255 * clamp(acc[q] / acc[3]))) for q in range(3)) + (int(round(255 * alpha)),))
        rows.append(row)
    return rows


def _dilate(px, r):
    """Thicker lines: every texel takes the most solid texel within r of it (across, then down)."""
    n = len(px)
    across = []
    for row in px:
        out = []
        for x in range(n):
            best = row[x]
            for k in range(max(0, x - r), min(n, x + r + 1)):
                if row[k][3] > best[3]:
                    best = row[k]
            out.append(best)
        across.append(out)
    down = [[None] * n for _ in range(n)]
    for x in range(n):
        for y in range(n):
            best = across[y][x]
            for k in range(max(0, y - r), min(n, y + r + 1)):
                if across[k][x][3] > best[3]:
                    best = across[k][x]
            down[y][x] = best
    return down


def _blur(px, r):
    """A soft blur (two box blurs each way) of colour weighted by alpha: (r, g, b, a) floats."""
    n = len(px)
    grid = [[(p[0] * p[3], p[1] * p[3], p[2] * p[3], p[3]) for p in row] for row in px]

    def box(line):
        out, acc, w = [], [0.0] * 4, 2 * r + 1
        padded = [line[0]] * r + line + [line[-1]] * r
        for k in range(w):
            for q in range(4):
                acc[q] += padded[k][q]
        for x in range(n):
            out.append(tuple(a / w for a in acc))
            if x + w < len(padded):
                for q in range(4):
                    acc[q] += padded[x + w][q] - padded[x][q]
        return out

    for _ in range(2):
        grid = [box(row) for row in grid]
        cols = [box([grid[y][x] for y in range(n)]) for x in range(n)]
        grid = [[cols[x][y] for x in range(n)] for y in range(n)]
    return grid


def bold(rows, grow=0, halo=0.0, spread=0, solid=1.0):
    """Easier to see in daylight and from afar: lines thickened by grow texels, a soft glow of
    their own colour spread texels wide round them (at halo strength), and faint parts made more
    solid (alpha -> 1 - (1 - alpha) ** solid)."""
    px = [[(p[0] / 255, p[1] / 255, p[2] / 255, p[3] / 255) for p in row] for row in rows]
    if grow:
        px = _dilate(px, grow)
    if halo and spread:
        glow = _blur(px, spread)
        mixed = []
        for row, grow_row in zip(px, glow):
            out = []
            for (r, g, b, a), (gr, gg, gb, ga) in zip(row, grow_row):
                ha = clamp(ga * halo)
                if ga > 1e-6 and ha > 0:
                    oa = a + ha * (1 - a)
                    k = ha * (1 - a)
                    out.append(((r * a + gr / ga * k) / oa, (g * a + gg / ga * k) / oa, (b * a + gb / ga * k) / oa, oa))
                else:
                    out.append((r, g, b, a))
            mixed.append(out)
        px = mixed
    result = []
    for row in px:
        line = []
        for r, g, b, a in row:
            a = 1 - (1 - clamp(a)) ** solid
            line.append((0, 0, 0, 0) if a <= 0.004 else
                        (int(round(255 * clamp(r))), int(round(255 * clamp(g))), int(round(255 * clamp(b))), int(round(255 * a))))
        result.append(line)
    return result


def _glow(d, width):
    return math.exp(-(d / width) ** 2)


def _ramp(t, stops):
    """Colour along a list of (position, hex) stops."""
    t = clamp(t)
    for k in range(len(stops) - 1):
        p0, c0 = stops[k]
        p1, c1 = stops[k + 1]
        if t <= p1:
            return mix(hexrgb(c0), hexrgb(c1), (t - p0) / max(1e-6, p1 - p0))
    return hexrgb(stops[-1][1])


def _polar(u, v):
    return math.hypot(u, v), math.atan2(v, u)


# ---- shading -----------------------------------------------------------------------------------------

# Light for the shaded effects lying on the ground (goo, broken ground): from high in the north
# (the top of the texture); the plugin only turns them a little, so it stays from about there.
_LIGHT = (0.0, -0.5, 0.866)
_HALF = (0.0, -0.259, 0.966)
# ...and for upright ones (seen side on): from above and to the left, in front.
_LIGHT_UP = (-0.45, -0.6, 0.66)
_HALF_UP = (-0.25, -0.33, 0.91)


def _normal(height, u, v, eps, strength):
    """The surface normal of a height field at (u, v)."""
    h = height(u, v)
    dx = (height(u + eps, v) - h) / eps * strength
    dy = (height(u, v + eps) - h) / eps * strength
    n = math.sqrt(dx * dx + dy * dy + 1)
    return h, (-dx / n, -dy / n, 1 / n)


def _dot(a, b):
    return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]


def _norm3(x, y, z):
    n = math.sqrt(x * x + y * y + z * z) or 1.0
    return x / n, y / n, z / n


def _smax(a, b, k):
    """A smooth maximum: shapes that meet flow into each other like liquid."""
    h = clamp(0.5 + 0.5 * (a - b) / k)
    return b + (a - b) * h + k * h * (1 - h)


# ---- Katana -------------------------------------------------------------------------------------------


def katana_glint(u, v):
    """The blade catching the light as it is drawn: a fine bright line with a four-pointed star at
    its heart, white in the core and fringed crimson."""
    r = math.hypot(u, v)
    taper = clamp(1 - (abs(u) / 0.98) ** 1.5)
    line = _glow(v, 0.004 + 0.02 * taper ** 2) * taper
    reach = clamp(1 - (abs(v) / 0.62) ** 1.3)
    spike = _glow(u, 0.004 + 0.016 * reach ** 2) * reach
    diag = 0.0
    for s in (1, -1):
        along = (u + s * v) / math.sqrt(2)
        across = (u - s * v) / math.sqrt(2)
        k = clamp(1 - (abs(along) / 0.3) ** 1.2)
        diag = max(diag, 0.7 * _glow(across, 0.003 + 0.01 * k) * k)
    core = _glow(r, 0.05)
    white = clamp(line + spike + diag + core)
    fringe = 0.55 * _glow(v, 0.03 + 0.07 * taper) * taper + 0.45 * _glow(r, 0.2)
    c = _ramp(white, [(0, '#b0071f'), (0.3, '#ff2b4e'), (0.65, '#ffc6d0'), (1, '#ffffff')])
    return c + (clamp(white + fringe),)


def katana_cut(u, v):
    """One sword cut as it is made: a razor-thin, white-hot line along a faint curve, needle-sharp
    at both ends and brightest towards the end the blade finished at (the right), with a thin
    crimson trail just behind it. The plugin shows it about 4.5 blocks wide and only a quarter of
    a block tall, so the curve here is far deeper than it looks in game, and every thickness is
    measured straight up and down (that is what stays thin when it is squashed)."""
    s = (u + 0.97) / 1.94                                # 0..1 along the cut
    if not 0.0 < s < 1.0:
        return (0.0, 0.0, 0.0, 0.0)
    dv = v - (0.42 - 0.84 * u * u)                       # < 0 behind the cut (above), > 0 in front
    reach = math.sin(math.pi * s) ** 0.55 * (0.35 + 0.65 * s ** 0.8)   # thin needle ends; the tail fades
    core = _glow(dv, 0.008 + 0.06 * reach) * reach
    edge = 0.6 * _glow(dv, 0.03 + 0.13 * reach) * reach
    trail = 0.0
    deep = 0.5 * reach
    if -deep < dv < 0.0:
        q = -dv / deep
        streaks = 0.5 + 0.5 * fbm(s * 2.6 + 3.0, q * 7.0, 51)
        trail = (1 - q) ** 2.2 * reach * streaks * 0.75
    c = mix(hexrgb('#b8001c'), hexrgb('#ff2448'), clamp(edge * 1.6))
    c = mix(c, (1.0, 1.0, 1.0), clamp(core * 1.5) ** 1.5)
    return c + (clamp(max(core, edge, trail)),)


# ---- Candy Cane ----------------------------------------------------------------------------------------


def _puddle_edge(a):
    """How far the puddle reaches at an angle: a lumpy, lopsided outline."""
    return (0.64 + 0.07 * math.sin(2 * a + 0.6) + 0.05 * math.sin(3 * a + 2.1) + 0.03 * math.sin(5 * a + 0.3)
            + 0.08 * (fbm(math.cos(a) * 1.7 + 3.0, math.sin(a) * 1.7 + 3.0, 41) - 0.5))


_EDGE = [_puddle_edge(k / 720 * TAU) for k in range(721)]


def _edge_at(a):
    f = (a % TAU) / TAU * 720
    k = int(f)
    return _EDGE[k] + (_EDGE[k + 1] - _EDGE[k]) * (f - k)


def _splash():
    """Where the goo splashed out from the puddle: arms reaching out, each ending in a drop, and a
    few loose drops beyond."""
    rnd = random.Random(17)
    arms = []
    for k in range(6):
        a = k / 6 * TAU + rnd.uniform(-0.35, 0.35)
        e = _edge_at(a)
        reach = rnd.uniform(0.1, 0.2)
        bend = rnd.uniform(-0.2, 0.2)
        p0 = (math.cos(a) * (e - 0.06), math.sin(a) * (e - 0.06))
        p1 = (math.cos(a + bend) * (e + reach), math.sin(a + bend) * (e + reach))
        arms.append((p0, p1, rnd.uniform(0.035, 0.05), rnd.uniform(0.04, 0.055)))
    drops = []
    for k in range(5):
        a = rnd.uniform(0, TAU)
        e = min(0.93, _edge_at(a) + rnd.uniform(0.12, 0.24))
        drops.append((math.cos(a) * e, math.sin(a) * e, rnd.uniform(0.022, 0.04)))
    return arms, drops


_ARMS, _DROPS = _splash()


def _scatter(seed, count, keep, size):
    """Points spread over the puddle (not too near its edge): (x, y, size, angle, which)."""
    rnd = random.Random(seed)
    out = []
    while len(out) < count:
        x, y = rnd.uniform(-0.75, 0.75), rnd.uniform(-0.75, 0.75)
        if math.hypot(x, y) < _edge_at(math.atan2(y, x)) - keep and all(
                math.hypot(x - p[0], y - p[1]) > 0.12 for p in out):
            out.append((x, y, rnd.uniform(*size), rnd.uniform(0, TAU), rnd.randrange(3)))
    return out


_BUBBLES = _scatter(23, 8, 0.14, (0.035, 0.07))
_SPRINKLES = _scatter(29, 13, 0.1, (0.04, 0.055))
_SPRINKLE_R = 0.021
_SPRINKLE = ['#fffaf7', '#e3122f', '#ffc2d8']


def _goo(u, v):
    """How deep the goo is at a point (> 0 inside it), and what is on top: ('goo' | 'bubble' |
    'sprinkle', colour)."""
    r, a = _polar(u, v)
    f = _edge_at(a) - r
    for p0, p1, w0, w1 in _ARMS:
        dx, dy = p1[0] - p0[0], p1[1] - p0[1]
        t = clamp(((u - p0[0]) * dx + (v - p0[1]) * dy) / (dx * dx + dy * dy))
        w = w0 + (w1 - w0) * t - 0.018 * math.sin(math.pi * t)            # a waist before the drop
        f = _smax(f, w - math.hypot(u - p0[0] - t * dx, v - p0[1] - t * dy), 0.05)
    for x, y, rad in _DROPS:
        f = max(f, rad - math.hypot(u - x, v - y))
    return f


def _goo_height(u, v):
    f = _goo(u, v)
    if f <= 0:
        return f * 0.4
    h = 0.06 * (1 - math.exp(-f / 0.035)) + 0.012 * fbm(u * 3 + 7, v * 3 + 7, 43) * smooth(0.0, 0.1, f)
    for x, y, rad, _, _ in _BUBBLES:
        d = math.hypot(u - x, v - y)
        if d < rad:
            h += 0.9 * rad * math.sqrt(1 - (d / rad) ** 2)
    for x, y, size, ang, _ in _SPRINKLES:
        along = (u - x) * math.cos(ang) + (v - y) * math.sin(ang)
        across = -(u - x) * math.sin(ang) + (v - y) * math.cos(ang)
        d = math.hypot(max(abs(along) - size, 0.0), across)
        if d < _SPRINKLE_R:
            h += 0.02 * math.sqrt(1 - (d / _SPRINKLE_R) ** 2)
    return h


def _sprinkle_at(u, v):
    for x, y, size, ang, which in _SPRINKLES:
        along = (u - x) * math.cos(ang) + (v - y) * math.sin(ang)
        across = -(u - x) * math.sin(ang) + (v - y) * math.cos(ang)
        if math.hypot(max(abs(along) - size, 0.0), across) < _SPRINKLE_R:
            return _SPRINKLE[which]
    return None


def _bubble_at(u, v):
    for x, y, rad, _, _ in _BUBBLES:
        d = math.hypot(u - x, v - y)
        if d < rad * 1.25:
            return d / rad
    return None


def _glossy(base, n, gloss=90, sheen=0.9, light=_LIGHT, half=_HALF):
    """Wet, glossy candy: lit from above, a sharp white highlight and a soft lighter rim."""
    ndl = max(0.0, _dot(n, light))
    c = tuple(ch * (0.72 + 0.36 * ndl) for ch in base)
    rim = (1 - n[2]) ** 1.5
    c = mix(c, hexrgb('#ffd3e6'), clamp(rim * 0.9))
    spec = max(0.0, _dot(n, half)) ** gloss * sheen + 0.18 * max(0.0, _dot(n, half)) ** 12
    return mix(c, (1.0, 1.0, 1.0), clamp(spec))


def candy_puddle(u, v):
    """The Sugar Trap: a puddle of sticky pink candy goo splashed on the ground, glossy and wet,
    with bubbles in it, splashes reaching out to drops round its edge, and sprinkles on top."""
    f = _goo(u, v)
    if f < -0.01:
        return (0.0, 0.0, 0.0, 0.0)
    h, n = _normal(_goo_height, u, v, 0.006, 1.0)
    depth = 1 - math.exp(-max(f, 0.0) / 0.14)
    base = mix(hexrgb('#ff8cc3'), hexrgb('#e2307f'), depth)
    base = mix(base, hexrgb('#b8145c'), 0.45 * _glow(f, 0.014))          # the darker rim of the goo
    warp = 1.6 * fbm(u * 1.4 + 5, v * 1.4 + 5, 45)
    swirl = fbm(u * 2.4 + warp, v * 2.4 - warp, 46)                    # paler candy marbled through it
    base = mix(base, hexrgb('#ffbfdc'), 0.4 * smooth(0.52, 0.66, swirl) * smooth(0.0, 0.08, f))
    bubble = _bubble_at(u, v)
    if bubble is not None and bubble < 1.0:
        base = mix(base, hexrgb('#ffa6d0'), 0.6 * (1 - bubble))
    sprinkle = _sprinkle_at(u, v)
    if sprinkle:
        base = hexrgb(sprinkle)
    c = _glossy(base, n, 110 if sprinkle else 90)
    alpha = smooth(-0.01, 0.006, f) * (0.88 + 0.12 * depth)
    return c + (alpha,)


# a fat glob, a smaller lump pulling away from it on a neck of goo, and drops strung out behind
_GLOB = [((0.08, 0.14), 0.5), ((-0.36, -0.42), 0.2), ((-0.6, -0.7), 0.085), ((-0.78, -0.9), 0.05),
         ((0.55, -0.45), 0.06), ((0.66, 0.66), 0.045)]
_NECK = ((0.08, 0.14), (-0.36, -0.42), 0.13)


def _dome(d, rad):
    return math.sqrt(max(rad * rad - d * d, 0.0))


def _glob_shape(u, v):
    """How far inside the glob's outline a point is (> 0 inside)."""
    u, v = u + 0.035 * math.sin(3.1 * v + 1), v + 0.035 * math.sin(2.7 * u + 2)      # a little wobble
    f = max(rad - math.hypot(u - x, v - y) for (x, y), rad in _GLOB)
    (x0, y0), (x1, y1), w = _NECK
    return max(f, w - seg_dist(u, v, (x0, y0), (x1, y1)))


def _glob_normal(u, v):
    """The glob's surface: each part is a rounded drop (the neck a rounded tube), and their normals
    blend by how high each stands, so the parts flow into each other without a crease."""
    u, v = u + 0.035 * math.sin(3.1 * v + 1), v + 0.035 * math.sin(2.7 * u + 2)
    parts = [(x, y, rad) for (x, y), rad in _GLOB]
    (x0, y0), (x1, y1), w = _NECK
    dx, dy = x1 - x0, y1 - y0
    t = clamp(((u - x0) * dx + (v - y0) * dy) / (dx * dx + dy * dy))
    parts.append((x0 + t * dx, y0 + t * dy, w))
    nx = ny = nz = 0.0
    for x, y, rad in parts:
        h = _dome(math.hypot(u - x, v - y), rad * 1.04)
        if h <= 0:
            continue
        k = h ** 6
        nx, ny, nz = nx + k * (u - x) / rad, ny + k * (v - y) / rad, nz + k * h / rad
    return _norm3(nx, ny, nz) if nz > 0 else (0.0, 0.0, 1.0)


def candy_goo(u, v):
    """A glob of the goo, flung: a fat wobbling blob with a lump pulling away from it and drops
    strung out behind, glossy like the puddle it splats into."""
    f = _glob_shape(u, v)
    if f < -0.01:
        return (0.0, 0.0, 0.0, 0.0)
    n = _glob_normal(u, v)
    depth = smooth(0.0, 0.35, f)
    base = mix(hexrgb('#ff8cc3'), hexrgb('#e2307f'), depth)
    base = mix(base, hexrgb('#b8145c'), 0.4 * _glow(f, 0.02))
    c = _glossy(base, n, 40, 1.0, _LIGHT_UP, _HALF_UP)
    return c + (smooth(-0.01, 0.008, f),)


_BITS = [((-0.75 + 0.5 * (k % 4) + random.Random(k).uniform(-0.08, 0.08),
           -0.75 + 0.5 * (k // 4) + random.Random(k + 50).uniform(-0.08, 0.08)),
          random.Random(k + 90).uniform(0.15, 0.23)) for k in range(16)]


def _bits(u, v):
    h = 0.0
    for (x, y), rad in _BITS:
        h = max(h, _dome(math.hypot(u - x, v - y), rad))
    return h


def candy_goo_bits(u, v):
    """What the goo's particles show (each one a random quarter of this): little glossy blobs of
    pink goo."""
    f = _bits(u, v)
    if f <= 0.0:
        return (0.0, 0.0, 0.0, 0.0)
    h, n = _normal(_bits, u, v, 0.01, 1.0)
    base = mix(hexrgb('#ff8cc3'), hexrgb('#e2307f'), smooth(0.0, 0.18, f))
    return _glossy(base, n, 30, 1.0, _LIGHT_UP, _HALF_UP) + (smooth(0.0, 0.04, f),)


# ---- Crush ---------------------------------------------------------------------------------------------


def _plates():
    """Where the broken ground's plates are: rings of them round the hollow, bigger further out."""
    rnd = random.Random(31)
    out = []
    for ring, count, radius in ((0, 6, 0.27), (1, 10, 0.5), (2, 14, 0.74), (3, 18, 0.98)):
        for k in range(count):
            a = (k + rnd.uniform(-0.3, 0.3)) / count * TAU + ring * 0.4
            rr = radius + rnd.uniform(-0.05, 0.05)
            # each plate is tipped: down towards the hollow, and a little either way
            tilt = (0.75 - min(rr, 0.75)) * 0.9
            out.append((math.cos(a) * rr, math.sin(a) * rr, -math.cos(a) * tilt + rnd.uniform(-0.25, 0.25),
                        -math.sin(a) * tilt + rnd.uniform(-0.25, 0.25)))
    return out


_PLATES = _plates()


def _crater_edge(a):
    return 0.8 + 0.07 * math.sin(3 * a + 1.3) + 0.1 * (fbm(math.cos(a) * 2 + 9, math.sin(a) * 2 + 9, 61) - 0.5)


_RIM = [_crater_edge(k / 720 * TAU) for k in range(721)]


def crush_crater(u, v):
    """Where a Crush lands: the ground itself broken into tipped plates, sunk round a dark hollow
    where azure light still burns, cracks splitting out between the plates and on past them, and
    grit and dust thrown out in streaks. It darkens and lightens the real ground under it rather
    than covering it."""
    r, a = _polar(u, v)
    edge = _RIM[int((a % TAU) / TAU * 720)]
    # the two nearest plate centres: their bisector is the crack between them
    best = second = 9.0
    plate = None
    for p in _PLATES:
        d = (u - p[0]) ** 2 + (v - p[1]) ** 2
        if d < best:
            best, second, plate = d, best, p
        elif d < second:
            second = d
    best, second = math.sqrt(best), math.sqrt(second)
    gap = (second - best) / 2
    inner = smooth(edge, edge * 0.8, r)                       # 1 in the broken ground, 0 beyond it
    width = (0.006 + 0.022 * max(0.0, 1 - r) ** 1.5) * inner
    crack = smooth(width + 0.004, width * 0.4, gap) * inner
    # cracks running on out past the plates
    rnd = random.Random(21)
    for k in range(9):
        base = k / 9 * TAU + rnd.uniform(-0.3, 0.3)
        reach = edge + rnd.uniform(0.08, 0.2)
        wob = 0.04 * math.sin(r * 9 + k) + 0.025 * math.sin(r * 23 + k * 2)
        da = math.atan2(math.sin(a - base - wob), math.cos(a - base - wob))
        if edge * 0.7 < r < reach:
            w = 0.006 * (1 - (r - edge * 0.7) / (reach - edge * 0.7)) + 0.002
            crack = max(crack, smooth(w + 0.003, w * 0.3, abs(da) * r))
    # the plate's light: tipped plates are darker or lighter, and the side of a plate that is
    # lifted catches a bright edge
    n = _norm3(plate[2], plate[3], 1.0)
    light = (_dot(n, _LIGHT) - 0.93) * 3.0 * inner
    lip = 0.0
    if crack < 0.5 and gap < 0.03 and inner > 0:
        to = _norm3(u - plate[0], v - plate[1], 0.0)
        lip = smooth(0.03, 0.008, gap) * max(0.0, -(to[0] * _LIGHT[0] + to[1] * _LIGHT[1])) * 1.6 * inner
    sink = 0.5 * smooth(0.6, 0.12, r)                          # the crater's bowl, deeper inwards
    hollow = smooth(0.17, 0.12, r + 0.06 * (fbm(math.cos(a) * 2 + 3, math.sin(a) * 2 + 3, 65) - 0.5))
    energy = smooth(0.62, 0.05, r)
    if hollow > 0.01:
        c = mix(hexrgb('#05070c'), hexrgb('#46b8ff'), _glow(r, 0.07) * 0.9)
        c = mix(c, hexrgb('#e4f7ff'), _glow(r, 0.03))
        return c + (clamp(0.8 * hollow + 0.2 + _glow(r, 0.08)),)
    if crack > 0.05:
        c = mix(hexrgb('#06080d'), hexrgb('#2f9bff'), clamp(energy * 1.3))
        c = mix(c, hexrgb('#d6f4ff'), energy ** 2 * crack)
        return c + (clamp(crack * (0.8 + 0.2 * energy)),)
    # dust and grit thrown out in streaks past the edge
    streak = fbm(a * 7.0, r * 1.2, 63) * smooth(edge * 0.75, edge, r) * smooth(1.0, edge, r)
    grit = (noise(u * 46, v * 46, 64) > 0.8) * smooth(1.0, 0.7, r) * smooth(0.45, 0.6, r) * 0.6
    if lip > 0.05:
        return hexrgb('#e8ecf2') + (clamp(lip * 0.55),)
    shade = clamp(-light) * 0.5 + sink * inner
    if light > 0.03 and inner > 0.5:
        return hexrgb('#f0f2f6') + (clamp(light * 0.12),)
    dust = clamp(streak - 0.42) * 1.2
    if grit > 0 and grit > shade:
        return hexrgb('#2a2d33') + (grit,)
    if dust > shade:
        return hexrgb('#9a9aa2') + (dust * 0.6,)
    return hexrgb('#0a0b0f') + (shade,)


# ---- Reaper --------------------------------------------------------------------------------------------

_SNATH = bezier((0.22, 0.95), (0.12, 0.42), (0.13, -0.18), (0.18, -0.66), 40)
_SCYTHE = bezier((0.2, -0.68), (-0.2, -0.98), (-0.74, -0.88), (-0.96, -0.22), 48)


def reap_scythe(u, v):
    """A spectral scythe: a long ghostly blade sweeping from a dark shaft, its cutting edge white,
    the rest burning soul-green into violet, and ghost-smoke streaming off its back."""
    # the blade: wide at its heel by the shaft, curving down to a fine point; the cutting edge is
    # on the inside of its curve, the back flickers like spectral flame
    d, t = line_pos(_SCYTHE, u, v)
    k = min(len(_SCYTHE) - 2, int(t * (len(_SCYTHE) - 1)))
    (x1, y1), (x2, y2) = _SCYTHE[k], _SCYTHE[k + 1]
    side = (x2 - x1) * (v - y1) - (y2 - y1) * (u - x1)          # > 0 on the inside (the edge)
    width = 0.28 * (1 - t) ** 0.8 + 0.01
    edge_w = width * 0.42
    back_w = width * 0.58 * (0.82 + 0.36 * fbm(t * 9 + 4, 1.5, 71))
    q = None                                                   # 0 at the edge .. 1 at the back
    if t < 0.999:
        if side > 0 and d < edge_w:
            q = 0.4 - 0.4 * d / edge_w
        elif side <= 0 and d < back_w:
            q = 0.4 + 0.6 * d / back_w
    if q is not None:
        c = _ramp(q, [(0, '#ffffff'), (0.07, '#d8fff2'), (0.25, '#3dffc0'), (0.5, '#13a184'), (0.75, '#4a2488'),
                      (1, '#2a0f4a')])
        c = mix(c, hexrgb('#b9ffe9'), 0.35 * _glow(q - 0.42, 0.03) * (1 - t))     # a bright line on the flat
        return c + (clamp(1.0 - 0.35 * q ** 2),)
    # the shaft: dark, with soul-light on its edges and two bindings near the top
    ds, ts = line_pos(_SNATH, u, v)
    if ds < 0.045:
        c = mix(hexrgb('#1b0f2c'), hexrgb('#5dffcf'), smooth(0.02, 0.044, ds))
        if 0.66 < ts < 0.7 or 0.84 < ts < 0.88:
            c = hexrgb('#8a6ad0')
        return c + (smooth(0.05, 0.036, ds),)
    # the nib: a short grip sticking out of the shaft
    nib = seg_dist(u, v, (0.14, 0.24), (0.36, 0.17))
    if nib < 0.03:
        return mix(hexrgb('#1b0f2c'), hexrgb('#5dffcf'), smooth(0.014, 0.03, nib)) + (smooth(0.034, 0.024, nib),)
    # ghost-smoke streaming off the back of the blade, and a soul glow round it all
    trail = 0.0
    if side < 0 and t < 0.95:
        out = d - back_w
        wisp = fbm(t * 6 + 1, out * 6 - t * 3, 73)
        trail = smooth(0.4, 0.0, out) * clamp(wisp * 1.8 - 0.55) * (1 - t) ** 0.5 * 0.85
    halo = 0.55 * _glow(d - width * 0.45, 0.06) * (1 - 0.5 * t) + 0.3 * _glow(ds, 0.06)
    c = mix(hexrgb('#3dffc0'), hexrgb('#6a32b4'), clamp(trail * 2.0))
    return c + (clamp(max(halo, trail)),)


def reap_soul(u, v):
    """A wisp of a reaped soul (three spiral up round the victim): a bright round heart of
    soul-light with a flickering flame-tail streaming down behind it as it rises, white at its
    heart, soul-green, fading to violet at the tip of the tail."""
    hy = -0.32
    r = math.hypot(u, v - hy)
    head = smooth(0.32, 0.16, r)
    t = (v - hy) / 1.2                                   # 0 at the heart .. 1 at the tip of the tail
    tail, fade = 0.0, 0.0
    if 0.0 < t < 1.0:
        cx = 0.14 * math.sin(t * 5.5 + 0.4) * t          # it wavers like a flame
        w = 0.26 * (1 - t) ** 1.3 + 0.012
        flicker = 0.7 + 0.3 * fbm(u * 4 + 2, v * 3 - 1, 81)
        tail = smooth(w, w * 0.25, abs(u - cx)) * (1 - t) ** 0.7 * flicker
        fade = t
    core = _glow(r, 0.1)
    light = clamp(core * 1.2 + 0.5 * head + 0.5 * tail)
    c = _ramp(light, [(0, '#2ee6b0'), (0.5, '#3dffc0'), (0.8, '#d8fff2'), (1, '#ffffff')])
    if tail > head:
        c = mix(c, hexrgb('#7a4ad0'), fade ** 1.5)                         # violet at the tip of the tail
    halo = 0.5 * _glow(r, 0.4)
    return c + (clamp(max(0.95 * head, tail, core, halo)),)


def reap_slash(u, v):
    """A scythe's sweep: a long crescent, thick at one end and drawn out to a fine hooked point
    at the other, white at its cutting edge, spectral green, with wisps trailing behind it."""
    co, ro = (0.08, 0.38), 0.95
    ci, ri = (0.2, 0.62), 0.92
    do = ro - math.hypot(u - co[0], v - co[1])
    di = math.hypot(u - ci[0], v - ci[1]) - ri
    side = clamp((u + 0.95) / 1.9)                       # 0 at the thin point (left) .. 1 at the heel
    body = smooth(0.0, 0.35, side) * smooth(1.0, 0.86, side)
    if do >= 0 and di >= 0 and v < 0.35:
        q = do / max(do + di, 1e-6)                      # 0 on the cutting edge (top), 1 at the back
        c = _ramp(q, [(0, '#ffffff'), (0.12, '#c8fff0'), (0.4, '#2ee6b0'), (1, '#2a1250')])
        return c + ((1 - 0.5 * q) * body,)
    # wisps trailing below the arc
    trail = 0.0
    if v < 0.55 and di < 0:
        wisp = fbm(u * 3.0 + 4, v * 6.0, 15)
        trail = smooth(0.25, 0.0, -di) * wisp * 0.6 * body
    halo = _glow(max(-do, 0) + max(-di, 0), 0.05) * body * 0.55
    return hexrgb('#3dffc0') + (max(halo, trail),)


# ---- the list --------------------------------------------------------------------------------------------

EFFECTS = {
    # name: (painter, size, kind)
    'katana_glint': (katana_glint, 128, 'upright'),
    'katana_cut': (katana_cut, 256, 'upright'),
    'candy_goo': (candy_goo, 128, 'upright'),
    'candy_puddle': (candy_puddle, 128, 'flat'),
    'crush_crater': (crush_crater, 256, 'flat'),
    'reap_scythe': (reap_scythe, 128, 'upright'),
    'reap_slash': (reap_slash, 128, 'upright'),
    'reap_soul': (reap_soul, 128, 'upright'),
}

# Textures only particles use: an effect's particles (the goo flying off the Sugar Trap is the
# vanilla item particle of candy_goo) show a random quarter of its model's particle texture.
PARTICLES = {
    'candy_goo': ('candy_goo_bits', candy_goo_bits, 64),
}


# How each effect is made easier to see in daylight and from afar: (thicker by, glow strength,
# glow width in texels, solidness). Shaded ones (the goo, the broken ground) are left as painted.
BOLD = {
    'katana_glint': (0, 0.6, 4, 1.4),
    'katana_cut': (0, 0.3, 3, 1.2),
    'reap_scythe': (0, 0.45, 3, 1.3),
    'reap_slash': (0, 0.6, 4, 1.5),
    'reap_soul': (0, 0.5, 4, 1.3),
}


def texture(name):
    """RGBA rows."""
    painter, size, kind = EFFECTS[name]
    rows = _paint(size, painter)
    if name in BOLD:
        rows = bold(rows, *BOLD[name])
    return rows


def _face(uv, flip=False):
    u0, v0, u1, v1 = uv
    return {'uv': [u1, v0, u0, v1] if flip else [u0, v0, u1, v1], 'texture': '#0'}


def model(name):
    painter, size, kind = EFFECTS[name]
    # Item models only find textures in the item (or block) folders: they are stitched into the
    # block atlas, and a texture anywhere else shows as the purple and black missing texture.
    texture_id = f'legendary:item/fx/{name}'
    particle = f'legendary:item/fx/{PARTICLES[name][0]}' if name in PARTICLES else texture_id
    full = [0, 0, 16, 16]
    if kind == 'flat':
        elements = [{'from': [0, 8, 0], 'to': [16, 8, 16], 'shade': False, 'light_emission': 15,
                     'faces': {'up': _face(full), 'down': _face([0, 16, 16, 0])}}]
    else:
        elements = [{'from': [0, 0, 8], 'to': [16, 16, 8], 'shade': False, 'light_emission': 15,
                     'faces': {'south': _face(full), 'north': _face(full, flip=True)}}]
    return {'textures': {'0': texture_id, 'particle': particle}, 'elements': elements}


def particle_textures():
    """{texture name: RGBA rows} of the particle-only textures."""
    return {tex: _paint(size, painter) for tex, painter, size in PARTICLES.values()}


def item(name):
    return {'model': {'type': 'minecraft:model', 'model': f'legendary:fx/{name}'}}


def sheet(path, cell=160):
    import png
    names = list(EFFECTS)
    cols = 5
    rows_n = (len(names) + cols - 1) // cols
    W, H = cols * cell, rows_n * cell
    canvas = [[(34, 34, 40) if ((x // 16 + y // 16) % 2) else (44, 44, 52) for x in range(W)] for y in range(H)]
    for k, name in enumerate(names):
        tex = texture(name)
        size = len(tex)
        ox, oy = (k % cols) * cell, (k // cols) * cell
        for y in range(cell - 8):
            for x in range(cell - 8):
                p = tex[min(size - 1, y * size // (cell - 8))][min(size - 1, x * size // (cell - 8))]
                if p[3]:
                    a = p[3] / 255
                    b = canvas[oy + 4 + y][ox + 4 + x]
                    canvas[oy + 4 + y][ox + 4 + x] = tuple(int(p[q] * a + b[q] * (1 - a)) for q in range(3))
    png.save(path, canvas)


if __name__ == '__main__':
    sheet(sys.argv[1] if len(sys.argv) > 1 else 'fx.png')
