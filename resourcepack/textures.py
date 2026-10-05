"""The five legendary weapons: 3D models with detailed 128 x 128 pixel-art textures, built in code
(no image libraries, no Blender needed).

Each weapon has its own file in models/: its parts (blade, guard, grip, gems...) as shapes with a
height map, colours and a thickness. pixel.py paints them with consistent lighting (and animates
the glowing parts), voxel.py turns the painted pixels into a 3D model, common.py says how the
model is held and shown, and render.py draws the models for the showcase.

    python3 textures.py                        a summary of every model
    python3 textures.py --preview out.png      the showcase picture
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
import voxel  # noqa: E402

WEAPONS = {
    'kurogane': kurogane.build, 'sugarcrash': sugarcrash.build, 'riftblade': riftblade.build,
    'gravebreaker': gravebreaker.build, 'starforged': starforged.build,
}
FRAMES = 8              # animation frames for weapons with glowing parts
FRAME_TIME = 3          # ticks per frame (blended smoothly between frames)


@lru_cache(maxsize=None)
def weapon(name):
    """(frames, depth, glow, display) for a weapon."""
    art, grip = WEAPONS[name]()
    animated = any(p.glow is not None for p in art.parts)
    frames, depth, glow = art.paint(frames=FRAMES if animated else 1)
    return frames, depth, glow, common.display(grip, depth)


def model(name):
    """The Minecraft model file (a dict) for the texture legendary:item/<name>."""
    frames, depth, glow, display = weapon(name)
    return voxel.model(f'legendary:item/{name}', depth, glow, display)


def texture(name):
    """The texture as a PNG file: the animation frames stacked top to bottom."""
    frames = weapon(name)[0]
    return png.encode([row for f in frames for row in f])


def animation(name):
    """The texture's .mcmeta file (or None when it is not animated)."""
    if len(weapon(name)[0]) == 1:
        return None
    return (json.dumps({'animation': {'frametime': FRAME_TIME, 'interpolate': True}}) + '\n').encode('utf-8')


def gui_image(name, size, ss=4):
    """The weapon as it sits in an inventory slot (size x size, transparent background)."""
    frames = weapon(name)[0]
    j = model(name)
    g = j['display']['gui']
    return render.render(j, frames[0], size=size, rotation=tuple(g['rotation']), frame=16, ss=ss,
                         scale=g['scale'][0], translate=tuple(g['translation']))


def preview(path, size=420):
    """The showcase: all five standing in 3D on dark grey, and below them as they look in the
    inventory."""
    shows, slots = [], []
    for name in WEAPONS:
        frames = weapon(name)[0]
        shows.append(render.render(model(name), frames[0], size=size, rotation=common.SHOWCASE, frame=17, ss=3))
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
        mesh, mtl = voxel.obj(name, model(name))
        with open(os.path.join(folder, name + '.obj'), 'w') as f:
            f.write(mesh)
        with open(os.path.join(folder, name + '.mtl'), 'w') as f:
            f.write(mtl)
        png.save(os.path.join(folder, name + '.png'), weapon(name)[0][0])


if __name__ == '__main__':
    if sys.argv[1:2] == ['--preview']:
        preview(sys.argv[2])
    elif sys.argv[1:2] == ['--obj']:
        obj(sys.argv[2])
    else:
        for name in (sys.argv[1:] or WEAPONS):
            j = model(name)
            print(name, len(j['elements']), 'parts,', len(weapon(name)[0]), 'frame(s),',
                  len(json.dumps(j, separators=(',', ':'))), 'bytes of model')
