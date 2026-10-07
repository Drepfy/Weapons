"""Builds the legendary weapons in Blender and renders them.

    python3 make.py render OUT_DIR [weapon ...]     beauty renders (PNG) of each weapon
    python3 make.py blend OUT_DIR [weapon ...]      the .blend file of each weapon
    python3 make.py sheet OUT.png [weapon ...]      all of them side by side

Needs Blender's Python module (pip install bpy==4.2.0); runs headless on the CPU (Cycles).
"""
import importlib
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import lib  # noqa: E402

WEAPONS = ['katana', 'candycane', 'crush', 'reaper']


def scene_with(name):
    lib.reset()
    lib.world()
    lib.lights()
    coll = lib.collection(name)
    parts = importlib.import_module(name).build(coll)
    return parts


def render(name, path, samples=96, size=(900, 1600), view='hero'):
    import bpy
    scene_with(name)
    scene = bpy.context.scene
    scene.cycles.samples = samples
    scene.render.resolution_x, scene.render.resolution_y = size
    if view == 'hero':
        cam = lib.camera('Camera', (9.0, -30.0, 11.0), (0.0, 0.0, 8.0), lens=70)
    elif view == 'front':
        cam = lib.camera('Camera', (0.0, -60.0, 8.0), (0.0, 0.0, 8.0), ortho=17.0)
    else:
        cam = lib.camera('Camera', (-4.0, -14.0, 14.5), (0.0, 0.0, 12.0), lens=70)
    scene.camera = cam
    scene.render.filepath = path
    bpy.ops.render.render(write_still=True)


def blend(name, path):
    import bpy
    scene_with(name)
    bpy.ops.wm.save_as_mainfile(filepath=path)


if __name__ == '__main__':
    mode, out = sys.argv[1], sys.argv[2]
    names = sys.argv[3:] or WEAPONS
    if mode == 'render':
        os.makedirs(out, exist_ok=True)
        for n in names:
            for view in ('hero', 'front', 'close'):
                render(n, os.path.join(out, f'{n}_{view}.png'), view=view,
                       size=(900, 1600) if view != 'close' else (1200, 1200))
    elif mode == 'blend':
        os.makedirs(out, exist_ok=True)
        for n in names:
            blend(n, os.path.join(out, f'{n}.blend'))
