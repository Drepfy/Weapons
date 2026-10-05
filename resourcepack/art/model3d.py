"""Turns a sprite plus a thickness for every pixel into a 3D Minecraft item model.

Every pixel becomes a stack of voxels centred on the item's middle plane (z = 8), so thick parts
stand out on both sides. Pixels next to each other in a row with the same thickness are merged
into one box, and faces that are always covered are left out. The front and back of each box
show the sprite; its sides show the colour of the pixel at that edge (Minecraft shades them).

A 32x32 sprite fills the usual 16x16 item space, so one pixel is 0.5 units and one voxel of
thickness is 0.5 units too.
"""

N = 32
UNIT = 16.0 / N

# Vanilla's item/generated + item/handheld display settings, so the weapons are held, dropped and
# framed exactly like a sword. Only "gui" is ours: tilted a little so the depth shows in the
# inventory too, while the pixels stay crisp.
DISPLAY = {
    'thirdperson_righthand': {'rotation': [0, -90, 55], 'translation': [0, 4.0, 0.5], 'scale': [0.85, 0.85, 0.85]},
    'thirdperson_lefthand': {'rotation': [0, 90, -55], 'translation': [0, 4.0, 0.5], 'scale': [0.85, 0.85, 0.85]},
    'firstperson_righthand': {'rotation': [0, -90, 25], 'translation': [1.13, 3.2, 1.13], 'scale': [0.68, 0.68, 0.68]},
    'firstperson_lefthand': {'rotation': [0, 90, -25], 'translation': [1.13, 3.2, 1.13], 'scale': [0.68, 0.68, 0.68]},
    'ground': {'rotation': [0, 0, 0], 'translation': [0, 2, 0], 'scale': [0.5, 0.5, 0.5]},
    'head': {'rotation': [0, 180, 0], 'translation': [0, 13, 7], 'scale': [1, 1, 1]},
    'fixed': {'rotation': [0, 180, 0], 'translation': [0, 0, 0], 'scale': [1, 1, 1]},
    'gui': {'rotation': [12, -22, 0], 'translation': [0, 0, 0], 'scale': [1, 1, 1]},
}


def _r(v):
    return round(v, 4)


def _pixel_uv(x, y):
    return [_r(x * UNIT), _r(y * UNIT), _r((x + 1) * UNIT), _r((y + 1) * UNIT)]


def elements(pixels, depth):
    """pixels: set of (x, y) that are drawn; depth: (x, y) -> thickness in voxels (>= 1)."""
    out = []
    for y in range(N):
        x = 0
        while x < N:
            if (x, y) not in pixels:
                x += 1
                continue
            t = depth[(x, y)]
            x1 = x
            while (x1 + 1, y) in pixels and depth[(x1 + 1, y)] == t:
                x1 += 1
            half = t * UNIT / 2
            faces = {
                'south': {'uv': [_r(x * UNIT), _r(y * UNIT), _r((x1 + 1) * UNIT), _r((y + 1) * UNIT)], 'texture': '#0'},
                'north': {'uv': [_r((x1 + 1) * UNIT), _r(y * UNIT), _r(x * UNIT), _r((y + 1) * UNIT)], 'texture': '#0'},
            }
            if depth.get((x1 + 1, y), 0) < t:
                faces['east'] = {'uv': _pixel_uv(x1, y), 'texture': '#0'}
            if depth.get((x - 1, y), 0) < t:
                faces['west'] = {'uv': _pixel_uv(x, y), 'texture': '#0'}
            if any(depth.get((c, y - 1), 0) < t for c in range(x, x1 + 1)):
                faces['up'] = {'uv': [_r(x * UNIT), _r(y * UNIT), _r((x1 + 1) * UNIT), _r((y + 1) * UNIT)],
                               'texture': '#0'}
            if any(depth.get((c, y + 1), 0) < t for c in range(x, x1 + 1)):
                faces['down'] = {'uv': [_r(x * UNIT), _r(y * UNIT), _r((x1 + 1) * UNIT), _r((y + 1) * UNIT)],
                                 'texture': '#0'}
            out.append({
                'from': [_r(x * UNIT), _r(16 - (y + 1) * UNIT), _r(8 - half)],
                'to': [_r((x1 + 1) * UNIT), _r(16 - y * UNIT), _r(8 + half)],
                'faces': faces,
            })
            x = x1 + 1
    return out


def model(texture, pixels, depth, gui=None):
    """The model file for one weapon. texture: e.g. 'legendary:item/kurogane'."""
    display = {k: dict(v) for k, v in DISPLAY.items()}
    if gui:
        display['gui'] = gui
    return {
        'credit': 'ᴠᴀɴɪʟʟᴀ sᴍᴘ legendary weapons',
        'gui_light': 'front',
        'ambientocclusion': False,
        'textures': {'0': texture, 'particle': texture},
        'elements': elements(pixels, depth),
        'display': display,
    }
