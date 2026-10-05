"""The five legendary weapons: 32x32 pixel art and chunky 3D models, drawn in code (no image
libraries).

Each weapon has its own file in art/ (palette, shapes, hand-placed details, and how thick every
part is). Every sprite lies on the diagonal like a vanilla sword, handle at the bottom left,
light from the top left, with outlines tinted to the material they surround. model3d.py turns a
sprite into a 3D item model (thin edges, thick spines, raised gems); render3d.py draws those
models for the showcase.

    python3 textures.py                       print every sprite as a grid of palette letters
    python3 textures.py kurogane              just one
    python3 textures.py --preview out.png     the showcase: all five in 3D on a dark background
    python3 textures.py --flat out.png        the flat sprites, enlarged
"""
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), 'art'))
import pix  # noqa: E402
import model3d  # noqa: E402
import render3d  # noqa: E402
import kurogane  # noqa: E402
import sugarcrash  # noqa: E402
import riftblade  # noqa: E402
import gravebreaker  # noqa: E402
import starforged  # noqa: E402

SIZE = pix.N
SPRITES = {
    'kurogane': kurogane.make, 'sugarcrash': sugarcrash.make, 'riftblade': riftblade.make,
    'gravebreaker': gravebreaker.make, 'starforged': starforged.make,
}


def sprite(name):
    return SPRITES[name]()


def rows(name):
    """32 rows of 32 pixels: (r, g, b) or None for transparent."""
    return sprite(name).rows()


def png(name):
    """The texture as a PNG file (32x32, transparent background)."""
    return pix.png_bytes(rows(name))


def model(name):
    """The 3D item model (a dict, saved as JSON) for the weapon's texture legendary:item/<name>."""
    sp = sprite(name)
    return model3d.model(f'legendary:item/{name}', set(sp.px), sp.depths())


def preview(path, size=420):
    """The showcase: all five in 3D, turned to show their depth, evenly spaced on dark grey."""
    images = []
    for name in SPRITES:
        sp = sprite(name)
        images.append(render3d.render(model3d.model('', set(sp.px), sp.depths()), sp.rows(), size=size,
                                      ry=-35, rx=20, zoom=1.55))
    pix.save(path, render3d.composite(images, bg=(30, 30, 34), gap=size // 10, pad=size // 8))


def flat(path, scale=12):
    """The flat sprites side by side, enlarged, on dark grey."""
    pix.save(path, pix.sheet([sprite(name) for name in SPRITES], k=scale, gap=4 * scale, pad=4 * scale,
                             bg=(30, 30, 34)))


if __name__ == '__main__':
    if sys.argv[1:2] == ['--preview']:
        preview(sys.argv[2])
        sys.exit()
    if sys.argv[1:2] == ['--flat']:
        flat(sys.argv[2])
        sys.exit()
    for name in (sys.argv[1:] or SPRITES):
        sp = sprite(name)
        print(name)
        for y in range(SIZE):
            print('  ' + ''.join(sp.px.get((x, y), '.') for x in range(SIZE)))
