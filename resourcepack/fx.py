"""The abilities' effect textures and models (slashes, sigils, sugar traps, rings, craters...),
painted in code like the weapons. The plugin shows them with display entities
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


# ---- Katana -------------------------------------------------------------------------------------------


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


def draw_sigil(u, v):
    """The half-drawn blade: one sweeping brush stroke round (thick and thin, with the gap where
    the brush lifted), eight small blades pointing in, and a thin inner ring."""
    r, a = _polar(u, v)
    # the brush stroke: it starts thick, thins out, and stops short of where it began
    turn = ((a + 2.2) % TAU) / TAU                       # 0..1 round the stroke
    width = 0.028 + 0.05 * (1 - turn) ** 1.4
    fade = smooth(0.0, 0.03, turn) * smooth(0.97, 0.88, turn)
    bristles = 0.75 + 0.25 * fbm(turn * 40, r * 30, 11)
    stroke = smooth(width, width * 0.55, abs(r - 0.84 - 0.02 * math.sin(a * 3))) * fade * bristles
    inner = 0.75 * _glow(r - 0.6, 0.012)
    blades = 0.0
    for k in range(8):
        ang = k * TAU / 8
        x = u * math.cos(ang) + v * math.sin(ang)        # along the blade (pointing in)
        y = -u * math.sin(ang) + v * math.cos(ang)
        if 0.64 < x < 0.78:
            half = 0.03 * (x - 0.64) / 0.14
            blades = max(blades, smooth(half + 0.006, half, abs(y)))
    core = _glow(r - 0.84, 0.012) * fade
    c = mix(hexrgb('#b80c26'), hexrgb('#ff8094'), clamp(core + 0.4 * blades))
    fill = 0.07 * smooth(0.84, 0.2, r)
    return c + (clamp(stroke + inner + blades + fill),)


# ---- Candy Cane ----------------------------------------------------------------------------------------


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


def sugar_trap(u, v):
    """A peppermint lying on the ground: red swirls curving round a white candy, a clear glossy
    rim, a soft pink glow round it and sugar sparkling on top."""
    r, a = _polar(u, v)
    if r > 1.0:
        return (0.0, 0.0, 0.0, 0.0)
    rho = r / 0.74
    swirl = ((a + rho * 1.7) / TAU * 8) % 1.0
    red = smooth(0.05, 0.0, abs(swirl - 0.5) - 0.2) * smooth(0.12, 0.22, rho) * smooth(0.98, 0.9, rho)
    c = mix(hexrgb('#fff6f4'), hexrgb('#e0142f'), red)
    c = mix(c, hexrgb('#ffd8e6'), 0.5 * smooth(0.86, 1.0, rho))
    c = mix(c, (1.0, 1.0, 1.0), 0.6 * _glow(math.hypot(u + 0.25, v + 0.25), 0.12))     # the shine
    if noise(u * 40, v * 40, 12) > 0.86 and rho < 0.95:
        c = mix(c, (1.0, 1.0, 1.0), 0.7)
    candy = smooth(1.0, 0.97, rho)
    glow = _glow(r - 0.8, 0.09) * 0.75
    if candy > 0:
        return c + (candy,)
    return hexrgb('#ff5aa5') + (glow,)


# ---- Crush ---------------------------------------------------------------------------------------------


def crush_ring(u, v):
    """Charged up: a heavy azure ring split into eight plates, bronze studs between them, and a
    faint ring of light inside."""
    r, a = _polar(u, v)
    plate = ((a / TAU) * 8) % 1.0
    gap = smooth(0.03, 0.06, min(plate, 1 - plate))
    ring = smooth(0.06, 0.035, abs(r - 0.82)) * gap
    core = _glow(r - 0.82, 0.012) * gap
    studs = 0.0
    for k in range(8):
        ang = k * TAU / 8
        studs = max(studs, _glow(math.hypot(u - 0.82 * math.cos(ang), v - 0.82 * math.sin(ang)), 0.035))
    inner = 0.6 * _glow(r - 0.62, 0.014)
    ticks = 0.0
    if 0.66 < r < 0.74:
        ticks = smooth(0.1, 0.0, abs(((a / TAU) * 32) % 1.0 - 0.5) * 2 - 0.85)
    c = mix(hexrgb('#1a6fd0'), hexrgb('#bfeaff'), clamp(core + 0.5 * inner))
    c = mix(c, hexrgb('#e8b878'), studs)
    glow = 0.25 * _glow(r - 0.82, 0.1)
    return c + (clamp(ring * 0.85 + core + studs + inner + 0.8 * ticks + glow),)


def crush_crater(u, v):
    """The ground where they landed: broken into a ring of plates round a dark hollow, cracks
    branching out from it glowing azure near the middle, and a ring of dust."""
    r, a = _polar(u, v)
    cracks = 0.0
    rnd = random.Random(21)
    for k in range(11):
        base = k / 11 * TAU + rnd.uniform(-0.25, 0.25)
        bend = rnd.uniform(-0.5, 0.5)
        reach = rnd.uniform(0.72, 0.95)
        wob = 0.035 * math.sin(r * 8 + k) + 0.02 * math.sin(r * 21 + k * 2) + bend * r * 0.25
        da = math.atan2(math.sin(a - base - wob), math.cos(a - base - wob))
        dist = abs(da) * r
        width = 0.02 * (1 - r / reach) + 0.004
        if r < reach:
            cracks = max(cracks, smooth(width, width * 0.3, dist))
        # a branch half way out
        if 0.4 < r < reach * 0.9:
            db = math.atan2(math.sin(a - base - wob - 0.7 * (r - 0.4)), math.cos(a - base - wob - 0.7 * (r - 0.4)))
            cracks = max(cracks, smooth(width * 0.8, width * 0.25, abs(db) * r) * smooth(0.4, 0.5, r))
    rim = smooth(0.016, 0.004, abs(r - 0.3 - 0.025 * math.sin(a * 9))) * (math.sin(a * 6 + 1) > -0.4)
    cracks = max(cracks, rim)
    hollow = 0.55 * smooth(0.3, 0.15, r)
    energy = smooth(0.7, 0.0, r)
    c = mix(hexrgb('#141a24'), hexrgb('#2f9bff'), energy * 0.95)
    c = mix(c, hexrgb('#d6f4ff'), energy ** 3)
    dust = _glow(r - 0.88, 0.07) * 0.4 * (0.6 + 0.4 * fbm(u * 6, v * 6, 22))
    if cracks > max(dust, hollow):
        return c + (clamp(cracks * (0.75 + 0.25 * energy)),)
    if hollow > dust:
        return hexrgb('#06080c') + (hollow,)
    return hexrgb('#8a8a96') + (dust,)


# ---- Reaper --------------------------------------------------------------------------------------------


def soul_ring(u, v):
    """Souls gather: a ring of spectral green fire with wisps rising off it, a thin ring inside,
    and small soul lights circling."""
    r, a = _polar(u, v)
    lick = 0.06 * fbm(a * 3.2 + 5, 2.0, 13) + 0.03 * math.sin(a * 11)
    flame = smooth(0.07, 0.02, abs(r - 0.8 - lick * 0.5)) + 0.6 * smooth(0.12, 0.0, r - 0.8 - lick) * (r > 0.8)
    flame *= 0.7 + 0.3 * fbm(a * 6, r * 8, 14)
    core = _glow(r - 0.8, 0.014)
    inner = 0.65 * _glow(r - 0.58, 0.012)
    lights = 0.0
    for k in range(6):
        ang = k * TAU / 6 + 0.3
        lights = max(lights, _glow(math.hypot(u - 0.68 * math.cos(ang), v - 0.68 * math.sin(ang)), 0.03))
    c = _ramp(clamp(core + lights + 0.3 * inner), [(0, '#0a7a58'), (0.5, '#3dffc0'), (1, '#f0fff9')])
    c = mix(c, hexrgb('#6d3fb0'), 0.35 * smooth(0.85, 1.0, r))
    fill = 0.06 * smooth(0.8, 0.1, r)
    return c + (clamp(flame + core + inner + lights + fill),)


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
    'crimson_slash': (crimson_slash, 128, 'flat'),
    'crimson_cut': (crimson_cut, 128, 'upright'),
    'draw_sigil': (draw_sigil, 256, 'flat'),
    'candy_burst': (candy_burst, 128, 'flat'),
    'sugar_trap': (sugar_trap, 128, 'flat'),
    'crush_ring': (crush_ring, 256, 'flat'),
    'crush_crater': (crush_crater, 256, 'flat'),
    'soul_ring': (soul_ring, 256, 'flat'),
    'reap_slash': (reap_slash, 128, 'upright'),
}


# How each effect is made easier to see (Legendary 1.4.1): (thicker by, glow strength, glow width
# in texels, solidness). Thin lines get thicker; everything gets a glow of its own colour.
BOLD = {
    'crimson_slash': (0, 0.55, 4, 1.5),
    'crimson_cut': (2, 0.7, 4, 1.7),
    'draw_sigil': (1, 0.6, 5, 1.6),
    'candy_burst': (2, 0.6, 4, 1.6),
    'sugar_trap': (0, 0.4, 3, 1.3),
    'crush_ring': (1, 0.55, 5, 1.6),
    'crush_crater': (1, 0.5, 5, 1.6),
    'soul_ring': (1, 0.6, 5, 1.6),
    'reap_slash': (0, 0.6, 4, 1.5),
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
    else:
        elements = [{'from': [0, 0, 8], 'to': [16, 16, 8], 'shade': False, 'light_emission': 15,
                     'faces': {'south': _face(full), 'north': _face(full, flip=True)}}]
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
