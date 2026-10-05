"""The five legendary weapons as 3D models with smooth painted textures, built in code (no image
libraries, no Blender needed).

Each weapon has its own file in models/: its shape as boxes and thin cut-out plates, and how
every part is painted (polished steel, gold, leather, wood, crystal, glow). mesh.py packs the
painted faces into one texture and writes the Minecraft model; render.py draws the models for
the showcase.

    python3 textures.py --preview out.png      the showcase: all five in 3D on a dark background
    python3 textures.py --obj folder           every weapon as .obj + .mtl + .png, to open in Blender
"""
import json
import os
import sys
from functools import lru_cache

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), 'models'))
import common  # noqa: E402
import gravebreaker  # noqa: E402
import kurogane  # noqa: E402
import png  # noqa: E402
import render  # noqa: E402
import riftblade  # noqa: E402
import starforged  # noqa: E402
import sugarcrash  # noqa: E402

WEAPONS = {
    'kurogane': kurogane.build, 'sugarcrash': sugarcrash.build, 'riftblade': riftblade.build,
    'gravebreaker': gravebreaker.build, 'starforged': starforged.build,
}


@lru_cache(maxsize=None)
def weapon(name):
    """(model, texture rows): the model with its faces packed and painted."""
    m = WEAPONS[name]()
    return m, m.bake()


def model(name):
    """The Minecraft model file (a dict) for the texture legendary:item/<name>."""
    return weapon(name)[0].json(f'legendary:item/{name}', common.DISPLAY)


def texture(name):
    """The texture as a PNG file."""
    return weapon(name)[0].png()


def gui_image(name, size):
    """The weapon as the inventory shows it, size x size pixels (transparent background)."""
    m, tex = weapon(name)
    j = model(name)
    return render.render(j, tex, size=size, rotation=tuple(j['display']['gui']['rotation']), zoom=23 / 16, ss=4)


def preview(path, size=460):
    """The showcase: all five standing, turned to show their depth, on dark grey."""
    images = []
    for name in WEAPONS:
        m, tex = weapon(name)
        images.append(render.render(model(name), tex, size=size, rotation=common.SHOWCASE, zoom=1.0, ss=3))
    png.save(path, render.composite(images, bg=(40, 40, 46), bg2=(20, 20, 24), gap=0, pad=size // 16))


def obj(folder):
    os.makedirs(folder, exist_ok=True)
    for name in WEAPONS:
        m, tex = weapon(name)
        mesh, mtl = m.obj(name)
        with open(os.path.join(folder, name + '.obj'), 'w') as f:
            f.write(mesh)
        with open(os.path.join(folder, name + '.mtl'), 'w') as f:
            f.write(mtl)
        with open(os.path.join(folder, name + '.png'), 'wb') as f:
            f.write(m.png())


if __name__ == '__main__':
    if sys.argv[1:2] == ['--preview']:
        preview(sys.argv[2])
    elif sys.argv[1:2] == ['--obj']:
        obj(sys.argv[2])
    else:
        for name in (sys.argv[1:] or WEAPONS):
            j = model(name)
            print(name, len(j['elements']), 'parts,', j['texture_size'][0], 'px texture,',
                  len(json.dumps(j)), 'bytes of model')
