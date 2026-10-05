"""Draws a Minecraft item model (a JSON dict plus its texture) without Minecraft: for the
showcase pictures and to check the exported models.

Orthographic camera, element rotations like Minecraft, nearest-texel sampling, transparent texels
cut out, flat light per face, drawn larger and scaled down for smooth edges.
"""
import math

def corners(direction, a, b):
    """Top-left, top-right and bottom-left corners of a box face, as Minecraft maps a texture onto
    it (u runs top-left to top-right, v top-left to bottom-left, seen from outside)."""
    (x0, y0, z0), (x1, y1, z1) = a, b
    return {
        'south': ((x0, y1, z1), (x1, y1, z1), (x0, y0, z1)),
        'north': ((x1, y1, z0), (x0, y1, z0), (x1, y0, z0)),
        'east': ((x1, y1, z1), (x1, y1, z0), (x1, y0, z1)),
        'west': ((x0, y1, z0), (x0, y1, z1), (x0, y0, z0)),
        'up': ((x0, y1, z0), (x1, y1, z0), (x0, y1, z1)),
        'down': ((x0, y0, z1), (x1, y0, z1), (x0, y0, z0)),
    }[direction]


def rotate(p, rot):
    """Turns a point by an element rotation (axis, angle in degrees, origin), right-handed like
    Minecraft."""
    if not rot:
        return p
    axis, angle, (ox, oy, oz) = rot
    c, s = math.cos(math.radians(angle)), math.sin(math.radians(angle))
    x, y, z = p[0] - ox, p[1] - oy, p[2] - oz
    if axis == 'x':
        y, z = y * c - z * s, y * s + z * c
    elif axis == 'y':
        z, x = z * c - x * s, z * s + x * c
    else:
        x, y = x * c - y * s, x * s + y * c
    return x + ox, y + oy, z + oz




def _euler(rx, ry, rz):
    """Minecraft's display rotation: z first, then y, then x (degrees)."""
    def rot(p):
        x, y, z = p
        c, s = math.cos(math.radians(rz)), math.sin(math.radians(rz))
        x, y = x * c - y * s, x * s + y * c
        c, s = math.cos(math.radians(ry)), math.sin(math.radians(ry))
        x, z = x * c + z * s, -x * s + z * c
        c, s = math.cos(math.radians(rx)), math.sin(math.radians(rx))
        y, z = y * c - z * s, y * s + z * c
        return x, y, z
    return rot


def _normalize(v):
    n = math.sqrt(sum(c * c for c in v)) or 1.0
    return tuple(c / n for c in v)


DEFAULT_UV = {
    'north': lambda a, b: [16 - b[0], 16 - b[1], 16 - a[0], 16 - a[1]],
    'south': lambda a, b: [a[0], 16 - b[1], b[0], 16 - a[1]],
    'east': lambda a, b: [16 - b[2], 16 - b[1], 16 - a[2], 16 - a[1]],
    'west': lambda a, b: [a[2], 16 - b[1], b[2], 16 - a[1]],
    'up': lambda a, b: [a[0], a[2], b[0], b[2]],
    'down': lambda a, b: [a[0], 16 - b[2], b[0], 16 - a[2]],
}


def _face_uv(face, direction, a, b):
    """The texture coordinates at a face's top-left, top-right and bottom-left corners, with the
    face's texture rotation (0, 90, 180, 270) applied the way Minecraft does."""
    u0, v0, u1, v1 = face.get('uv') or DEFAULT_UV[direction](a, b)
    quad = [(u0, v0), (u0, v1), (u1, v1), (u1, v0)]          # vertex order: TL, BL, BR, TR
    k = (face.get('rotation', 0) // 90) % 4
    return quad[k % 4], quad[(3 + k) % 4], quad[(1 + k) % 4]


def render(model, texture, size=400, rotation=(0, 0, 0), zoom=1.0, ss=3, light=(-0.5, 0.6, 0.65),
           ambient=0.52, frame=23.0, scale=1.0, translate=(0, 0, 0)):
    """Returns rows of (r, g, b, a) or None. texture: rows, or {'#key': rows} for models with
    several textures. rotation: display rotation [x, y, z]. frame: how many model units fit across
    the picture (at zoom 1). scale and translate: a display transform's (translate after turning)."""
    view = _euler(*rotation)
    textures = texture if isinstance(texture, dict) else {'#0': texture}
    big = size * ss
    k = big / frame * zoom
    lx, ly, lz = _normalize(light)
    color = [[None] * big for _ in range(big)]
    zbuf = [[-1e9] * big for _ in range(big)]

    def screen(p):
        x, y, z = view(((p[0] - 8) * scale, (p[1] - 8) * scale, (p[2] - 8) * scale))
        x, y, z = x + translate[0], y + translate[1], z + translate[2]
        return big / 2 + x * k, big / 2 - y * k, z

    for e in model['elements']:
        r = e.get('rotation')
        rot = (r['axis'], r.get('angle', 0), tuple(r.get('origin', (8, 8, 8)))) if r and r.get('axis') else None
        a, b = tuple(e['from']), tuple(e['to'])
        for direction, face in e['faces'].items():
            tl, tr, bl = (rotate(c, rot) for c in corners(direction, a, b))
            # the face normal from its corners (right-handed: u x v points out of the face... use cross)
            ux, uy, uz = (tr[i] - tl[i] for i in range(3))
            vx, vy, vz = (bl[i] - tl[i] for i in range(3))
            n = _normalize((vy * uz - vz * uy, vz * ux - vx * uz, vx * uy - vy * ux))
            nx, ny, nz = view(n)
            if nz <= 1e-6:
                continue
            shade = ambient + (1 - ambient) * max(0.0, nx * lx + ny * ly + nz * lz)
            p0, p1, p2 = screen(tl), screen(tr), screen(bl)
            ex, ey, ez = p1[0] - p0[0], p1[1] - p0[1], p1[2] - p0[2]
            fx, fy, fz = p2[0] - p0[0], p2[1] - p0[1], p2[2] - p0[2]
            det = ex * fy - ey * fx
            if abs(det) < 1e-9:
                continue
            tex = textures.get(face.get('texture', '#0'))
            if tex is None:
                continue
            tex_h, tex_w = len(tex), len(tex[0])
            (ua, va), (ub, vb), (uc, vc) = _face_uv(face, direction, a, b)
            du_s, dv_s, du_t, dv_t = ub - ua, vb - va, uc - ua, vc - va
            xs = (p0[0], p1[0], p2[0], p1[0] + fx)
            ys = (p0[1], p1[1], p2[1], p1[1] + fy)
            for py in range(max(0, int(min(ys))), min(big, int(max(ys)) + 1)):
                cy = py + 0.5 - p0[1]
                zrow, crow = zbuf[py], color[py]
                for px in range(max(0, int(min(xs))), min(big, int(max(xs)) + 1)):
                    cx = px + 0.5 - p0[0]
                    s = (cx * fy - cy * fx) / det
                    if s < 0 or s >= 1:
                        continue
                    t = (ex * cy - ey * cx) / det
                    if t < 0 or t >= 1:
                        continue
                    depth = p0[2] + s * ez + t * fz
                    if depth <= zrow[px]:
                        continue
                    tx = min(tex_w - 1, max(0, int((ua + s * du_s + t * du_t) / 16 * tex_w)))
                    ty = min(tex_h - 1, max(0, int((va + s * dv_s + t * dv_t) / 16 * tex_h)))
                    c = tex[ty][tx]
                    if c is None or c[3] < 128:
                        continue
                    zrow[px] = depth
                    crow[px] = (c[0] * shade, c[1] * shade, c[2] * shade)
    out = []
    n = ss * ss
    for y in range(size):
        row = []
        for x in range(size):
            r = g = bl = cnt = 0
            for dy in range(ss):
                line = color[y * ss + dy]
                for dx in range(ss):
                    c = line[x * ss + dx]
                    if c is not None:
                        r += c[0]; g += c[1]; bl += c[2]; cnt += 1
            row.append((min(255, int(r / cnt)), min(255, int(g / cnt)), min(255, int(bl / cnt)), 255 * cnt // n)
                       if cnt else None)
        out.append(row)
    return out


def composite(images, bg=(30, 30, 34), gap=24, pad=32, bg2=None):
    """Lays pictures side by side on a background (a soft vertical gradient to bg2 if given)."""
    h = max(len(img) for img in images)
    w = sum(len(img[0]) for img in images) + gap * (len(images) - 1) + 2 * pad
    H = h + 2 * pad
    canvas = []
    for y in range(H):
        c = bg if bg2 is None else tuple(int(bg[i] + (bg2[i] - bg[i]) * y / (H - 1)) for i in range(3))
        canvas.append([c] * w)
    ox = pad
    for img in images:
        oy = pad + (h - len(img)) // 2
        for y, row in enumerate(img):
            line = canvas[oy + y]
            for x, p in enumerate(row):
                if p is None:
                    continue
                a = p[3] / 255.0
                b = line[ox + x]
                line[ox + x] = tuple(int(p[i] * a + b[i] * (1 - a)) for i in range(3))
        ox += len(img[0]) + gap
    return canvas
