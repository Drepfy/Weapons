"""Build the 3D weapon models in Blender.

Every texture pixel becomes a block with its own thickness (thin sharp blade
edges, thick guards/grips, raised gems), textured with the pixel texture using
nearest-neighbour filtering - the same look as Minecraft held items.

Run:  blender -b -P build_blend.py      (or with the `bpy` Python module)
Outputs weapons.blend, glb/<name>.glb and showcase.png next to this file.
"""
import math
import os
import sys

import bpy  # noqa: I001  (bpy must load before bmesh)
import bmesh

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from designs import PALETTE, WEAPONS  # noqa: E402
from preview import hex_rgb, texture  # noqa: E402

PX = 1 / 16  # one texture pixel = 1/16 of a Blender unit, like Minecraft


def build_mesh(name, canvas):
    bm = bmesh.new()
    uv = bm.loops.layers.uv.new("UVMap")
    W, H = canvas.w, canvas.h

    def quad(verts, x, y):
        vs = [bm.verts.new(v) for v in verts]
        f = bm.faces.new(vs)
        u0, v0 = (x + 0.15) / W, 1 - (y + 0.85) / H
        u1, v1 = (x + 0.85) / W, 1 - (y + 0.15) / H
        for loop, (a, b) in zip(f.loops, ((u0, v0), (u1, v0), (u1, v1), (u0, v1))):
            loop[uv].uv = (a, b)

    for (x, y), (k, d) in canvas.px.items():
        if d <= 0:
            d = 1
        X0, X1 = (x - W / 2) * PX, (x + 1 - W / 2) * PX
        Z0, Z1 = (H - y - 1) * PX, (H - y) * PX
        hd = d * PX / 2
        # front (-Y) and back (+Y)
        quad([(X0, -hd, Z0), (X1, -hd, Z0), (X1, -hd, Z1), (X0, -hd, Z1)], x, y)
        quad([(X1, hd, Z0), (X0, hd, Z0), (X0, hd, Z1), (X1, hd, Z1)], x, y)
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            n = canvas.px.get((x + dx, y + dy))
            nd = (max(n[1], 1) if n else 0) * PX / 2
            if nd >= hd:
                continue
            for a, b in (((-hd, -nd) if nd else (-hd, hd)),) + (((nd, hd),) if nd else ()):
                if dx == 1:
                    quad([(X1, a, Z0), (X1, b, Z0), (X1, b, Z1), (X1, a, Z1)], x, y)
                elif dx == -1:
                    quad([(X0, b, Z0), (X0, a, Z0), (X0, a, Z1), (X0, b, Z1)], x, y)
                elif dy == 1:  # pixel below -> bottom face
                    quad([(X0, b, Z0), (X1, b, Z0), (X1, a, Z0), (X0, a, Z0)], x, y)
                else:  # pixel above -> top face
                    quad([(X0, a, Z1), (X1, a, Z1), (X1, b, Z1), (X0, b, Z1)], x, y)

    bmesh.ops.remove_doubles(bm, verts=bm.verts, dist=1e-6)
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    me = bpy.data.meshes.new(name)
    bm.to_mesh(me)
    bm.free()
    for p in me.polygons:
        p.use_smooth = False
    return me


def make_material(name, canvas, tex_dir):
    img_pil = texture(canvas)
    path = os.path.join(tex_dir, name + ".png")
    img_pil.save(path)
    glow = img_pil.copy()
    for (x, y), (k, _) in canvas.px.items():
        glow.putpixel((x, y), (hex_rgb(PALETTE[k][0]) + (255,)) if PALETTE[k][2] else (0, 0, 0, 255))
    gpath = os.path.join(tex_dir, name + "_glow.png")
    glow.save(gpath)

    mat = bpy.data.materials.new(name)
    mat.use_nodes = True
    nt = mat.node_tree
    bsdf = nt.nodes["Principled BSDF"]
    bsdf.inputs["Roughness"].default_value = 0.75
    tex = nt.nodes.new("ShaderNodeTexImage")
    tex.image = bpy.data.images.load(path)
    tex.interpolation = "Closest"
    gtex = nt.nodes.new("ShaderNodeTexImage")
    gtex.image = bpy.data.images.load(gpath)
    gtex.interpolation = "Closest"
    gtex.location = (-300, -300)
    nt.links.new(tex.outputs["Color"], bsdf.inputs["Base Color"])
    nt.links.new(tex.outputs["Alpha"], bsdf.inputs["Alpha"])
    nt.links.new(gtex.outputs["Color"], bsdf.inputs["Emission Color"])
    bsdf.inputs["Emission Strength"].default_value = 2.5
    return mat


def main():
    bpy.ops.wm.read_factory_settings(use_empty=True)
    tex_dir = os.path.join(HERE, "textures")
    glb_dir = os.path.join(HERE, "glb")
    os.makedirs(tex_dir, exist_ok=True)
    os.makedirs(glb_dir, exist_ok=True)
    scene = bpy.context.scene

    objs = []
    spacing = 2.6
    for i, (name, fn) in enumerate(WEAPONS):
        canvas = fn()
        me = build_mesh(name, canvas)
        me.materials.append(make_material(name, canvas, tex_dir))
        ob = bpy.data.objects.new(name, me)
        scene.collection.objects.link(ob)
        ob.location = ((i - 2) * spacing, 0, 0)
        ob.rotation_euler = (0, 0, math.radians(-35))
        objs.append(ob)

    # individual glb exports (straight-on orientation)
    for ob in objs:
        loc, rot = ob.location.copy(), ob.rotation_euler.copy()
        ob.location, ob.rotation_euler = (0, 0, 0), (0, 0, 0)
        bpy.ops.object.select_all(action="DESELECT")
        ob.select_set(True)
        bpy.context.view_layer.objects.active = ob
        bpy.ops.export_scene.gltf(filepath=os.path.join(glb_dir, ob.name + ".glb"),
                                  use_selection=True, export_format="GLB")
        ob.location, ob.rotation_euler = loc, rot

    # world, lights, camera
    world = bpy.data.worlds.new("World")
    world.use_nodes = True
    world.node_tree.nodes["Background"].inputs["Color"].default_value = (0.022, 0.022, 0.027, 1)
    world.node_tree.nodes["Background"].inputs["Strength"].default_value = 1.0
    scene.world = world

    def light(name, kind, loc, rot, energy, size=3):
        ld = bpy.data.lights.new(name, kind)
        ld.energy = energy
        if kind == "AREA":
            ld.size = size
        lo = bpy.data.objects.new(name, ld)
        lo.location, lo.rotation_euler = loc, [math.radians(a) for a in rot]
        scene.collection.objects.link(lo)

    light("Key", "AREA", (-4, -8, 7), (55, 0, -25), 1400, 8)
    light("Fill", "AREA", (6, -6, 2), (75, 0, 40), 350, 6)
    light("Rim", "AREA", (0, 6, 6), (-50, 0, 0), 600, 10)

    cam_d = bpy.data.cameras.new("Camera")
    cam_d.type = "ORTHO"
    cam_d.ortho_scale = 13.2
    cam = bpy.data.objects.new("Camera", cam_d)
    cam.location = (0, -19.3, 7.2)
    cam.rotation_euler = (math.radians(75), 0, 0)
    scene.collection.objects.link(cam)
    scene.camera = cam

    scene.render.engine = "CYCLES"
    scene.cycles.device = "CPU"
    scene.cycles.samples = 48
    scene.cycles.use_denoising = True
    scene.render.resolution_x, scene.render.resolution_y = 1920, 1080
    scene.view_settings.view_transform = "Standard"
    scene.render.filepath = os.path.join(HERE, "showcase.png")

    bpy.ops.file.pack_all()
    bpy.ops.wm.save_as_mainfile(filepath=os.path.join(HERE, "weapons.blend"))
    if "--no-render" not in sys.argv:
        bpy.ops.render.render(write_still=True)


main()
