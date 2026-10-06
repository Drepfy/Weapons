"""The abilities' effect textures and models (slashes, rune circles, a rift, stars, a black
hole...), painted in code like the weapons. The plugin shows them with display entities
(legendary:fx/<name>) and animates them.

Kinds of model:
    flat     lying on the ground, seen from above (texture top = north, bottom = the way it faces)
    upright  standing, facing the viewer (south); billboards always turn to face the camera
    streak   standing along its length (the plane runs north-south), for a dash trail
    stone    a small 3D gravestone

    python3 fx.py out.png      a contact sheet of every effect
"""
import math
import os
import random
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), 'models'))
from paint import clamp, fbm, hexrgb, mix, noise, smooth  # noqa: E402

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


# ---- Kurogane -----------------------------------------------------------------------------------------


def crimson_slash(u, v):
    """A crescent bulging towards the bottom (the way it faces), white-hot on its leading edge."""
    co, ro = (0.0, -0.32), 0.98
    ci, ri = (0.0, -0.72), 1.02
    do = ro - math.hypot(u - co[0], v - co[1])
    di = math.hypot(u - ci[0], v - ci[1]) - ri
    tips = smooth(0.98, 0.55, abs(u))
    if do >= 0 and di >= 0:
        w = max(do + di, 1e-6)
        q = do / w                                       # 0 on the leading edge, 1 inside
        c = _ramp(q, [(0, '#ffffff'), (0.12, '#ffd2da'), (0.35, '#ff2a4a'), (1, '#5a0010')])
        return c + ((1 - 0.55 * q) * tips,)
    halo = _glow(max(-do, 0) + max(-di, 0), 0.05) * tips * 0.6
    return hexrgb('#ff3355') + (halo,)


def crimson_streak(u, v):
    """A long dash trail: a white core line in a crimson glow, tapering at both ends."""
    taper = clamp(1 - abs(u) ** 6)
    wobble = 0.02 * math.sin(u * 9)
    d = abs(v - wobble)
    core = _glow(d, 0.025)
    body = _glow(d, 0.11)
    ghost = 0.35 * (_glow(abs(v - 0.28), 0.02) + _glow(abs(v + 0.26), 0.02)) * smooth(0.95, 0.2, abs(u))
    c = mix(hexrgb('#c0102c'), hexrgb('#ffffff'), core)
    return c + ((core + 0.75 * body + ghost) * taper,)


def crimson_cut(u, v):
    """An X of two slashes crossing."""
    best = 0.0
    for sign in (1, -1):
        # distance to the diagonal, and how far along it
        along = (u + sign * v) / math.sqrt(2)
        across = (u - sign * v) / math.sqrt(2)
        width = 0.07 * clamp(1 - (along / 0.95) ** 2)
        if width > 0:
            best = max(best, _glow(across, width + 1e-3))
    halo = best ** 0.5 * 0.4
    c = mix(hexrgb('#d8102e'), hexrgb('#ffffff'), best ** 2)
    return c + (max(best, halo),)


def _rune_circle(u, v, colour, accent, seed):
    r, a = _polar(u, v)
    ring = _glow(r - 0.9, 0.025) + 0.8 * _glow(r - 0.82, 0.012) + 0.7 * _glow(r - 0.6, 0.012)
    # rune marks between the rings
    marks = 0.0
    k = 16
    sector = (a / TAU * k) % 1.0
    idx = int(math.floor((a / TAU) * k)) % k
    rnd = random.Random(seed * 100 + idx)
    if 0.66 < r < 0.78:
        shape = rnd.randint(0, 3)
        x = (sector - 0.5) * 2
        y = (r - 0.72) / 0.06
        if shape == 0:
            marks = _glow(abs(x), 0.12) * (abs(y) < 0.9)
        elif shape == 1:
            marks = _glow(abs(y), 0.18) * (abs(x) < 0.5) + _glow(abs(x), 0.1) * (abs(y) < 0.9)
        elif shape == 2:
            marks = _glow(abs(abs(x) - abs(y) * 0.5), 0.12) * (abs(y) < 0.9)
        else:
            marks = _glow(math.hypot(x, y) - 0.5, 0.15)
    # small dots on the outer ring
    dots = _glow(r - 0.96, 0.02) * (0.5 + 0.5 * math.cos(a * 24)) ** 8
    inner = 0.08 * smooth(0.62, 0.0, r)
    intensity = clamp(ring + 0.85 * marks + dots)
    c = mix(hexrgb(colour), hexrgb(accent), clamp(ring * 0.6 + marks * 0.3))
    return c + (clamp(intensity + inner),)


def rune_crimson(u, v):
    return _rune_circle(u, v, '#c2102c', '#ff9aa8', 3)


# ---- Sugarcrash ----------------------------------------------------------------------------------------

CANDY = ['#ff2d55', '#ffffff', '#ff7ac3', '#7af0ff', '#ffe066']


def candy_burst(u, v):
    r, a = _polar(u, v)
    rays = 12
    sector = (a / TAU * rays) % 1.0
    ray = abs(sector - 0.5) * 2                          # 0 at the middle of a ray
    width = 0.55 * (1 - r) + 0.08
    on = smooth(1 - width, 1 - width * 0.4, 1 - ray)
    stripe = ((r * 9) % 1.0) < 0.5
    idx = int(math.floor(a / TAU * rays)) % 3
    col = hexrgb(['#ff2d55', '#ffffff', '#ff7ac3'][idx]) if stripe else hexrgb('#ffffff')
    fade = smooth(0.98, 0.6, r) * smooth(0.12, 0.25, r)
    core = _glow(r, 0.16)
    sparkle = (0.5 + 0.5 * math.cos(a * 4)) ** 12 * _glow(r - 0.3, 0.2)
    alpha = clamp(on * fade + core + sparkle * 0.6)
    return mix(col, (1, 1, 1), clamp(core + sparkle)) + (alpha,)


def _hook_path():
    """The candy cane: a shaft up, then the crook curling over to the left."""
    pts = []
    for k in range(24):
        pts.append((0.18, 0.92 - k * (1.1 / 23)))                 # the shaft, bottom to top
    cx, cy, r = -0.17, -0.18, 0.35
    for k in range(1, 33):
        ang = math.pi * k / 32                                    # over the top, right to left
        pts.append((cx + r * math.cos(ang), cy - r * math.sin(ang)))
    for k in range(1, 7):
        pts.append((cx - r + 0.01 * k, cy + 0.035 * k))            # a little barb at the tip
    return pts


HOOK = _hook_path()
HOOK_LENGTHS = [0.0]
for _k in range(1, len(HOOK)):
    HOOK_LENGTHS.append(HOOK_LENGTHS[-1] + math.dist(HOOK[_k - 1], HOOK[_k]))


def candy_hook(u, v):
    """A glossy red-and-white candy-cane hook with a pink glow."""
    best, along, side = 9.0, 0.0, 0.0
    for k in range(len(HOOK) - 1):
        (x0, y0), (x1, y1) = HOOK[k], HOOK[k + 1]
        dx, dy = x1 - x0, y1 - y0
        seg = dx * dx + dy * dy
        t = clamp(((u - x0) * dx + (v - y0) * dy) / seg) if seg > 0 else 0.0
        px, py = x0 + dx * t, y0 + dy * t
        d = math.hypot(u - px, v - py)
        if d < best:
            best = d
            along = HOOK_LENGTHS[k] + math.sqrt(seg) * t
            side = ((u - px) * -dy + (v - py) * dx) / max(1e-6, math.sqrt(seg))
    width = 0.085 if along < HOOK_LENGTHS[-1] - 0.06 else 0.06
    if best < width:
        stripe = ((along * 5.5 + side * 2.5) % 1.0) < 0.5
        col = hexrgb('#e8153c') if stripe else hexrgb('#fff4f7')
        q = side / width                                           # -1..1 across the cane
        col = mix(col, hexrgb('#5a0418'), clamp(-q) * 0.45)         # shaded side
        col = mix(col, (1, 1, 1), _glow(q - 0.45, 0.18) * 0.7)       # gloss
        return col + (smooth(width, width - 0.015, best),)
    halo = _glow(best - width, 0.07) * 0.55
    return hexrgb('#ff6fb5') + (halo,)


def stun_ring(u, v):
    """Dizzy candy stars circling a head, seen a little from above: an ellipse of five stars,
    the ones in front brighter."""
    ring = _glow(math.hypot(u / 0.86, v / 0.3) - 1.0, 0.05) * 0.35
    best, col = 0.0, (1, 1, 1)
    for k in range(5):
        ang = TAU * k / 5 + 0.3
        cx, cy = 0.86 * math.cos(ang), 0.3 * math.sin(ang)
        front = 0.55 + 0.45 * math.sin(ang)                        # lower on the ellipse = in front
        dx, dy = u - cx, v - cy
        r, a = math.hypot(dx, dy), math.atan2(dy, dx)
        size = 0.09 + 0.05 * front
        spikes = size * (0.55 + 0.45 * math.cos(a * 5) ** 2)
        star_a = smooth(spikes + 0.02, spikes - 0.01, r) * (0.55 + 0.45 * front)
        glow = _glow(r, size * 1.6) * 0.35 * front
        val = max(star_a, glow)
        if val > best:
            best = val
            col = mix(hexrgb(CANDY[k % len(CANDY)]), (1, 1, 1), _glow(r, size * 0.45) * 0.8)
    return col + (clamp(max(best, ring)),)


def candy_ring(u, v):
    r, a = _polar(u, v)
    band = smooth(0.66, 0.72, r) * smooth(0.97, 0.9, r)
    swirl = (a / TAU * 9 + r * 5) % 1.0
    k = int(math.floor(a / TAU * 9 + r * 5)) % 3
    col = hexrgb(['#ff2d55', '#ffffff', '#ff7ac3'][k])
    edge = smooth(0.0, 0.08, min(swirl, 1 - swirl))
    gloss = _glow(r - 0.8, 0.03) * 0.5
    col = mix(col, hexrgb('#8a0a2a'), (1 - edge) * 0.5)
    col = mix(col, (1, 1, 1), gloss)
    glow = _glow(r - 0.82, 0.12) * 0.35
    return col + (clamp(band * (0.75 + 0.25 * edge) + glow),)


# ---- Riftblade ------------------------------------------------------------------------------------------


def rift(u, v):
    """A tall jagged tear: starry void inside, violet-white burning edges, a haze round it."""
    centre = 0.06 * math.sin(v * 7.0) + 0.035 * math.sin(v * 19.0 + 1.3)
    half = 0.2 * clamp(1 - abs(v) ** 2.2) + 0.015 * math.sin(v * 41)
    d = abs(u - centre) - half                           # < 0 inside the tear
    if d < 0:
        star = 1.0 if noise(u * 40, v * 40, 9) > 0.86 else 0.0
        depth = smooth(0.0, -half, d)
        c = mix(hexrgb('#2a0a52'), hexrgb('#05010c'), depth)
        c = mix(c, hexrgb('#e8d6ff'), star * 0.8)
        return c + (1.0,)
    edge = _glow(d, 0.02)
    haze = _glow(d, 0.14) * 0.55 * clamp(1 - abs(v) ** 3)
    c = mix(hexrgb('#9a4dff'), hexrgb('#ffffff'), edge)
    return c + (clamp(edge + haze),)


def void_portal(u, v):
    r, a = _polar(u, v)
    swirl = 0.5 + 0.5 * math.sin(5 * a + 11 * r)
    core = smooth(0.32, 0.12, r)
    rim = _glow(r - 0.78, 0.08)
    body = smooth(0.95, 0.75, r)
    c = _ramp(swirl * 0.7 + rim * 0.3, [(0, '#12021f'), (0.5, '#5a16b8'), (0.85, '#b37aff'), (1, '#ffffff')])
    c = mix(c, hexrgb('#000000'), core)
    return c + (clamp(body * (0.55 + 0.45 * swirl) + core + rim * 0.6),)


def void_burst(u, v):
    r, a = _polar(u, v)
    jag = 0.03 * math.sin(a * 13) + 0.02 * math.sin(a * 29 + 2)
    ring = _glow(r - 0.78 - jag, 0.06)
    fill = 0.18 * smooth(0.8, 0.2, r)
    c = mix(hexrgb('#7a2be0'), hexrgb('#f3e6ff'), ring ** 2)
    return c + (clamp(ring + fill),)


# ---- Gravebreaker ---------------------------------------------------------------------------------------


def shockwave(u, v):
    """Cracked ground: cracks running out from the impact, glowing like embers near it, and a
    ring of dust."""
    r, a = _polar(u, v)
    cracks = 0.0
    rnd = random.Random(5)
    for k in range(9):
        base = k / 9 * TAU + rnd.uniform(-0.2, 0.2)
        wob = 0.12 * math.sin(r * 9 + k) + 0.06 * math.sin(r * 23 + k * 2)
        da = math.atan2(math.sin(a - base - wob), math.cos(a - base - wob))
        dist = abs(da) * r
        width = 0.03 * (1 - r) + 0.006
        if r < 0.95:
            cracks = max(cracks, smooth(width, width * 0.3, dist))
    ring_cracks = smooth(0.012, 0.004, abs(r - 0.42 - 0.03 * math.sin(a * 7))) * (math.sin(a * 5) > -0.2)
    cracks = max(cracks, ring_cracks * 0.9)
    ember = smooth(0.75, 0.0, r)
    c = mix(hexrgb('#1a0d08'), hexrgb('#ff7a1a'), ember * 0.9)
    c = mix(c, hexrgb('#ffe08a'), ember ** 3)
    dust = _glow(r - 0.88, 0.07) * 0.45 * (0.6 + 0.4 * fbm(u * 6, v * 6, 2))
    alpha = clamp(cracks * (0.75 + 0.25 * ember) + dust)
    col = c if cracks > dust else hexrgb('#8a7a66')
    return col + (alpha,)


def ember_ring(u, v):
    r, a = _polar(u, v)
    ring = _glow(r - 0.86, 0.045) + 0.4 * _glow(r - 0.86, 0.12)
    flicker = 0.75 + 0.25 * noise(a * 6, 3, 4)
    c = mix(hexrgb('#ff5a0a'), hexrgb('#fff0b0'), _glow(r - 0.86, 0.02))
    return c + (clamp(ring * flicker),)


def gravestone_texture(size=64):
    """Stone with an engraved cross, worn edges and moss near the ground. The left half is the
    front, the right half the sides."""
    rows = []
    for j in range(size):
        row = []
        for i in range(size):
            x, y = i / size, j / size
            n = fbm(x * 9, y * 9, 13)
            base = mix(hexrgb('#4b4d55'), hexrgb('#8a8d96'), 0.35 + 0.5 * n)
            if i < size // 2:
                fx, fy = (x - 0.25) / 0.25, (y - 0.42) / 0.42      # front face, -1..1
                engraved = (abs(fx) < 0.12 and -0.7 < fy < 0.55) or (abs(fy + 0.25) < 0.1 and abs(fx) < 0.45)
                if engraved:
                    base = mix(base, hexrgb('#1c1d22'), 0.75)
                    if noise(x * 30, y * 30, 3) > 0.7:
                        base = mix(base, hexrgb('#7c2a8a'), 0.5)     # a faint soul glow
                crack = abs(fx - 0.6 - 0.1 * math.sin(fy * 6)) < 0.04 and fy > 0.2
                if crack:
                    base = mix(base, hexrgb('#24252b'), 0.7)
            moss = smooth(0.7, 0.98, y) * smooth(0.45, 0.7, fbm(x * 14, y * 14, 21))
            base = mix(base, hexrgb('#3d5a2a'), moss * 0.8)
            row.append(tuple(int(round(255 * clamp(c))) for c in base) + (255,))
        rows.append(row)
    return rows


# ---- Starforged -----------------------------------------------------------------------------------------


def rune_star(u, v):
    r, a = _polar(u, v)
    ring = _glow(r - 0.9, 0.022) + 0.7 * _glow(r - 0.84, 0.01) + 0.8 * _glow(r - 0.58, 0.014)
    # an eight-pointed star drawn with straight lines between points on the inner ring
    lines = 0.0
    pts = [(0.84 * math.cos(k * TAU / 8), 0.84 * math.sin(k * TAU / 8)) for k in range(8)]
    for k in range(8):
        (x1, y1), (x2, y2) = pts[k], pts[(k + 3) % 8]
        dx, dy = x2 - x1, y2 - y1
        t = clamp(((u - x1) * dx + (v - y1) * dy) / (dx * dx + dy * dy))
        d = math.hypot(u - x1 - t * dx, v - y1 - t * dy)
        lines = max(lines, _glow(d, 0.009))
    dots = 0.0
    for x, y in pts:
        dots = max(dots, _glow(math.hypot(u - x, v - y), 0.035))
    twinkle = (0.5 + 0.5 * math.cos(a * 32)) ** 16 * _glow(r - 0.9, 0.04)
    gold, cyan = hexrgb('#ffd36b'), hexrgb('#6fe6ff')
    c = mix(gold, cyan, clamp(lines * 0.9 + _glow(r - 0.58, 0.02)))
    c = mix(c, (1, 1, 1), clamp(dots + twinkle) * 0.7)
    fill = 0.06 * smooth(0.9, 0.0, r)
    return c + (clamp(ring + 0.85 * lines + dots + twinkle + fill),)


def star(u, v):
    r, a = _polar(u, v)
    rays = max(_glow(u, 0.045) * _glow(v, 0.55), _glow(v, 0.045) * _glow(u, 0.55))
    diag = 0.5 * max(_glow((u + v) / 1.414, 0.03) * _glow((u - v) / 1.414, 0.3),
                     _glow((u - v) / 1.414, 0.03) * _glow((u + v) / 1.414, 0.3))
    core = _glow(r, 0.12)
    halo = _glow(r, 0.4) * 0.45
    c = _ramp(clamp(core + rays * 0.7), [(0, '#6fe6ff'), (0.5, '#ffd36b'), (1, '#ffffff')])
    return c + (clamp(core + rays + diag + halo),)


def nova(u, v):
    r, a = _polar(u, v)
    ring = _glow(r - 0.8, 0.07)
    inner = 0.22 * smooth(0.82, 0.3, r)
    sparks = (0.5 + 0.5 * math.cos(a * 18)) ** 20 * _glow(r - 0.86, 0.08)
    c = _ramp(ring, [(0, '#8a5cff'), (0.6, '#6fe6ff'), (1, '#ffffff')])
    return c + (clamp(ring + inner + sparks),)


def black_hole(u, v):
    r, a = _polar(u, v)
    if r < 0.38:
        return hexrgb('#020005') + (1.0,)
    photon = _glow(r - 0.42, 0.035)
    lens = _glow(r - 0.5, 0.2) * 0.55
    c = _ramp(photon, [(0, '#3b1a8a'), (0.5, '#ff9a3c'), (1, '#fff6e0')])
    return c + (clamp(photon + lens + smooth(0.45, 0.38, r)),)


def accretion(u, v):
    r, a = _polar(u, v)
    band = smooth(0.3, 0.42, r) * smooth(0.98, 0.75, r)
    spiral = 0.5 + 0.5 * math.sin(a * 3 + math.log(max(r, 1e-3)) * 9)
    heat = smooth(0.95, 0.35, r)
    c = _ramp(heat * 0.8 + spiral * 0.2, [(0, '#3b1a8a'), (0.4, '#8a5cff'), (0.7, '#ff9a3c'), (1, '#fff3c4')])
    return c + (clamp(band * (0.35 + 0.65 * spiral)),)


# ---- the list --------------------------------------------------------------------------------------------

EFFECTS = {
    # name: (painter, size, kind)
    'crimson_slash': (crimson_slash, 128, 'flat'),
    'crimson_streak': (crimson_streak, 128, 'streak'),
    'crimson_cut': (crimson_cut, 128, 'upright'),
    'rune_crimson': (rune_crimson, 256, 'flat'),
    'candy_hook': (candy_hook, 128, 'upright'),
    'stun_ring': (stun_ring, 128, 'upright'),
    'candy_burst': (candy_burst, 128, 'flat'),
    'candy_ring': (candy_ring, 256, 'flat'),
    'rift': (rift, 128, 'upright'),
    'void_portal': (void_portal, 128, 'upright'),
    'void_burst': (void_burst, 128, 'upright'),
    'shockwave': (shockwave, 256, 'flat'),
    'ember_ring': (ember_ring, 128, 'flat'),
    'gravestone': (None, 64, 'stone'),
    'rune_star': (rune_star, 256, 'flat'),
    'star': (star, 64, 'upright'),
    'nova': (nova, 128, 'flat'),
    'black_hole': (black_hole, 128, 'upright'),
    'accretion': (accretion, 256, 'flat'),
}


# How each effect is made easier to see (Legendary 1.4.1): (thicker by, glow strength, glow width
# in texels, solidness). Thin lines get thicker; everything gets a glow of its own colour.
BOLD = {
    'crimson_slash': (0, 0.55, 4, 1.5),
    'crimson_streak': (1, 0.7, 5, 1.7),
    'crimson_cut': (2, 0.7, 4, 1.7),
    'rune_crimson': (1, 0.6, 5, 1.6),
    'candy_hook': (0, 0.4, 3, 1.2),
    'stun_ring': (1, 0.6, 3, 1.6),
    'candy_burst': (2, 0.6, 4, 1.6),
    'candy_ring': (0, 0.4, 4, 1.4),
    'rift': (0, 0.5, 4, 1.3),
    'void_portal': (0, 0.3, 3, 1.4),
    'void_burst': (2, 0.6, 4, 1.6),
    'shockwave': (2, 0.5, 5, 1.7),
    'ember_ring': (1, 0.4, 3, 1.4),
    'rune_star': (2, 0.6, 5, 1.6),
    'star': (0, 0.7, 3, 1.6),
    'nova': (1, 0.4, 4, 1.4),
    'accretion': (0, 0.3, 4, 1.4),
}


def texture(name):
    """RGBA rows."""
    painter, size, kind = EFFECTS[name]
    if kind == 'stone':
        return gravestone_texture(size)
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
    full = [0, 0, 16, 16]
    if kind == 'flat':
        elements = [{'from': [0, 8, 0], 'to': [16, 8, 16], 'shade': False, 'light_emission': 15,
                     'faces': {'up': _face(full), 'down': _face([0, 16, 16, 0])}}]
    elif kind == 'upright':
        elements = [{'from': [0, 0, 8], 'to': [16, 16, 8], 'shade': False, 'light_emission': 15,
                     'faces': {'south': _face(full), 'north': _face(full, flip=True)}}]
    elif kind == 'streak':
        elements = [{'from': [8, 0, 0], 'to': [8, 16, 16], 'shade': False, 'light_emission': 15,
                     'faces': {'east': _face(full), 'west': _face(full, flip=True)}}]
    else:
        # a gravestone: plinth, slab and a rounded top; the front shows the engraving
        front = [0, 0, 8, 16]
        side = [8, 0, 16, 16]
        elements = []
        for a, b in (([2, 0, 5.5], [14, 1.5, 10.5]), ([3, 1.5, 6.5], [13, 11, 9.5]),
                     ([4, 11, 6.5], [12, 12.5, 9.5]), ([5.5, 12.5, 6.5], [10.5, 13.5, 9.5])):
            faces = {}
            for face in ('north', 'south'):
                faces[face] = {'uv': [front[0] + (a[0] - 2) / 12 * 8, 16 - b[1] * 16 / 13.5,
                                      front[0] + (b[0] - 2) / 12 * 8, 16 - a[1] * 16 / 13.5], 'texture': '#0'}
            for face in ('east', 'west', 'up', 'down'):
                faces[face] = {'uv': side, 'texture': '#0'}
            elements.append({'from': a, 'to': b, 'faces': faces})
    return {'textures': {'0': texture_id, 'particle': texture_id}, 'elements': elements}


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
