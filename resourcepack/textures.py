"""Draws the five legendary weapon textures (16x16, no image libraries needed).

Every sprite lies on the diagonal like a vanilla sword: handle at the bottom left, tip at the
top right. For each pixel, t = x - y runs along the weapon and d = x + y - 15 across it.
"""
import struct
import sys
import zlib

SIZE = 16

PALETTES = {
    'kurogane': {
        'K': (24, 18, 26), 'S': (214, 220, 232), 'W': (255, 255, 255), 'G': (140, 146, 162),
        'H': (176, 186, 204), 'Y': (232, 184, 62), 'y': (150, 104, 28), 'B': (38, 30, 38),
        'R': (196, 28, 52), 'r': (112, 12, 30),
    },
    'sugarcrash': {
        'K': (70, 10, 24), 'R': (232, 36, 64), 'r': (170, 20, 44), 'W': (255, 255, 255),
        'w': (232, 222, 228), 'P': (255, 140, 210), 'p': (200, 80, 160), 'M': (120, 240, 180),
        'm': (60, 170, 120),
    },
    'riftblade': {
        'K': (12, 0, 20), 'N': (26, 6, 40), 'n': (48, 14, 72), 'V': (176, 82, 255),
        'v': (110, 34, 180), 'M': (246, 168, 255), 'C': (90, 230, 255), 'c': (60, 120, 200),
        'B': (34, 20, 44), 'b': (70, 40, 90),
    },
    'gravebreaker': {
        'K': (20, 16, 16), 'S': (196, 200, 206), 'W': (240, 242, 246), 'G': (116, 118, 126),
        'g': (78, 80, 88), 'R': (150, 12, 22), 'r': (96, 6, 14), 'D': (92, 62, 40),
        'd': (58, 38, 24), 'I': (60, 60, 66),
    },
    'starforged': {
        'K': (10, 10, 34), 'I': (40, 36, 120), 'i': (64, 58, 170), 'C': (110, 214, 255),
        'c': (70, 150, 230), 'W': (255, 255, 255), 'Y': (255, 224, 120), 'y': (210, 160, 50),
        'N': (24, 22, 70), 'n': (46, 40, 110),
    },
}


def blank():
    return [['.'] * SIZE for _ in range(SIZE)]


def cells():
    for y in range(SIZE):
        for x in range(SIZE):
            yield x, y, x - y, x + y - 15


def kurogane():
    g = blank()
    for x, y, t, d in cells():
        bend = -1 if t >= 9 else 0          # the katana curves near the tip
        dd = d - bend
        if -3 <= t <= 13 and dd in (-1, 0):
            if t >= 11:
                g[y][x] = 'W' if dd == -1 else 'S'
            else:
                g[y][x] = 'H' if dd == -1 else 'S'
            if dd == 0 and t % 4 == 1 and t < 11:
                g[y][x] = 'G'                # hamon, the temper line
        elif t in (-5, -4) and -2 <= d <= 1:
            g[y][x] = 'Y' if (d + t) % 2 else 'y'   # tsuba
        elif -14 <= t <= -6 and d in (-1, 0):
            g[y][x] = 'R' if (t + d) % 4 in (0, 1) else 'B'   # wrapped handle
        elif t <= -15 and d in (-1, 0):
            g[y][x] = 'y'
    return g


def sugarcrash():
    # A candy cane: the straight end is the handle, the crook curls over at the top.
    art = [
        "................",
        "...........RWR..",
        "..........WrwrW.",
        ".........Rw..rWR",
        "........Wr....wR",
        ".......Rw.....rW",
        "......Wr.......w",
        ".....Rw.........",
        "....Wr..........",
        "...Rw...........",
        "..PpP...........",
        ".RpPp...........",
        "Rw.P............",
        "w...............",
        "................",
        "................",
    ]
    g = [list(row) for row in art]
    g[0][14] = 'M'  # a sparkle of magic
    return g


def riftblade():
    g = blank()
    for x, y, t, d in cells():
        if -3 <= t <= 14 and -1 <= d <= 1 and not (t >= 12 and d != 0) and not (t == 14 and d != 0):
            if d == 0:
                g[y][x] = 'M' if t in (1, 2, 6, 7, 10) else 'N'   # the crack of light inside
            else:
                g[y][x] = 'V' if d == -1 else 'v'
        elif t in (-5, -4) and -2 <= d <= 2:
            g[y][x] = 'C' if abs(d) == 2 else 'c'                # crystal guard
        elif -14 <= t <= -6 and d in (-1, 0):
            g[y][x] = 'b' if (t + d) % 3 == 0 else 'B'
        elif t <= -15 and d in (-1, 0):
            g[y][x] = 'V'
    return g


def axe(head):
    g = blank()
    for x, y, t, d in cells():
        if -15 <= t <= 12 and d in (0, 1):
            g[y][x] = head['shaft'](t, d)
    for (x, y), c in head['cells'].items():
        g[y][x] = c
    return g


def gravebreaker():
    # A heavy crescent head on the upper left of the shaft, its edge stained red.
    cells_ = {}
    rows = {
        1: (5, 9), 2: (4, 9), 3: (3, 9), 4: (3, 8), 5: (3, 8), 6: (3, 7), 7: (4, 7), 8: (5, 6),
    }
    for y, (x0, x1) in rows.items():
        for x in range(x0, x1 + 1):
            edge = x == x0 or (y in (1, 8) and x <= x0 + 1)
            if edge:
                c = 'R' if y in (3, 4, 5, 6) else 'r'
            elif x == x0 + 1:
                c = 'W'
            elif x <= x0 + 2:
                c = 'S'
            else:
                c = 'G' if (x + y) % 3 else 'g'
            cells_[(x, y)] = c
    for x, y in ((10, 3), (11, 2), (10, 4)):
        cells_[(x, y)] = 'I'                  # iron collar
    for x, y in ((12, 2), (13, 1)):
        cells_[(x, y)] = 'G'                  # back spike
    return axe({
        'shaft': lambda t, d: 'd' if (t // 3) % 3 == 0 else 'D',
        'cells': cells_,
    })


def starforged():
    # A crescent moon head of night sky with stars, its edge glowing.
    cells_ = {}
    rows = {
        1: (5, 9), 2: (4, 9), 3: (3, 9), 4: (3, 8), 5: (3, 8), 6: (3, 7), 7: (4, 7), 8: (5, 6),
    }
    for y, (x0, x1) in rows.items():
        for x in range(x0, x1 + 1):
            if x == x0:
                c = 'C'
            elif x == x0 + 1:
                c = 'c'
            else:
                c = 'i' if (x + y) % 2 else 'I'
            cells_[(x, y)] = c
    for x, y, c in ((6, 3, 'W'), (8, 5, 'Y'), (7, 2, 'Y'), (6, 6, 'W')):
        cells_[(x, y)] = c                    # stars
    for x, y in ((10, 3), (11, 2), (10, 4)):
        cells_[(x, y)] = 'y'                  # gold collar
    for x, y, c in ((13, 1, 'Y'), (12, 0, 'W'), (14, 0, 'W'), (14, 2, 'W'), (12, 2, 'W')):
        cells_[(x, y)] = c                    # a star on top
    return axe({
        'shaft': lambda t, d: 'n' if (t + d) % 4 == 0 else 'N',
        'cells': cells_,
    })


def outline(grid):
    out = [row[:] for row in grid]
    for y in range(SIZE):
        for x in range(SIZE):
            if grid[y][x] != '.':
                continue
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                nx, ny = x + dx, y + dy
                if 0 <= nx < SIZE and 0 <= ny < SIZE and grid[ny][nx] not in ('.', 'K'):
                    out[y][x] = 'K'
                    break
    return out


SPRITES = {
    'kurogane': kurogane, 'sugarcrash': sugarcrash, 'riftblade': riftblade,
    'gravebreaker': gravebreaker, 'starforged': starforged,
}


def grid(name):
    return outline(SPRITES[name]())


def png(rows, palette):
    colors = dict(palette)
    colors['.'] = None
    raw = b''
    for row in rows:
        raw += b'\x00'
        for c in row:
            rgb = colors.get(c)
            raw += bytes((0, 0, 0, 0)) if rgb is None else bytes(rgb + (255,))

    def chunk(kind, data):
        body = kind + data
        return struct.pack('>I', len(data)) + body + struct.pack('>I', zlib.crc32(body) & 0xFFFFFFFF)

    header = struct.pack('>IIBBBBB', SIZE, SIZE, 8, 6, 0, 0, 0)
    return b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', header) + chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b'')


def preview(path, scale=12):
    """All five side by side, enlarged, on a grey background (to look at them)."""
    names = list(SPRITES)
    width = (SIZE * scale + scale) * len(names) + scale
    height = SIZE * scale + 2 * scale
    pixels = [[(60, 60, 66)] * width for _ in range(height)]
    for i, name in enumerate(names):
        rows = grid(name)
        ox = scale + i * (SIZE * scale + scale)
        for y in range(SIZE):
            for x in range(SIZE):
                rgb = PALETTES[name].get(rows[y][x])
                if rgb is None:
                    continue
                for dy in range(scale):
                    for dx in range(scale):
                        pixels[scale + y * scale + dy][ox + x * scale + dx] = rgb
    raw = b''.join(b'\x00' + b''.join(bytes(p) for p in row) for row in pixels)

    def chunk(kind, data):
        body = kind + data
        return struct.pack('>I', len(data)) + body + struct.pack('>I', zlib.crc32(body) & 0xFFFFFFFF)

    header = struct.pack('>IIBBBBB', width, height, 8, 2, 0, 0, 0)
    with open(path, 'wb') as out:
        out.write(b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', header) + chunk(b'IDAT', zlib.compress(raw, 9))
                  + chunk(b'IEND', b''))


if __name__ == '__main__':
    if sys.argv[1:2] == ['--preview']:
        preview(sys.argv[2])
        sys.exit()
    for name in (sys.argv[1:] or SPRITES):
        print(name)
        for row in grid(name):
            print('  ' + ''.join(row))
