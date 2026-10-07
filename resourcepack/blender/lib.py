"""Shared tools for the legendary weapons in Blender: the studio (lights, world, cameras, render
settings), the materials, and the geometry builders the weapons are modelled with.

Units are Minecraft model units: a weapon stands upright along +Z, 16 units tall, its grip near
the bottom, the blade's edge (or the axe's blade) towards -X and its front facing -Y.

Geometry is built from real cross-sections, not boxes:
    loft      rings of points joined into a smooth surface (blades, axe heads, collars)
    lathe     a profile turned round the Z axis, optionally oval (grips, pommels, rings)
    tube      a round (or flat) section swept along a path (candy canes, ribs, cords, wraps)
Every part is its own object, so blade, guard, handle and pommel stay separate.
"""
import math

import bpy  # noqa: I001 (bpy first: it makes addon_utils and mathutils importable)
import addon_utils
from mathutils import Matrix, Vector


# ---- the scene ---------------------------------------------------------------------------------


def reset():
    bpy.ops.wm.read_factory_settings(use_empty=True)
    addon_utils.enable('cycles', default_set=True)
    scene = bpy.context.scene
    scene.render.engine = 'CYCLES'
    scene.cycles.device = 'CPU'
    scene.cycles.samples = 96
    scene.cycles.use_denoising = True
    scene.cycles.denoiser = 'OPENIMAGEDENOISE'
    scene.cycles.max_bounces = 8
    scene.cycles.glossy_bounces = 4
    scene.render.film_transparent = True
    scene.view_settings.view_transform = 'AgX'
    scene.view_settings.look = 'AgX - Medium High Contrast'
    scene.unit_settings.system = 'NONE'
    return scene


def collection(name):
    c = bpy.data.collections.new(name)
    bpy.context.scene.collection.children.link(c)
    return c


def world():
    """A studio for metal to reflect: dark floor, a bright soft horizon band, a dimmer sky."""
    w = bpy.data.worlds.new('Studio')
    bpy.context.scene.world = w
    w.use_nodes = True
    nt = w.node_tree
    nt.nodes.clear()
    coord = nt.nodes.new('ShaderNodeTexCoord')
    sep = nt.nodes.new('ShaderNodeSeparateXYZ')
    ramp = nt.nodes.new('ShaderNodeValToRGB')
    bg = nt.nodes.new('ShaderNodeBackground')
    out = nt.nodes.new('ShaderNodeOutputWorld')
    nt.links.new(coord.outputs['Generated'], sep.inputs[0])
    mr = nt.nodes.new('ShaderNodeMapRange')
    mr.inputs['From Min'].default_value = -1.0
    mr.inputs['From Max'].default_value = 1.0
    nt.links.new(sep.outputs['Z'], mr.inputs['Value'])
    nt.links.new(mr.outputs['Result'], ramp.inputs['Fac'])
    els = ramp.color_ramp.elements
    els[0].position, els[0].color = 0.0, (0.004, 0.004, 0.005, 1)
    els[1].position, els[1].color = 0.47, (0.03, 0.03, 0.035, 1)
    for pos, col in ((0.53, (0.9, 0.92, 0.96, 1)), (0.62, (0.35, 0.37, 0.42, 1)), (1.0, (0.12, 0.13, 0.16, 1))):
        e = els.new(pos)
        e.color = col
    nt.links.new(ramp.outputs['Color'], bg.inputs['Color'])
    bg.inputs['Strength'].default_value = 0.8
    nt.links.new(bg.outputs['Background'], out.inputs['Surface'])
    return w


def area_light(name, location, target, size, energy, colour=(1, 1, 1), shape='RECTANGLE', size_y=None):
    light = bpy.data.lights.new(name, 'AREA')
    light.shape = shape
    light.size = size
    if size_y is not None:
        light.size_y = size_y
    light.energy = energy
    light.color = colour
    obj = bpy.data.objects.new(name, light)
    bpy.context.scene.collection.objects.link(obj)
    obj.location = location
    look_at(obj, target)
    return obj


def lights(centre=(0, 0, 8)):
    """Key softbox from the top left front, a cool fill from the right, two rim strips behind
    that draw bright lines along edges, and a soft top light."""
    c = Vector(centre)
    area_light('Key', c + Vector((-14, -18, 10)), c, 10, 9000, (1.0, 0.97, 0.92), 'RECTANGLE', 14)
    area_light('Fill', c + Vector((18, -14, -2)), c, 12, 2500, (0.85, 0.9, 1.0))
    area_light('RimLeft', c + Vector((-16, 14, 4)), c, 2, 3500, (1, 1, 1), 'RECTANGLE', 20)
    area_light('RimRight', c + Vector((16, 12, 6)), c, 2, 3500, (0.95, 0.97, 1), 'RECTANGLE', 20)
    area_light('Top', c + Vector((0, -2, 20)), c, 8, 1500)
    reflector('Reflector', c + Vector((-8, -40, 10)), c, (44, 26), 1.6)
    reflector('Reflector low', c + Vector((10, -36, -14)), c, (30, 10), 0.5)


def reflector(name, location, target, size, strength):
    """A big soft white card in front of the weapon that the camera never sees: what polished
    metal facing the viewer reflects, so a blade reads as bright steel and not as a black
    mirror of the dark studio."""
    me = bpy.data.meshes.new(name)
    w, h = size[0] / 2, size[1] / 2
    me.from_pydata([(-w, -h, 0), (w, -h, 0), (w, h, 0), (-w, h, 0)], [], [(0, 1, 2, 3)])
    obj = bpy.data.objects.new(name, me)
    bpy.context.scene.collection.objects.link(obj)
    obj.location = location
    look_at(obj, target)
    obj.rotation_euler.rotate_axis('X', math.pi)       # face the weapon
    mat = bpy.data.materials.new(name)
    mat.use_nodes = True
    nt = mat.node_tree
    nt.nodes.clear()
    em = nt.nodes.new('ShaderNodeEmission')
    em.inputs['Strength'].default_value = strength
    out = nt.nodes.new('ShaderNodeOutputMaterial')
    nt.links.new(em.outputs[0], out.inputs['Surface'])
    me.materials.append(mat)
    obj.visible_camera = False
    obj.visible_shadow = False
    return obj


def look_at(obj, target):
    direction = Vector(target) - obj.location
    obj.rotation_euler = direction.to_track_quat('-Z', 'Y').to_euler()


def camera(name, location, target, lens=85.0, ortho=None):
    cam = bpy.data.cameras.new(name)
    if ortho:
        cam.type = 'ORTHO'
        cam.ortho_scale = ortho
    else:
        cam.lens = lens
    cam.clip_end = 500
    obj = bpy.data.objects.new(name, cam)
    bpy.context.scene.collection.objects.link(obj)
    obj.location = location
    look_at(obj, target)
    return obj


# ---- materials ------------------------------------------------------------------------------------


def _bsdf(mat):
    return mat.node_tree.nodes['Principled BSDF']


def material(name, colour, metallic=0.0, roughness=0.5, **inputs):
    """A Principled BSDF material. Extra inputs by their Blender names with spaces as
    underscores (coat_weight=1.0, emission_strength=4.0, ...)."""
    mat = bpy.data.materials.new(name)
    mat.use_nodes = True
    b = _bsdf(mat)
    b.inputs['Base Color'].default_value = (*colour, 1.0)
    b.inputs['Metallic'].default_value = metallic
    b.inputs['Roughness'].default_value = roughness
    for key, value in inputs.items():
        socket = b.inputs[key.replace('_', ' ').title().replace('Ior', 'IOR')]
        socket.default_value = (*value, 1.0) if isinstance(value, tuple) and len(value) == 3 else value
    return mat


def srgb(h):
    """'#rrggbb' -> linear (r, g, b), as Blender's colour inputs expect."""
    h = h.lstrip('#')
    def lin(c):
        c = int(h[c:c + 2], 16) / 255.0
        return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4
    return (lin(0), lin(2), lin(4))


class Nodes:
    """A small helper for building node trees in code."""

    def __init__(self, mat):
        self.nt = mat.node_tree
        self.bsdf = _bsdf(mat)

    def new(self, kind, **values):
        n = self.nt.nodes.new(kind)
        for k, v in values.items():
            if k in n.inputs:
                n.inputs[k].default_value = v
            else:
                setattr(n, k, v)
        return n

    def link(self, a, b):
        self.nt.links.new(a, b)

    def math(self, op, a, b=None, c=None, clamp=False):
        n = self.nt.nodes.new('ShaderNodeMath')
        n.operation = op
        n.use_clamp = clamp
        for i, v in enumerate((a, b, c)):
            if v is None:
                continue
            if isinstance(v, (int, float)):
                n.inputs[i].default_value = v
            else:
                self.link(v, n.inputs[i])
        return n.outputs[0]

    def ramp(self, fac, stops, constant=False):
        n = self.nt.nodes.new('ShaderNodeValToRGB')
        if constant:
            n.color_ramp.interpolation = 'CONSTANT'
        els = n.color_ramp.elements
        els[0].position, els[0].color = stops[0][0], (*stops[0][1], 1)
        els[1].position, els[1].color = stops[-1][0], (*stops[-1][1], 1)
        for pos, col in stops[1:-1]:
            els.new(pos).color = (*col, 1)
        self.link(fac, n.inputs['Fac'])
        return n.outputs['Color']

    def mix(self, fac, a, b):
        n = self.nt.nodes.new('ShaderNodeMix')
        n.data_type = 'RGBA'
        self.link(fac, n.inputs['Factor']) if not isinstance(fac, (int, float)) else None
        if isinstance(fac, (int, float)):
            n.inputs['Factor'].default_value = fac
        for sock, v in ((n.inputs[6], a), (n.inputs[7], b)):
            if isinstance(v, tuple):
                sock.default_value = (*v, 1)
            else:
                self.link(v, sock)
        return n.outputs[2]

    def uv(self):
        n = self.nt.nodes.new('ShaderNodeUVMap')
        n.uv_map = 'UVMap'
        sep = self.nt.nodes.new('ShaderNodeSeparateXYZ')
        self.link(n.outputs['UV'], sep.inputs[0])
        return sep.outputs['X'], sep.outputs['Y']

    def object_coords(self):
        n = self.nt.nodes.new('ShaderNodeTexCoord')
        sep = self.nt.nodes.new('ShaderNodeSeparateXYZ')
        self.link(n.outputs['Object'], sep.inputs[0])
        return n.outputs['Object'], sep.outputs['X'], sep.outputs['Y'], sep.outputs['Z']

    def bump(self, height, strength=0.3, distance=0.05):
        n = self.nt.nodes.new('ShaderNodeBump')
        n.inputs['Strength'].default_value = strength
        n.inputs['Distance'].default_value = distance
        self.link(height, n.inputs['Height'])
        self.link(n.outputs['Normal'], self.bsdf.inputs['Normal'])
        return n

    def noise(self, vector=None, scale=5.0, detail=4.0, roughness=0.5):
        n = self.nt.nodes.new('ShaderNodeTexNoise')
        n.inputs['Scale'].default_value = scale
        n.inputs['Detail'].default_value = detail
        n.inputs['Roughness'].default_value = roughness
        if vector is not None:
            self.link(vector, n.inputs['Vector'])
        return n.outputs['Fac']

    def set(self, name, value):
        sock = self.bsdf.inputs[name]
        if isinstance(value, (int, float)):
            sock.default_value = value
        elif isinstance(value, tuple):
            sock.default_value = (*value, 1.0)
        else:
            self.link(value, sock)


# ---- geometry -------------------------------------------------------------------------------------


def mesh(name, verts, faces, mat=None, coll=None, smooth=True, sharp=None, uvs=None, mats=None, face_mats=None):
    """An object from vertices and faces. uvs: one (u, v) per vertex; sharp: edges sharper
    than this angle (degrees) stay crisp, the rest are shaded smooth."""
    me = bpy.data.meshes.new(name)
    me.from_pydata([tuple(v) for v in verts], [], [tuple(f) for f in faces])
    if uvs:
        layer = me.uv_layers.new(name='UVMap')
        for loop in me.loops:
            layer.data[loop.index].uv = uvs[loop.vertex_index]
    me.validate()
    me.update()
    for p in me.polygons:
        p.use_smooth = smooth
    if sharp is not None:
        me.set_sharp_from_angle(angle=math.radians(sharp))
    obj = bpy.data.objects.new(name, me)
    (coll or bpy.context.scene.collection).objects.link(obj)
    for m in (mats or ([mat] if mat else [])):
        me.materials.append(m)
    if face_mats:
        for p, i in zip(me.polygons, face_mats):
            p.material_index = i
    return obj


def loft(name, rings, mat=None, coll=None, cap_start=True, cap_end=True, tip=None, sharp=None, uvs=None):
    """Joins rings of points (each the same count, going round) into a closed surface. tip: a
    point the last ring closes to (a blade's point). uvs: per-ring list of (u, v) per point."""
    verts, faces, uv = [], [], []
    n = len(rings[0])
    for k, ring in enumerate(rings):
        verts.extend(ring)
        if uvs:
            uv.extend(uvs[k])
    for k in range(len(rings) - 1):
        for i in range(n):
            a = k * n + i
            b = k * n + (i + 1) % n
            faces.append((a, b, b + n, a + n))
    if cap_start:
        faces.append(tuple(reversed(range(n))))
    if tip is not None:
        t = len(verts)
        verts.append(tip)
        if uvs:
            uv.append(uvs[-1][0] if len(uvs) > len(rings) else (0.5, 1.0))
        last = (len(rings) - 1) * n
        for i in range(n):
            faces.append((last + i, last + (i + 1) % n, t))
    elif cap_end:
        last = (len(rings) - 1) * n
        faces.append(tuple(last + i for i in range(n)))
    return mesh(name, verts, faces, mat, coll, sharp=sharp, uvs=uv if uvs else None)


def lathe(name, profile, mat=None, coll=None, segments=32, scale=(1.0, 1.0), sharp=None, offset=(0, 0)):
    """A profile [(radius, z), ...] turned round the Z axis (oval with scale). The ends close
    when their radius is 0."""
    rings, uvs = [], []
    total = sum(math.dist(profile[i], profile[i + 1]) for i in range(len(profile) - 1)) or 1
    run = 0.0
    for k, (r, z) in enumerate(profile):
        if k:
            run += math.dist(profile[k - 1], profile[k])
        ring, uv = [], []
        for i in range(segments):
            a = 2 * math.pi * i / segments
            ring.append((offset[0] + r * math.cos(a) * scale[0], offset[1] + r * math.sin(a) * scale[1], z))
            uv.append((i / segments, run / total))
        rings.append(ring)
        uvs.append(uv)
    cap_start = profile[0][0] > 1e-6
    cap_end = profile[-1][0] > 1e-6
    return loft(name, rings, mat, coll, cap_start, cap_end, sharp=sharp, uvs=uvs)


def frames(points):
    """Tangent, normal and binormal along a path (parallel transport, no twisting)."""
    pts = [Vector(p) for p in points]
    tangents = []
    for i in range(len(pts)):
        a, b = pts[max(0, i - 1)], pts[min(len(pts) - 1, i + 1)]
        tangents.append((b - a).normalized())
    t0 = tangents[0]
    ref = Vector((0, 1, 0)) if abs(t0.y) < 0.9 else Vector((1, 0, 0))
    normal = t0.cross(ref).normalized()
    out = []
    for i, t in enumerate(tangents):
        if i:
            prev = tangents[i - 1]
            axis = prev.cross(t)
            if axis.length > 1e-9:
                angle = prev.angle(t)
                normal = (Matrix.Rotation(angle, 3, axis.normalized()) @ normal).normalized()
        binormal = t.cross(normal).normalized()
        out.append((t, normal, binormal))
    return pts, out


def tube(name, points, radius, mat=None, coll=None, segments=16, flat=1.0, caps=True, tip=False, sharp=None,
         normal_up=None):
    """A section swept along a path. radius: a number or a function of the fraction along
    (0..1). flat < 1 squashes the section (a ribbon). normal_up: a function giving, for a point,
    the direction the flat side should face (for ribbons wound on a grip)."""
    pts, fr = frames(points)
    lengths = [0.0]
    for i in range(1, len(pts)):
        lengths.append(lengths[-1] + (pts[i] - pts[i - 1]).length)
    total = lengths[-1] or 1
    rings, uvs = [], []
    for i, (p, (t, nrm, bnm)) in enumerate(zip(pts, fr)):
        f = lengths[i] / total
        r = radius(f) if callable(radius) else radius
        if normal_up is not None:
            up = Vector(normal_up(p))
            bnm = t.cross(up).normalized()
            nrm = bnm.cross(t).normalized()
        ring, uv = [], []
        for k in range(segments):
            a = 2 * math.pi * k / segments
            off = nrm * (math.cos(a) * r * flat) + bnm * (math.sin(a) * r)
            ring.append(tuple(p + off))
            uv.append((k / segments, lengths[i]))
        rings.append(ring)
        uvs.append(uv)
    end = None
    if tip:
        end = tuple(pts[-1] + fr[-1][0] * (radius(1.0) if callable(radius) else radius))
    return loft(name, rings, mat, coll, caps, caps and not tip, tip=end, sharp=sharp, uvs=uvs)


def helix(z0, z1, turns, radius, phase=0.0, steps_per_turn=24, scale=(1.0, 1.0), direction=1):
    """Points of a helix round the Z axis (radius: number or function of z)."""
    n = max(2, int(abs(turns) * steps_per_turn))
    pts = []
    for k in range(n + 1):
        f = k / n
        z = z0 + (z1 - z0) * f
        a = phase + direction * 2 * math.pi * turns * f
        r = radius(z) if callable(radius) else radius
        pts.append((r * math.cos(a) * scale[0], r * math.sin(a) * scale[1], z))
    return pts


def bezier(p0, p1, p2, p3, n=24):
    out = []
    for k in range(n + 1):
        t = k / n
        u = 1 - t
        out.append(tuple(u ** 3 * a + 3 * u * u * t * b + 3 * u * t * t * c + t ** 3 * d
                         for a, b, c, d in zip(p0, p1, p2, p3)))
    return out


# ---- modifiers --------------------------------------------------------------------------------------


def bevel(obj, width=0.03, segments=3, angle=30):
    m = obj.modifiers.new('Bevel', 'BEVEL')
    m.width = width
    m.segments = segments
    m.limit_method = 'ANGLE'
    m.angle_limit = math.radians(angle)
    return m


def subdivide(obj, levels=2):
    m = obj.modifiers.new('Subdivision', 'SUBSURF')
    m.levels = levels
    m.render_levels = levels
    return m


def boolean(obj, cutter, operation='DIFFERENCE'):
    m = obj.modifiers.new('Boolean', 'BOOLEAN')
    m.operation = operation
    m.object = cutter
    m.solver = 'EXACT'
    cutter.hide_render = True
    cutter.hide_viewport = True
    return m


def apply_all(obj):
    """Bakes an object's modifiers into its mesh (for export)."""
    dg = bpy.context.evaluated_depsgraph_get()
    ev = obj.evaluated_get(dg)
    me = bpy.data.meshes.new_from_object(ev)
    obj.modifiers.clear()
    old = obj.data
    obj.data = me
    bpy.data.meshes.remove(old)
