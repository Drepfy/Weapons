"""Crush: a heavy, hard-hitting war axe.

A great crescent blade of dark forged steel that thins from a heavy body to a bright ground
edge, with its two horns sweeping back towards the haft; azure light breaks out of the steel in
cracks. A bevelled square hammer on the back for the impact, a four-sided spike on top, an iron
socket bound with bronze rings and set with an azure crystal on each face. A long wooden haft
with bronze bands, a grip wound in dark leather, and a bronze pommel holding a small crystal.
"""
import math

import lib

HEAD_Z = 12.0                 # the middle of the head
EDGE_C, EDGE_R = (-1.15, HEAD_Z), 4.75      # the cutting edge's arc (x, z centre and radius)
SPAN = 64.0                   # how far the arc runs either way (degrees)


def head_point(t, r):
    """A point on the blade: t from -1 (lower horn) to 1 (upper horn), r from the socket (0) to
    the edge (1). The back of the blade is hollowed, so the horns reach back towards the haft."""
    a = math.radians(SPAN * t)
    ex, ez = EDGE_C[0] - EDGE_R * math.cos(a), EDGE_C[1] + EDGE_R * math.sin(a)
    neck = 1.25
    ix, iz = -0.55, HEAD_Z + neck * t
    if abs(t) > 0.55:                                   # the horns curl back past the neck
        k = (abs(t) - 0.55) / 0.45
        ix -= 1.4 * k ** 1.5
        iz += math.copysign(1.6 * k, t)
    # the back of the blade curves (hollow) rather than running straight
    mx = (ix + ex) / 2 + 0.55 * (1 - abs(t))
    mz = (iz + ez) / 2
    u = r
    x = (1 - u) ** 2 * ix + 2 * u * (1 - u) * mx + u * u * ex
    z = (1 - u) ** 2 * iz + 2 * u * (1 - u) * mz + u * u * ez
    return x, z


def thickness(t, r):
    """Half the blade's thickness: heavy by the socket, thin towards the horns, a sharp bevel
    over the last part to the edge."""
    body = 0.34 * (1 - 0.45 * abs(t) ** 2)
    if r > 0.8:
        body *= max(0.0, (1 - r) / 0.2) ** 0.9
    return body * (1 - 0.25 * r)


def head(mat, coll):
    rings, uvs = [], []
    T, R = 46, 18
    rs = [k / R for k in range(R + 1)]
    for i in range(T + 1):
        t = -1 + 2 * i / T
        tt = t * 0.985
        front, back, uf, ub = [], [], [], []
        for r in rs:
            x, z = head_point(tt, r)
            h = max(0.004, thickness(tt, r) * (1 - 0.6 * max(0.0, abs(t) - 0.8) / 0.2))
            front.append((x, -h, z))
            back.append((x, h, z))
            uf.append((r, (t + 1) / 2))
            ub.append((r, (t + 1) / 2))
        ring = front + list(reversed(back[1:-1]))
        uv = uf + list(reversed(ub[1:-1]))
        rings.append(ring)
        uvs.append(uv)
    obj = lib.loft('Crush head', rings, mat, coll, uvs=uvs)
    n = len(rings[0])
    for e in obj.data.edges:
        a, b = e.vertices
        if a < len(rings) * n and b < len(rings) * n and a // n != b // n and a % n == b % n and a % n == R:
            e.use_edge_sharp = True
    return obj


def head_material():
    """Dark forged steel with broad hammer marks, a bright ground edge, and azure light in its
    cracks."""
    m = lib.material('Crush steel', lib.srgb('#3b4250'), 1.0, 0.36)
    n = lib.Nodes(m)
    u, v = n.uv()
    edge = n.new('ShaderNodeMapRange')
    n.link(u, edge.inputs['Value'])
    edge.inputs['From Min'].default_value = 0.78
    edge.inputs['From Max'].default_value = 0.83
    n.set('Base Color', n.mix(edge.outputs['Result'], lib.srgb('#3b4250'), lib.srgb('#d9dee6')))
    n.set('Roughness', n.ramp(edge.outputs['Result'], [(0.0, (0.38, 0.38, 0.38)), (1.0, (0.14, 0.14, 0.14))]))
    coord, x, y, z = n.object_coords()
    vor = n.new('ShaderNodeTexVoronoi')
    vor.feature = 'DISTANCE_TO_EDGE'
    vor.inputs['Scale'].default_value = 0.75
    n.link(coord, vor.inputs['Vector'])
    crack = n.new('ShaderNodeMapRange')
    n.link(vor.outputs['Distance'], crack.inputs['Value'])
    crack.inputs['From Min'].default_value = 0.035
    crack.inputs['From Max'].default_value = 0.0
    inside = n.new('ShaderNodeMapRange')
    n.link(u, inside.inputs['Value'])
    inside.inputs['From Min'].default_value = 0.72
    inside.inputs['From Max'].default_value = 0.6
    glow = n.math('MULTIPLY', crack.outputs['Result'], inside.outputs['Result'])
    n.set('Emission Color', lib.srgb('#3fb4ff'))
    n.set('Emission Strength', n.math('MULTIPLY', glow, 9.0))
    n.bump(n.noise(coord, 1.6, 3.0), 0.35, 0.06)
    return m


def build(coll):
    iron = lib.material('Crush iron', lib.srgb('#2b303a'), 1.0, 0.42)
    bright = lib.material('Crush bright steel', lib.srgb('#c5ccd6'), 1.0, 0.2)
    bronze = lib.material('Crush bronze', lib.srgb('#b77a3a'), 1.0, 0.32)
    crystal = lib.material('Crush crystal', lib.srgb('#59c6ff'), 0.0, 0.05, coat_weight=1.0,
                           emission_color=lib.srgb('#3fa8ff'), emission_strength=3.0)
    wood = lib.material('Crush wood', lib.srgb('#5a3820'), 0.0, 0.55)
    leather = lib.material('Crush leather', lib.srgb('#1b2740'), 0.0, 0.62)
    _wood_grain(wood)
    _hammer_marks(iron)
    parts = {'head': head(head_material(), coll)}

    # the hammer on the back: a bevelled block with a slightly bigger, battered striking face
    body = lib.lathe('Crush hammer', [(0.0, -0.95), (0.85, -0.95), (0.85, 0.95), (0.0, 0.95)], iron, coll, 4,
                     sharp=40)
    body.rotation_euler = (0, math.radians(90), math.radians(45))
    body.location = (1.35, 0.0, HEAD_Z)
    body.scale = (1.0, 0.8, 1.0)
    lib.bevel(body, 0.06, 3)
    face = lib.lathe('Crush hammer face', [(0.0, -0.16), (1.0, -0.16), (1.06, 0.0), (1.0, 0.16), (0.0, 0.16)], bright,
                     coll, 4, sharp=40)
    face.rotation_euler = (0, math.radians(90), math.radians(45))
    face.location = (2.38, 0.0, HEAD_Z)
    face.scale = (1.0, 0.8, 1.0)
    lib.bevel(face, 0.05, 3)
    parts['hammer'] = body
    parts['hammer face'] = face
    for x in (0.75, 1.9):
        band = lib.lathe(f'Crush hammer band {x}', [(0.0, -0.07), (0.92, -0.07), (0.95, 0.0), (0.92, 0.07),
                                                   (0.0, 0.07)], bronze, coll, 4)
        band.rotation_euler = (0, math.radians(90), math.radians(45))
        band.location = (x, 0.0, HEAD_Z)
        band.scale = (1.0, 0.8, 1.0)
        parts[f'hammer band {x}'] = band

    # the socket round the haft, its bronze rings, its crystals, and the spike on top
    parts['socket'] = lib.lathe('Crush socket', [(0.0, 9.9), (0.66, 9.9), (0.66, 14.1), (0.0, 14.1)], iron, coll,
                                32, sharp=50)
    lib.bevel(parts['socket'], 0.04, 2)
    for z in (9.9, 13.95):
        parts[f'ring {z}'] = lib.lathe(f'Crush ring {z}', [(0.0, z - 0.14), (0.74, z - 0.14), (0.8, z),
                                                           (0.74, z + 0.14), (0.0, z + 0.14)], bronze, coll, 32)
    for side in (-1, 1):
        gem = lib.lathe(f'Crush crystal {side}', [(0.0, 0.0), (0.36, 0.12), (0.26, 0.26), (0.0, 0.3)], crystal, coll,
                        8, sharp=10)
        gem.rotation_euler = (math.radians(90 * side), 0, 0)
        gem.location = (0.0, side * 0.6, HEAD_Z)
        parts[f'crystal {side}'] = gem
    spike = lib.lathe('Crush spike', [(0.0, 14.1), (0.5, 14.1), (0.42, 14.5), (0.0, 15.95)], bright, coll, 4,
                      sharp=20)
    spike.rotation_euler = (0, 0, math.radians(45))
    parts['spike'] = spike

    # the haft, its bands, the leather grip and the pommel
    parts['haft'] = lib.lathe('Crush haft', [(0.0, 1.3), (0.38, 1.3), (0.4, 6.0), (0.42, 9.95), (0.0, 9.95)], wood,
                              coll, 24)
    for z in (5.2, 7.7):
        parts[f'band {z}'] = lib.lathe(f'Crush band {z}', [(0.0, z - 0.18), (0.48, z - 0.18), (0.52, z),
                                                           (0.48, z + 0.18), (0.0, z + 0.18)], bronze, coll, 24)
    parts['grip'] = lib.lathe('Crush grip', [(0.0, 1.35), (0.44, 1.35), (0.44, 4.55), (0.0, 4.55)], leather, coll, 24)
    for strand in range(2):
        pts = lib.helix(1.4, 4.5, 4.0, 0.45, phase=strand * math.pi, steps_per_turn=36)
        parts[f'wrap {strand}'] = lib.tube(f'Crush wrap {strand}', pts, 0.15, leather, coll, 10, flat=0.35,
                                           normal_up=lambda p: (p[0], p[1], 0.0))
    for z in (1.35, 4.55):
        parts[f'grip ring {z}'] = lib.lathe(f'Crush grip ring {z}', [(0.0, z - 0.12), (0.52, z - 0.12), (0.56, z),
                                                                     (0.52, z + 0.12), (0.0, z + 0.12)], bronze, coll,
                                            24)
    parts['pommel'] = lib.lathe('Crush pommel', [(0.0, 0.25), (0.3, 0.25), (0.55, 0.45), (0.62, 0.8), (0.5, 1.15),
                                                 (0.38, 1.3), (0.0, 1.3)], bronze, coll, 32)
    pg = lib.lathe('Crush pommel crystal', [(0.0, 0.0), (0.2, 0.07), (0.15, 0.15), (0.0, 0.17)], crystal, coll, 8,
                   sharp=10)
    pg.rotation_euler = (math.radians(-90), 0, 0)
    pg.location = (0.0, -0.55, 0.8)
    parts['pommel crystal'] = pg
    return parts


def _wood_grain(mat):
    n = lib.Nodes(mat)
    coord, x, y, z = n.object_coords()
    wave = n.new('ShaderNodeTexWave')
    wave.wave_type = 'RINGS'
    wave.inputs['Scale'].default_value = 1.4
    wave.inputs['Distortion'].default_value = 6.0
    wave.inputs['Detail'].default_value = 3.0
    mapping = n.new('ShaderNodeMapping')
    mapping.inputs['Scale'].default_value = (3.0, 3.0, 0.15)
    n.link(coord, mapping.inputs['Vector'])
    n.link(mapping.outputs['Vector'], wave.inputs['Vector'])
    n.set('Base Color', n.ramp(wave.outputs['Fac'], [(0.0, lib.srgb('#3a2214')), (0.5, lib.srgb('#6a4426')),
                                                     (1.0, lib.srgb('#8a5e36'))]))
    n.bump(wave.outputs['Fac'], 0.1, 0.02)


def _hammer_marks(mat):
    n = lib.Nodes(mat)
    coord, x, y, z = n.object_coords()
    n.bump(n.noise(coord, 3.0, 3.0), 0.3, 0.05)
