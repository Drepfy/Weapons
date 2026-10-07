"""The four legendary weapons: smooth 3D models with high-resolution textures baked for them,
built in code (no image libraries, no Blender needed).

Each weapon has its own file in models/ that describes its parts: flat parts with any outline
(blades, axe heads, guards, gems) and round parts (grips, hafts, collars, rings), and how each
looks at every point (colour, surface shape, metal, gloss, glow). paint.py lights every texel
smoothly, forge.py builds the 3D model and bakes its 512 x 512 texture plus a small animated
texture for the glowing parts, common.py says how the model is held and shown, and render.py
draws the models for the showcase.

    python3 textures.py                        a summary of every model
    python3 textures.py --preview out.png      the showcase picture
    python3 textures.py --obj folder           every weapon as .obj + .mtl + .png, to open in Blender
"""
import json
import os
import sys
from functools import lru_cache

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), 'models'))
import candycane  # noqa: E402
import common  # noqa: E402
import crush  # noqa: E402
import forge  # noqa: E402
import katana  # noqa: E402
import png  # noqa: E402
import reaper  # noqa: E402
import render  # noqa: E402

WEAPONS = {
    'katana': katana.build, 'candycane': candycane.build, 'crush': crush.build, 'reaper': reaper.build,
}
FRAME_TIME = 3          # ticks per frame of the glow animation (blended smoothly between frames)


@lru_cache(maxsize=None)
def weapon(name):
    """(the baked weapon, its display settings)."""
    w = WEAPONS[name]()
    baked = w.bake()
    return baked, common.display(w.grip, baked.points())


def model(name):
    """The Minecraft model file (a dict) for legendary:item/<name>."""
    baked, display = weapon(name)
    return forge.model(baked, f'legendary:item/{name}', f'legendary:item/{name}_glow', display)


def texture(name):
    """The main texture as a PNG file."""
    return png.encode(weapon(name)[0].tex)


def glow_texture(name):
    """The glow texture (its animation frames stacked top to bottom) as a PNG file, or None."""
    baked = weapon(name)[0]
    if not baked.glow_size:
        return None
    return png.encode([row for f in baked.glow_frames for row in f])


def animation(name):
    """The glow texture's .mcmeta file."""
    return (json.dumps({'animation': {'frametime': FRAME_TIME, 'interpolate': True}}) + '\n').encode('utf-8')


def _textures(name, frame=0):
    baked = weapon(name)[0]
    tex = {'#0': baked.tex}
    if baked.glow_size:
        tex['#1'] = baked.glow_frames[frame]
    return tex


def gui_image(name, size, ss=4):
    """The weapon as it sits in an inventory slot (size x size, transparent background)."""
    j = model(name)
    g = j['display']['gui']
    return render.render(j, _textures(name), size=size, rotation=tuple(g['rotation']), frame=16, ss=ss,
                         scale=g['scale'][0], translate=tuple(g['translation']))


def preview(path, size=420):
    """The showcase: all four standing in 3D on dark grey, and below them as they look in the
    inventory."""
    shows, slots = [], []
    for name in WEAPONS:
        shows.append(render.render(model(name), _textures(name), size=size, rotation=common.SHOWCASE,
                                   frame=17, ss=3))
        slot = gui_image(name, 96)
        slots.append([[p for p in r for _ in range(2)] for r in slot for _ in range(2)])
    top = render.composite(shows, bg=(40, 40, 46), bg2=(22, 22, 26), gap=0, pad=size // 16)
    grey = (139, 139, 139)
    side = size // 16 + (size - 192) // 2                  # each icon under its weapon
    bottom = render.composite(slots, bg=grey, gap=size - 192, pad=side)
    bottom = [r + [grey] * (len(top[0]) - len(r)) for r in bottom[side - 24:len(bottom) - side + 24]]
    png.save(path, top + bottom)


def obj(folder):
    os.makedirs(folder, exist_ok=True)
    for name in WEAPONS:
        mesh, mtl = forge.obj(name, model(name))
        with open(os.path.join(folder, name + '.obj'), 'w') as f:
            f.write(mesh)
        with open(os.path.join(folder, name + '.mtl'), 'w') as f:
            f.write(mtl)
        baked = weapon(name)[0]
        png.save(os.path.join(folder, name + '.png'), baked.tex)
        if baked.glow_size:
            png.save(os.path.join(folder, name + '_glow.png'), baked.glow_frames[0])


if __name__ == '__main__':
    if sys.argv[1:2] == ['--preview']:
        preview(sys.argv[2])
    elif sys.argv[1:2] == ['--obj']:
        obj(sys.argv[2])
    else:
        for name in (sys.argv[1:] or WEAPONS):
            j = model(name)
            baked = weapon(name)[0]
            print(name, len(j['elements']), 'parts,', len(json.dumps(j, separators=(',', ':'))) // 1024,
                  'KB of model, glow texture', baked.glow_size or 'none')
