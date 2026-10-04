"""Tiny pixel-art toolkit: palettes, diagonal coordinates, outlines, PNG output."""
import struct, zlib

N = 32

def hexrgb(h):
    h = h.lstrip('#')
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))

class Sprite:
    def __init__(self, palette, outline_of):
        self.pal = {k: hexrgb(v) for k, v in palette.items()}
        self.outline_of = outline_of   # material key -> outline key
        self.px = {}

    def set(self, x, y, k):
        if 0 <= x < N and 0 <= y < N:
            if k is None:
                self.px.pop((x, y), None)
            else:
                self.px[(x, y)] = k

    def get(self, x, y):
        return self.px.get((x, y))

    # diagonal coords: s along the weapon (0 = bottom left, 31 = top right), o across it
    # (negative = upper-left side, positive = lower-right side)
    @staticmethod
    def so(x, y):
        return (x - y + (N - 1)) / 2.0, x + y - (N - 1)

    @staticmethod
    def xy(s, o):
        return int(s + o / 2), int(N - 1 - s + o / 2)

    def paint(self, fn):
        for y in range(N):
            for x in range(N):
                s, o = self.so(x, y)
                k = fn(s, o, x, y)
                if k:
                    self.set(x, y, k)

    def draw(self, art, ox=0, oy=0):
        for y, row in enumerate(art):
            for x, c in enumerate(row):
                if c == ' ' or c == '.':
                    continue
                if c == '_':
                    self.set(ox + x, oy + y, None)
                else:
                    self.set(ox + x, oy + y, c)

    def center(self, dx=0, dy=0):
        """Move the drawing to the middle of the canvas (plus an optional nudge)."""
        xs = [x for x, _ in self.px]; ys = [y for _, y in self.px]
        mx = (N - 1 - max(xs) - min(xs)) // 2 + dx
        my = (N - 1 - max(ys) - min(ys)) // 2 + dy
        self.px = {(x + mx, y + my): k for (x, y), k in self.px.items()}

    def outline(self):
        add = {}
        for y in range(N):
            for x in range(N):
                if (x, y) in self.px:
                    continue
                votes = {}
                for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    k = self.px.get((x + dx, y + dy))
                    if k is not None and k in self.outline_of:
                        o = self.outline_of[k]
                        votes[o] = votes.get(o, 0) + 1
                if votes:
                    add[(x, y)] = max(votes, key=lambda o: (votes[o], o))
        self.px.update(add)

    def rows(self):
        return [[self.pal[self.px[(x, y)]] if (x, y) in self.px else None for x in range(N)] for y in range(N)]

def png_bytes(rows, bg=None):
    h = len(rows); w = len(rows[0])
    raw = bytearray()
    empty = bytes(bg + (255,)) if bg else b'\x00\x00\x00\x00'
    for row in rows:
        raw += b'\x00'
        for p in row:
            raw += empty if p is None else bytes(p + (255,))
    def chunk(kind, data):
        body = kind + data
        return struct.pack('>I', len(data)) + body + struct.pack('>I', zlib.crc32(body) & 0xFFFFFFFF)
    return (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 6, 0, 0, 0))
            + chunk(b'IDAT', zlib.compress(bytes(raw), 9)) + chunk(b'IEND', b''))

def scale(rows, k):
    out = []
    for row in rows:
        big = []
        for p in row:
            big.extend([p] * k)
        for _ in range(k):
            out.append(list(big))
    return out

def sheet(sprites, k=8, gap=40, pad=40, bg=(38, 38, 42)):
    size = N * k
    w = pad * 2 + len(sprites) * size + (len(sprites) - 1) * gap
    h = pad * 2 + size
    canvas = [[bg] * w for _ in range(h)]
    for i, sp in enumerate(sprites):
        big = scale(sp.rows(), k)
        ox = pad + i * (size + gap)
        for y in range(size):
            for x in range(size):
                p = big[y][x]
                if p is not None:
                    canvas[pad + y][ox + x] = p
    return canvas

def save(path, rows, bg=None):
    with open(path, 'wb') as f:
        f.write(png_bytes(rows, bg))

def inside(poly, x, y):
    """Is the point inside the polygon (even-odd rule)?"""
    c = False
    n = len(poly)
    for i in range(n):
        (x1, y1), (x2, y2) = poly[i], poly[(i + 1) % n]
        if (y1 > y) != (y2 > y) and x < (x2 - x1) * (y - y1) / (y2 - y1) + x1:
            c = not c
    return c

def seg_dist(px, py, a, b):
    (x1, y1), (x2, y2) = a, b
    dx, dy = x2 - x1, y2 - y1
    t = max(0.0, min(1.0, ((px - x1) * dx + (py - y1) * dy) / float(dx * dx + dy * dy or 1)))
    return ((px - x1 - t * dx) ** 2 + (py - y1 - t * dy) ** 2) ** 0.5

def line_dist(pts, x, y):
    return min(seg_dist(x, y, pts[i], pts[i + 1]) for i in range(len(pts) - 1))

def bezier(p0, p1, p2, p3, n=12):
    out = []
    for i in range(n + 1):
        t = i / float(n)
        u = 1 - t
        out.append((u ** 3 * p0[0] + 3 * u * u * t * p1[0] + 3 * u * t * t * p2[0] + t ** 3 * p3[0],
                    u ** 3 * p0[1] + 3 * u * u * t * p1[1] + 3 * u * t * t * p2[1] + t ** 3 * p3[1]))
    return out
