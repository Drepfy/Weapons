"""Katana: Japanese, clean and precise.

A long, gently curved blade with a true shinogi-zukuri cross-section: a bevelled cutting edge
rising to a crisp ridge line, a flat with a fuller groove, and a peaked spine. A frosty white
temper line rolls along the edge against mirror-polished steel, and the point (kissaki) sweeps
up to the spine. A gold collar (habaki), gold washers and a round black iron guard with a gold
rim and two openings; a handle of white ray skin wound with crimson silk in the classic diamond
pattern, gold ornaments under the silk, and a black lacquered pommel cap with a gold band.
"""
import math

import lib

Z_BLADE, Z_TIP = 5.3, 15.95
LENGTH = Z_TIP - Z_BLADE
KISSAKI = 0.86            # where the point begins (fraction of the blade)
SORI = 0.42               # how far the tip bends back towards the spine

# half-thickness across the blade, edge (0) to spine (1), as a fraction of the ridge's
F = [0.0, 0.05, 0.16, 0.32, 0.5, 0.62, 0.72, 0.78, 0.86, 0.94, 0.97, 1.0]
H = [0.0, 0.14, 0.36, 0.58, 0.78, 0.9, 1.0, 0.97, 0.94, 0.9, 0.72, 0.0]
GROOVE = {7: 0.25, 8: 0.42, 9: 0.25}       # how deep the fuller dips at those points
RIDGE = 6                                  # index of the ridge line (shinogi)


def width(s):
    return 1.2 - 0.22 * min(s, KISSAKI) / KISSAKI


def thickness(s):
    return 0.25 - 0.08 * s


def spine_x(s):
    return width(0) / 2 + SORI * s * s


def section(s):
    """The ring of points round the blade at a fraction s along it, and its (u, v) mapping."""
    z = Z_BLADE + LENGTH * s
    w = width(s)
    if s > KISSAKI:
        q = (s - KISSAKI) / (1 - KISSAKI)
        w *= math.sqrt(max(0.0, 1 - q ** 1.35))          # the point's curved edge (fukura)
    x1 = spine_x(s)
    x0 = x1 - w
    t = thickness(s) * (1 - 0.65 * max(0.0, s - KISSAKI) / (1 - KISSAKI))
    groove = max(0.0, min(1.0, (0.7 - s) / 0.08))           # the fuller runs out before the point
    front, back, uf, ub = [], [], [], []
    for i, (f, h) in enumerate(zip(F, H)):
        y = h * t / 2 * (1 - GROOVE.get(i, 0) * groove)
        x = x0 + f * w
        if i == 0:
            continue
        if i == len(F) - 1:
            spine = (x, 0.0, z)
            continue
        front.append((x, -y, z))
        back.append((x, y, z))
        uf.append((f, s))
        ub.append((f, s))
    ring = [(x0, 0.0, z)] + front + [spine] + list(reversed(back))
    uv = [(0.0, s)] + uf + [(1.0, s)] + list(reversed(ub))
    return ring, uv


def blade(mats, coll):
    steps = 90
    rings, uvs = [], []
    for k in range(steps):
        s = k / steps * 0.995
        r, u = section(s)
        rings.append(r)
        uvs.append(u)
    tip = (spine_x(1.0), 0.0, Z_TIP)
    obj = lib.loft('Katana blade', rings, mats['steel'], coll, cap_start=True, cap_end=False, tip=tip, uvs=uvs)
    n = len(rings[0])
    sharp = {0, 1 + RIDGE - 1, n - 1 - (RIDGE - 1), len(F) - 2}   # edge, ridge lines, spine peak
    sharp |= {len(F) - 3, n - (len(F) - 3)}                        # the spine's facets
    me = obj.data
    for e in me.edges:
        a, b = e.vertices
        if a // n != b // n and a % n == b % n and a % n in sharp:
            e.use_edge_sharp = True
    return obj


def blade_material():
    """Mirror-polished steel with a frosty white hardened edge behind a rolling temper line
    (hamon), and a darker burnished flat above the ridge."""
    m = lib.material('Katana steel', lib.srgb('#9aa3ae'), 1.0, 0.16)
    n = lib.Nodes(m)
    u, v = n.uv()
    wave = n.math('ADD', n.math('MULTIPLY', n.math('SINE', n.math('MULTIPLY', v, 52.0)), 0.045),
                  n.math('MULTIPLY', n.math('SINE', n.math('MULTIPLY', v, 131.0)), 0.018))
    line = n.math('ADD', wave, 0.27)
    edge = n.math('SMOOTH_MIN', n.math('SUBTRACT', line, u), 0.0, 0.0)     # >0 inside the edge
    hard = n.new('ShaderNodeMapRange')
    n.link(n.math('SUBTRACT', line, u), hard.inputs['Value'])
    hard.inputs['From Min'].default_value = -0.012
    hard.inputs['From Max'].default_value = 0.03
    flat = n.new('ShaderNodeMapRange')
    n.link(u, flat.inputs['Value'])
    flat.inputs['From Min'].default_value = 0.72
    flat.inputs['From Max'].default_value = 0.74
    colour = n.mix(hard.outputs['Result'], lib.srgb('#a7afba'), lib.srgb('#e9edf2'))
    colour = n.mix(flat.outputs['Result'], colour, lib.srgb('#6c7380'))
    n.set('Base Color', colour)
    rough = n.ramp(hard.outputs['Result'], [(0.0, (0.16, 0.16, 0.16)), (1.0, (0.34, 0.34, 0.34))])
    n.set('Roughness', rough)
    # fine polishing lines along the blade
    coord, x, y, z = n.object_coords()
    scale = n.new('ShaderNodeMapping')
    scale.inputs['Scale'].default_value = (60.0, 60.0, 1.5)
    n.link(coord, scale.inputs['Vector'])
    n.bump(n.noise(scale.outputs['Vector'], 4.0, 2.0), 0.08, 0.01)
    del edge
    return m


def build(coll):
    mats = {
        'steel': blade_material(),
        'gold': lib.material('Katana gold', lib.srgb('#e3ad4c'), 1.0, 0.26),
        'iron': lib.material('Katana guard iron', lib.srgb('#17161b'), 0.85, 0.42),
        'lacquer': lib.material('Katana lacquer', lib.srgb('#0d0c10'), 0.0, 0.18, coat_weight=1.0,
                                coat_roughness=0.05),
        'silk': lib.material('Katana silk', lib.srgb('#9e1124'), 0.0, 0.5, sheen_weight=0.6,
                             sheen_tint=(1.0, 0.55, 0.55)),
        'ray': lib.material('Katana ray skin', lib.srgb('#e8e1d0'), 0.0, 0.55),
    }
    _ray_skin(mats['ray'])
    _hammered(mats['iron'])
    parts = {'blade': blade(mats, coll)}

    # the gold collar on the blade, a little bigger than the blade at its base
    rings = []
    for z, grow in ((4.62, 1.0), (4.66, 1.06), (5.3, 1.04), (5.4, 0.96)):
        ring, _ = section(0.0)
        cx = sum(p[0] for p in ring) / len(ring)
        rings.append([((p[0] - cx) * 1.14 * grow + cx, p[1] * 1.9 * grow, z) for p in ring])
    parts['habaki'] = lib.loft('Katana habaki', rings, mats['gold'], coll, sharp=40)
    lib.bevel(parts['habaki'], 0.02, 2)

    # gold washers and the round guard
    for name, z0 in (('Katana washer 1', 4.54), ('Katana washer 2', 4.22)):
        w = lib.lathe(name, [(0.0, z0), (0.95, z0), (1.0, z0 + 0.04), (0.95, z0 + 0.08), (0.0, z0 + 0.08)],
                      mats['gold'], coll, 48, (1.0, 0.82))
        parts[name.split()[-2] + name[-1]] = w
    guard = lib.lathe('Katana guard', [(0.0, 4.3), (1.48, 4.3), (1.48, 4.54), (0.0, 4.54)], mats['iron'], coll,
                      64, (1.0, 0.86), sharp=40)
    lib.bevel(guard, 0.035, 3)
    rim = lib.tube('Katana guard rim', [(1.52 * math.cos(a), 1.52 * 0.86 * math.sin(a), 4.42)
                                        for a in [2 * math.pi * k / 96 for k in range(97)]],
                   0.14, mats['gold'], coll, 12, flat=0.9, caps=False)
    for side in (-1, 1):                              # the two openings (hitsu-ana)
        cut = lib.lathe('cut', [(0.0, 4.1), (0.26, 4.1), (0.26, 4.8), (0.0, 4.8)], None, coll, 24, (1.0, 0.55),
                        offset=(side * 0.92, 0.0))
        lib.boolean(guard, cut)
    parts['guard'] = guard
    parts['guard rim'] = rim

    # the handle: collar, ray skin core, silk wrap, ornaments, pommel cap
    parts['fuchi'] = lib.lathe('Katana fuchi', [(0.0, 3.92), (0.55, 3.92), (0.58, 3.98), (0.6, 4.22),
                                                (0.0, 4.22)], mats['lacquer'], coll, 48, (1.0, 0.78), sharp=40)
    lib.bevel(parts['fuchi'], 0.02, 2)
    parts['fuchi band'] = lib.lathe('Katana fuchi band', [(0.0, 3.94), (0.585, 3.94), (0.61, 4.02), (0.0, 4.02)],
                                    mats['gold'], coll, 48, (1.0, 0.78))
    core = lambda z: 0.5 - 0.03 * math.sin(math.pi * (z - 0.9) / 3.0)
    profile = [(0.0, 0.88)] + [(core(z), z) for z in [0.88 + 3.06 * k / 24 for k in range(25)]] + [(0.0, 3.94)]
    parts['grip'] = lib.lathe('Katana grip', profile, mats['ray'], coll, 48, (1.0, 0.78))
    for direction in (1, -1):
        for strand in range(2):
            pts = lib.helix(1.02, 3.8, 2.6, lambda z: core(z) + 0.035, phase=-math.pi / 2 + strand * math.pi,
                            steps_per_turn=48, scale=(1.0, 0.78), direction=direction)
            ribbon = lib.tube(f'Katana silk {direction} {strand}', pts, 0.12, mats['silk'], coll, 10, flat=0.3,
                              normal_up=lambda p: (p[0], p[1] / 0.78, 0.0))
            parts[f'silk {direction} {strand}'] = ribbon
    for side in (-1, 1):
        orn = lib.lathe(f'Katana menuki {side}', [(0.0, -0.36), (0.16, -0.3), (0.2, 0.0), (0.16, 0.3), (0.0, 0.36)],
                        mats['gold'], coll, 24, (1.0, 0.45))
        orn.location = (0.0, side * 0.4, 2.4)
        orn.rotation_euler = (math.radians(90), 0, 0)
        orn.scale = (1.0, 1.0, 1.0)
        parts[f'menuki {side}'] = orn
    parts['pommel'] = lib.lathe('Katana kashira', [(0.0, 0.42), (0.36, 0.42), (0.5, 0.5), (0.56, 0.65),
                                                   (0.56, 0.9), (0.0, 0.9)], mats['lacquer'], coll, 48,
                                (1.0, 0.78), sharp=50)
    lib.bevel(parts['pommel'], 0.02, 2)
    parts['pommel band'] = lib.lathe('Katana kashira band', [(0.0, 0.8), (0.575, 0.8), (0.6, 0.85),
                                                             (0.575, 0.9), (0.0, 0.9)], mats['gold'], coll, 48,
                                     (1.0, 0.78))
    return parts


def _ray_skin(mat):
    """White ray skin: a field of tiny rounded nodules."""
    n = lib.Nodes(mat)
    coord, x, y, z = n.object_coords()
    vor = n.new('ShaderNodeTexVoronoi')
    vor.inputs['Scale'].default_value = 26.0
    vor.feature = 'F1'
    n.link(coord, vor.inputs['Vector'])
    n.bump(n.math('SUBTRACT', 1.0, vor.outputs['Distance']), 0.35, 0.02)


def _hammered(mat):
    """Forged iron: soft hammer dents."""
    n = lib.Nodes(mat)
    coord, x, y, z = n.object_coords()
    n.bump(n.noise(coord, 6.0, 3.0), 0.25, 0.03)
