"""A small software renderer for the weapon models (boxes with textured faces), used to draw
the showcase and to check the exported models without starting Minecraft.

Orthographic camera, flat lighting per face, nearest-pixel textures, transparent texels cut out
(like Minecraft), drawn at twice the size and scaled down for clean edges.
"""
import math

UNIT = 0.5

# (normal, corner order top-left, top-right, bottom-left) for each face, from the box (x0..x1, ...)
FACES = {
    'south': ((0, 0, 1), lambda a, b: ((a[0], b[1], b[2]), (b[0], b[1], b[2]), (a[0], a[1], b[2]))),
    'north': ((0, 0, -1), lambda a, b: ((b[0], b[1], a[2]), (a[0], b[1], a[2]), (b[0], a[1], a[2]))),
    'east': ((1, 0, 0), lambda a, b: ((b[0], b[1], b[2]), (b[0], b[1], a[2]), (b[0], a[1], b[2]))),
    'west': ((-1, 0, 0), lambda a, b: ((a[0], b[1], a[2]), (a[0], b[1], b[2]), (a[0], a[1], a[2]))),
    'up': ((0, 1, 0), lambda a, b: ((a[0], b[1], a[2]), (b[0], b[1], a[2]), (a[0], b[1], b[2]))),
    'down': ((0, -1, 0), lambda a, b: ((a[0], a[1], b[2]), (b[0], a[1], b[2]), (a[0], a[1], a[2]))),
}


def _rotation(rx, ry, rz):
    """Rotate about z, then y, then x (degrees). Returns a function on (x, y, z)."""
    cz, sz = math.cos(math.radians(rz)), math.sin(math.radians(rz))
    cy, sy = math.cos(math.radians(ry)), math.sin(math.radians(ry))
    cx, sx = math.cos(math.radians(rx)), math.sin(math.radians(rx))

    def rot(p):
        x, y, z = p
        x, y = x * cz - y * sz, x * sz + y * cz
        x, z = x * cy + z * sy, -x * sy + z * cy
        y, z = y * cx - z * sx, y * sx + z * cx
        return x, y, z
    return rot


def _texel(t0, t1, f):
    t = (t0 + f * (t1 - t0)) / UNIT
    return (math.ceil(t) - 1) if t1 < t0 else math.floor(t)


def render(model, texture, size=320, rx=0, ry=0, rz=0, light=(-0.45, 0.55, 0.7), zoom=1.0, ss=2):
    """Draws the model; returns rows of (r, g, b, a). texture: 32 rows of (r, g, b) or None."""
    rot = _rotation(rx, ry, rz)
    big = size * ss
    scale = big / 23.0 * zoom                       # the 16x16 diagonal fits with a margin
    lx, ly, lz = light
    ln = math.sqrt(lx * lx + ly * ly + lz * lz)
    lx, ly, lz = lx / ln, ly / ln, lz / ln
    color = [[None] * big for _ in range(big)]
    zbuf = [[-1e9] * big for _ in range(big)]

    def screen(p):
        x, y, z = rot((p[0] - 8, p[1] - 8, p[2] - 8))
        return big / 2 + x * scale, big / 2 - y * scale, z

    for element in model['elements']:
        a, b = element['from'], element['to']
        for name, face in element['faces'].items():
            normal, corners = FACES[name]
            nx, ny, nz = rot(normal)
            if nz <= 1e-6:
                continue                             # facing away
            shade = 0.5 + 0.5 * max(0.0, nx * lx + ny * ly + nz * lz)
            tl, tr, bl = (screen(c) for c in corners(a, b))
            ex, ey, ez = tr[0] - tl[0], tr[1] - tl[1], tr[2] - tl[2]
            fx, fy, fz = bl[0] - tl[0], bl[1] - tl[1], bl[2] - tl[2]
            det = ex * fy - ey * fx
            if abs(det) < 1e-9:
                continue
            u0, v0, u1, v1 = face['uv']
            xs = (tl[0], tr[0], bl[0], tr[0] + fx)
            ys = (tl[1], tr[1], bl[1], tr[1] + fy)
            for py in range(max(0, int(min(ys))), min(big, int(max(ys)) + 1)):
                cy = py + 0.5 - tl[1]
                for px in range(max(0, int(min(xs))), min(big, int(max(xs)) + 1)):
                    cx = px + 0.5 - tl[0]
                    s = (cx * fy - cy * fx) / det
                    if s < 0 or s >= 1:
                        continue
                    t = (ex * cy - ey * cx) / det
                    if t < 0 or t >= 1:
                        continue
                    depth = tl[2] + s * ez + t * fz
                    if depth <= zbuf[py][px]:
                        continue
                    tx = min(31, max(0, _texel(u0, u1, s)))
                    ty = min(31, max(0, _texel(v0, v1, t)))
                    rgb = texture[ty][tx]
                    if rgb is None:
                        continue
                    zbuf[py][px] = depth
                    color[py][px] = (min(255, int(rgb[0] * shade)), min(255, int(rgb[1] * shade)),
                                     min(255, int(rgb[2] * shade)))
    out = []
    for y in range(size):
        row = []
        for x in range(size):
            r = g = bb = n = 0
            for dy in range(ss):
                for dx in range(ss):
                    c = color[y * ss + dy][x * ss + dx]
                    if c is not None:
                        r += c[0]; g += c[1]; bb += c[2]; n += 1
            row.append((r // n, g // n, bb // n, 255 * n // (ss * ss)) if n else None)
        out.append(row)
    return out


def composite(images, bg=(30, 30, 34), gap=24, pad=32):
    """Lays rendered images side by side on a background; returns rows of (r, g, b)."""
    h = max(len(img) for img in images)
    w = sum(len(img[0]) for img in images) + gap * (len(images) - 1) + 2 * pad
    canvas = [[bg] * w for _ in range(h + 2 * pad)]
    ox = pad
    for img in images:
        for y, row in enumerate(img):
            for x, p in enumerate(row):
                if p is None:
                    continue
                a = p[3] / 255.0
                b = canvas[pad + y][ox + x]
                canvas[pad + y][ox + x] = tuple(int(p[i] * a + b[i] * (1 - a)) for i in range(3))
        ox += len(img[0]) + gap
    return canvas
