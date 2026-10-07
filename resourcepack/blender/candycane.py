"""Candy Cane: red, white and holiday.

A broad, double-edged blade of glossy hard candy: a rounded central ridge falling to two keen
edges, bold red stripes twisting across clear white candy under a high-gloss coat. The guard is
a pair of real candy canes curling down round the hand, striped all the way round, with a
sprig of holly (two leaves, three berries) on a gold boss where they meet; a gold collar, a
deep green grip wound with gold cord, and a peppermint swirl as the pommel.
"""
import math

import lib

Z_BLADE, Z_TIP = 5.2, 15.95
LENGTH = Z_TIP - Z_BLADE
ACROSS = [k / 10 for k in range(-10, 11)]          # edge (-1) to edge (1)


def width(s):
    w = 1.72 + 0.22 * math.sin(math.pi * min(1.0, s / 0.62))
    if s > 0.68:
        w *= math.sqrt(max(0.0, 1 - ((s - 0.68) / 0.32) ** 1.7))
    return w


def section(s):
    z = Z_BLADE + LENGTH * s
    w = width(s)
    t = 0.42 * (1 - 0.55 * s)
    front, back, uf, ub = [], [], [], []
    for f in ACROSS:
        # a rounded ridge in the middle and a keen bevel at each edge
        a = abs(f)
        h = t / 2 * (1 - a ** 1.8) ** 0.75
        x = f * w / 2
        front.append((x, -h, z))
        back.append((x, h, z))
        uf.append(((f + 1) / 2, s))
        ub.append(((f + 1) / 2, s))
    ring = front + list(reversed(back[1:-1]))
    uv = uf + list(reversed(ub[1:-1]))
    return ring, uv


def blade(mat, coll):
    rings, uvs = [], []
    steps = 80
    for k in range(steps):
        s = k / steps * 0.996
        r, u = section(s)
        rings.append(r)
        uvs.append(u)
    obj = lib.loft('Candy blade', rings, mat, coll, cap_start=True, cap_end=False, tip=(0.0, 0.0, Z_TIP), uvs=uvs)
    n = len(rings[0])
    edges = {0, len(ACROSS) - 1}
    for e in obj.data.edges:
        a, b = e.vertices
        if a // n != b // n and a % n == b % n and a % n in edges:
            e.use_edge_sharp = True
    return obj


def candy(name, stripes):
    """Glossy hard candy with red stripes: stripes(n) gives the stripe position (0..1 repeating)
    from the material's node helper. A clear coat on top, a little light passing into it."""
    m = lib.material(name, lib.srgb('#f6efec'), 0.0, 0.28, coat_weight=1.0, coat_roughness=0.03,
                     subsurface_weight=0.25, subsurface_scale=0.05)
    n = lib.Nodes(m)
    p = stripes(n)
    col = n.ramp(p, [(0.0, lib.srgb('#d4102c')), (0.30, lib.srgb('#d4102c')), (0.315, lib.srgb('#fbf5f2')),
                     (0.60, lib.srgb('#fbf5f2')), (0.615, lib.srgb('#e0263f')), (0.68, lib.srgb('#e0263f')),
                     (0.695, lib.srgb('#fbf5f2')), (1.0, lib.srgb('#fbf5f2'))])
    n.set('Base Color', col)
    return m


def _blade_stripes(n):
    coord, x, y, z = n.object_coords()
    return n.math('FRACT', n.math('MULTIPLY', n.math('ADD', z, n.math('MULTIPLY', x, 1.1)), 0.55))


def _cane_stripes(n):
    u, v = n.uv()
    return n.math('FRACT', n.math('ADD', n.math('MULTIPLY', v, 1.3), u))


def _peppermint(n):
    coord, x, y, z = n.object_coords()
    angle = n.math('DIVIDE', n.math('ARCTAN2', y, x), 2 * math.pi)
    r = n.math('SQRT', n.math('ADD', n.math('MULTIPLY', x, x), n.math('MULTIPLY', y, y)))
    return n.math('FRACT', n.math('MULTIPLY', n.math('ADD', angle, n.math('MULTIPLY', r, 0.55)), 6.0))


def build(coll):
    gold = lib.material('Candy gold', lib.srgb('#e8b450'), 1.0, 0.24)
    green = lib.material('Candy velvet', lib.srgb('#0f4a2a'), 0.0, 0.75, sheen_weight=0.8,
                         sheen_tint=(0.5, 1.0, 0.6))
    holly = lib.material('Holly', lib.srgb('#1d6a33'), 0.0, 0.3, coat_weight=0.6)
    berry = lib.material('Berry', lib.srgb('#c4091f'), 0.0, 0.15, coat_weight=1.0, coat_roughness=0.02)
    parts = {'blade': blade(candy('Candy blade', _blade_stripes), coll)}
    cane_mat = candy('Candy cane', _cane_stripes)

    # gold collar at the base of the blade
    parts['collar'] = lib.lathe('Candy collar', [(0.0, 4.86), (1.02, 4.86), (1.12, 4.96), (1.12, 5.2), (1.0, 5.3),
                                                (0.0, 5.3)], gold, coll, 48, (1.0, 0.36), sharp=50)
    lib.bevel(parts['collar'], 0.025, 2)

    # the two candy canes of the guard, out to each side and curling down round the hand
    for side in (-1, 1):
        pts = [(side * 0.2, 0.0, 4.72), (side * 1.85, 0.0, 4.72)]
        c = (side * 1.85, 0.0, 4.16)
        for k in range(1, 25):
            a = math.radians(90 - 200 * k / 24)
            pts.append((c[0] + side * 0.56 * math.cos(a), 0.0, c[2] + 0.56 * math.sin(a)))
        parts[f'cane {side}'] = lib.tube(f'Candy cane {side}', pts, 0.24, cane_mat, coll, 20)

    # gold boss with a sprig of holly on the front
    boss = lib.lathe('Candy boss', [(0.0, -0.3), (0.42, -0.3), (0.48, -0.22), (0.48, 0.22), (0.42, 0.3),
                                    (0.0, 0.3)], gold, coll, 40, sharp=50)
    boss.rotation_euler = (math.radians(90), 0, 0)
    boss.location = (0.0, 0.0, 4.72)
    lib.bevel(boss, 0.03, 2)
    parts['boss'] = boss
    for k, (ang, size) in enumerate(((150, 1.0), (25, 0.92))):
        parts[f'leaf {k}'] = _leaf(f'Holly leaf {k}', holly, coll, (0.0, -0.36, 4.74), math.radians(ang), size)
    for k, (dx, dz) in enumerate(((-0.12, 4.66), (0.14, 4.7), (0.0, 4.88))):
        b = lib.lathe(f'Berry {k}', [(0.0, -0.13), (0.09, -0.11), (0.13, 0.0), (0.09, 0.11), (0.0, 0.13)], berry,
                      coll, 20)
        b.location = (dx, -0.5, dz)
        parts[f'berry {k}'] = b

    # the grip: deep green velvet wound with gold cord
    parts['grip'] = lib.lathe('Candy grip', [(0.0, 1.5), (0.46, 1.5), (0.43, 3.0), (0.46, 4.5), (0.0, 4.5)], green,
                              coll, 40)
    for strand in range(2):
        pts = lib.helix(1.55, 4.45, 5.0, 0.47, phase=strand * math.pi, steps_per_turn=40)
        parts[f'cord {strand}'] = lib.tube(f'Candy cord {strand}', pts, 0.07, gold, coll, 10)
    parts['grip collar'] = lib.lathe('Candy grip collar', [(0.0, 4.4), (0.56, 4.4), (0.6, 4.5), (0.56, 4.62),
                                                          (0.0, 4.62)], gold, coll, 40)
    parts['pommel collar'] = lib.lathe('Candy pommel collar', [(0.0, 1.32), (0.5, 1.32), (0.56, 1.42),
                                                              (0.5, 1.56), (0.0, 1.56)], gold, coll, 40)

    # the peppermint pommel, facing the front
    pm = lib.lathe('Peppermint', [(0.0, -0.26), (0.78, -0.26), (0.9, -0.18), (0.95, 0.0), (0.9, 0.18), (0.78, 0.26),
                                  (0.0, 0.26)], candy('Peppermint', _peppermint), coll, 64)
    pm.rotation_euler = (math.radians(90), 0, 0)
    pm.location = (0.0, 0.0, 0.62)
    parts['pommel'] = pm
    return parts


def _leaf(name, mat, coll, at, angle, size):
    """A holly leaf: a flat, slightly cupped blade with spiky lobes along its edge."""
    pts = []
    n = 28
    for k in range(n + 1):
        t = k / n
        half = 0.17 * math.sin(math.pi * t) * size
        spike = 1 + 0.35 * max(0.0, math.cos(t * math.pi * 6)) ** 6
        pts.append((t * 0.62 * size, half * spike))
    outline = pts + [(x, -y) for x, y in reversed(pts[1:-1])]
    verts = [(x, 0.03 * (1 - (2 * abs(y) / 0.2)), y) for x, y in outline]
    verts.append((0.31 * size, -0.04, 0.0))
    c = len(verts) - 1
    faces = [(i, (i + 1) % c, c) for i in range(c)]
    obj = lib.mesh(name, verts, faces, mat, coll)
    solid = obj.modifiers.new('Solidify', 'SOLIDIFY')
    solid.thickness = 0.04
    obj.location = at
    obj.rotation_euler = (0, -angle, 0)
    return obj
