"""The abilities' effect textures and models (slashes, claw marks, rune circles, rings, dragon
fire, stars...), painted in code like the weapons. The plugin shows them with display entities
(legendary:fx/<name>) and animates them.

Kinds of model:
    flat     lying on the ground, seen from above (texture top = north, bottom = the way it faces)
    upright  standing, facing the viewer (south); billboards always turn to face the camera
    streak   standing along its length (the plane runs north-south), for a dash trail

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


# ---- Wyrmfang ------------------------------------------------------------------------------------------


def wyrm_slash(u, v):
    """A wide jade crescent bulging towards the bottom (the way it faces): a pale-green burning
    leading edge, venom green inside, with a gold glint at its heart."""
    co, ro = (0.0, -0.3), 1.0
    ci, ri = (0.0, -0.78), 1.08
    do = ro - math.hypot(u - co[0], v - co[1])
    di = math.hypot(u - ci[0], v - ci[1]) - ri
    tips = smooth(1.0, 0.5, abs(u))
    if do >= 0 and di >= 0:
        q = do / max(do + di, 1e-6)                      # 0 on the leading edge, 1 inside
        c = _ramp(q, [(0, '#f2fff6'), (0.12, '#b8ffd0'), (0.4, '#2fd47a'), (1, '#063a1e')])
        c = mix(c, hexrgb('#ffd56a'), 0.35 * _glow(u, 0.18) * _glow(q - 0.25, 0.12))
        return c + ((1 - 0.5 * q) * tips,)
    halo = _glow(max(-do, 0) + max(-di, 0), 0.06) * tips * 0.6
    return hexrgb('#3de08a') + (halo,)


def wyrm_claw(u, v):
    """Three parallel claw gashes, pale green at their cores, fading at both ends."""
    best = 0.0
    for k in (-1, 0, 1):
        along = v + 0.08 * k * k
        across = u - 0.3 * k - 0.12 * v * v
        width = 0.055 * clamp(1 - (along / (0.9 - 0.12 * abs(k))) ** 2)
        if width > 0:
            best = max(best, _glow(across, width + 1e-3))
    halo = best ** 0.5 * 0.45
    c = mix(hexrgb('#1fae5e'), hexrgb('#f0fff4'), best ** 2)
    return c + (max(best, halo),)


def dragon_flame(u, v):
    """A puff of green dragon fire: a white-hot core, tongues of venom-green flame licking
    outwards (furthest upwards), thinning to nothing at their tips."""
    r, a = _polar(u, v)
    lick = 0.24 * fbm(a * 2.6 + 3, r * 3.2, 6) + 0.1 * math.sin(a * 7 + r * 9)
    edge = 0.62 + lick - 0.16 * v                        # v grows downwards: taller on top
    if r > edge + 0.1:
        return (0.0, 0.0, 0.0, 0.0)
    heat = clamp(1 - r / max(edge, 1e-3))
    swirl = fbm(u * 4 + 7, v * 4 - 2, 9)
    c = _ramp(heat * 0.9 + swirl * 0.2, [(0, '#0b5a2c'), (0.3, '#22c76a'), (0.6, '#7dff9e'),
                                          (0.82, '#d9ffb0'), (1, '#ffffff')])
    alpha = smooth(edge + 0.1, edge - 0.12, r) * (0.35 + 0.65 * heat ** 0.6) * (0.65 + 0.35 * swirl)
    return c + (clamp(alpha * 1.3),)


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


# ---- the list --------------------------------------------------------------------------------------------

EFFECTS = {
    # name: (painter, size, kind)
    'crimson_slash': (crimson_slash, 128, 'flat'),
    'crimson_streak': (crimson_streak, 128, 'streak'),
    'crimson_cut': (crimson_cut, 128, 'upright'),
    'rune_crimson': (rune_crimson, 256, 'flat'),
    'candy_burst': (candy_burst, 128, 'flat'),
    'candy_ring': (candy_ring, 256, 'flat'),
    'wyrm_slash': (wyrm_slash, 128, 'flat'),
    'wyrm_claw': (wyrm_claw, 128, 'upright'),
    'dragon_flame': (dragon_flame, 128, 'upright'),
    'shockwave': (shockwave, 256, 'flat'),
    'ember_ring': (ember_ring, 128, 'flat'),
    'rune_star': (rune_star, 256, 'flat'),
    'star': (star, 64, 'upright'),
    'nova': (nova, 128, 'flat'),
}


# How each effect is made easier to see (Legendary 1.4.1): (thicker by, glow strength, glow width
# in texels, solidness). Thin lines get thicker; everything gets a glow of its own colour.
BOLD = {
    'crimson_slash': (0, 0.55, 4, 1.5),
    'crimson_streak': (1, 0.7, 5, 1.7),
    'crimson_cut': (2, 0.7, 4, 1.7),
    'rune_crimson': (1, 0.6, 5, 1.6),
    'candy_burst': (2, 0.6, 4, 1.6),
    'candy_ring': (0, 0.4, 4, 1.4),
    'wyrm_slash': (0, 0.55, 4, 1.5),
    'wyrm_claw': (2, 0.7, 4, 1.7),
    'dragon_flame': (0, 0.35, 3, 1.2),
    'shockwave': (2, 0.5, 5, 1.7),
    'ember_ring': (1, 0.4, 3, 1.4),
    'rune_star': (2, 0.6, 5, 1.6),
    'star': (0, 0.7, 3, 1.6),
    'nova': (1, 0.4, 4, 1.4),
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
    full = [0, 0, 16, 16]
    if kind == 'flat':
        elements = [{'from': [0, 8, 0], 'to': [16, 8, 16], 'shade': False, 'light_emission': 15,
                     'faces': {'up': _face(full), 'down': _face([0, 16, 16, 0])}}]
    elif kind == 'upright':
        elements = [{'from': [0, 0, 8], 'to': [16, 16, 8], 'shade': False, 'light_emission': 15,
                     'faces': {'south': _face(full), 'north': _face(full, flip=True)}}]
    else:
        elements = [{'from': [8, 0, 0], 'to': [8, 16, 16], 'shade': False, 'light_emission': 15,
                     'faces': {'east': _face(full), 'west': _face(full, flip=True)}}]
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
