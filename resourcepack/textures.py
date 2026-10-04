"""The five legendary weapon textures: 32x32 pixel art, drawn in code (no image libraries).

Each weapon has its own file in art/ (palette, shapes, hand-placed details). Every sprite lies
on the diagonal like a vanilla sword, handle at the bottom left, light from the top left, with
outlines tinted to the material they surround.

    python3 textures.py                       print every sprite as a grid of palette letters
    python3 textures.py kurogane              just one
    python3 textures.py --preview out.png     the showcase sheet: all five, enlarged, on dark grey
"""
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), 'art'))
import pix  # noqa: E402
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


def preview(path, scale=12):
    """The showcase sheet: all five side by side, evenly spaced, on a dark neutral background."""
    pix.save(path, pix.sheet([sprite(name) for name in SPRITES], k=scale, gap=4 * scale, pad=4 * scale,
                             bg=(30, 30, 34)))


if __name__ == '__main__':
    if sys.argv[1:2] == ['--preview']:
        preview(sys.argv[2])
        sys.exit()
    for name in (sys.argv[1:] or SPRITES):
        sp = sprite(name)
        print(name)
        for y in range(SIZE):
            print('  ' + ''.join(sp.px.get((x, y), '.') for x in range(SIZE)))
