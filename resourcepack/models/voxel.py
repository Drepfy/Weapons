"""Turns a painted weapon (128 x 128 pixels with a thickness per pixel) into a 3D Minecraft model.

Pixels with the same thickness are merged into as few rectangles as possible; each rectangle
becomes a box centred on the model's middle plane, so thick parts stand out on both sides. The
front and back of a box show its pixels; its sides show the colour of its edge pixels. Sides
always hidden behind a thicker neighbour are left out. Glowing pixels get their own boxes, which
newer clients light up in the dark (light_emission; older clients ignore it).
"""
from pixel import PX, SIZE

U = 16.0 / SIZE                     # model units (and texture units) per pixel


def rectangles(depth, glow):
    """Greedy merge into (i0, j0, i1, j1, thickness, glowing) rectangles (inclusive)."""
    n = SIZE
    done = [[False] * n for _ in range(n)]
    out = []
    for j in range(n):
        for i in range(n):
            if done[j][i] or depth[j][i] == 0:
                continue
            key = (depth[j][i], glow[j][i])
            i1 = i
            while i1 + 1 < n and not done[j][i1 + 1] and (depth[j][i1 + 1], glow[j][i1 + 1]) == key:
                i1 += 1
            j1 = j
            while j1 + 1 < n and all(not done[j1 + 1][c] and (depth[j1 + 1][c], glow[j1 + 1][c]) == key
                                     for c in range(i, i1 + 1)):
                j1 += 1
            for jj in range(j, j1 + 1):
                for ii in range(i, i1 + 1):
                    done[jj][ii] = True
            out.append((i, j, i1, j1) + key)
    return out


def _r(v):
    return round(v, 4)


def elements(depth, glow):
    n = SIZE

    def d(i, j):
        return depth[j][i] if 0 <= i < n and 0 <= j < n else 0

    out = []
    for i0, j0, i1, j1, t, glowing in rectangles(depth, glow):
        half = t * U / 2
        faces = {
            'south': {'uv': [_r(i0 * U), _r(j0 * U), _r((i1 + 1) * U), _r((j1 + 1) * U)], 'texture': '#0'},
            'north': {'uv': [_r((i1 + 1) * U), _r(j0 * U), _r(i0 * U), _r((j1 + 1) * U)], 'texture': '#0'},
        }
        if any(d(i1 + 1, j) < t for j in range(j0, j1 + 1)):
            faces['east'] = {'uv': [_r(i1 * U), _r(j0 * U), _r((i1 + 1) * U), _r((j1 + 1) * U)], 'texture': '#0'}
        if any(d(i0 - 1, j) < t for j in range(j0, j1 + 1)):
            faces['west'] = {'uv': [_r(i0 * U), _r(j0 * U), _r((i0 + 1) * U), _r((j1 + 1) * U)], 'texture': '#0'}
        if any(d(i, j0 - 1) < t for i in range(i0, i1 + 1)):
            faces['up'] = {'uv': [_r(i0 * U), _r(j0 * U), _r((i1 + 1) * U), _r((j0 + 1) * U)], 'texture': '#0'}
        if any(d(i, j1 + 1) < t for i in range(i0, i1 + 1)):
            faces['down'] = {'uv': [_r(i0 * U), _r(j1 * U), _r((i1 + 1) * U), _r((j1 + 1) * U)], 'texture': '#0'}
        e = {
            'from': [_r(i0 * U), _r(16 - (j1 + 1) * U), _r(8 - half)],
            'to': [_r((i1 + 1) * U), _r(16 - j0 * U), _r(8 + half)],
            'faces': faces,
        }
        if glowing:
            e['light_emission'] = 15
        out.append(e)
    return out


def model(texture, depth, glow, display):
    return {
        'credit': 'ᴠᴀɴɪʟʟᴀ sᴍᴘ legendary weapons',
        'texture_size': [SIZE, SIZE],
        'gui_light': 'front',
        'textures': {'0': texture, 'particle': texture},
        'elements': elements(depth, glow),
        'display': display,
    }


CORNERS = {
    'south': lambda a, b: ((a[0], b[1], b[2]), (a[0], a[1], b[2]), (b[0], a[1], b[2]), (b[0], b[1], b[2])),
    'north': lambda a, b: ((b[0], b[1], a[2]), (b[0], a[1], a[2]), (a[0], a[1], a[2]), (a[0], b[1], a[2])),
    'east': lambda a, b: ((b[0], b[1], b[2]), (b[0], a[1], b[2]), (b[0], a[1], a[2]), (b[0], b[1], a[2])),
    'west': lambda a, b: ((a[0], b[1], a[2]), (a[0], a[1], a[2]), (a[0], a[1], b[2]), (a[0], b[1], b[2])),
    'up': lambda a, b: ((a[0], b[1], a[2]), (a[0], b[1], b[2]), (b[0], b[1], b[2]), (b[0], b[1], a[2])),
    'down': lambda a, b: ((a[0], a[1], b[2]), (a[0], a[1], a[2]), (b[0], a[1], a[2]), (b[0], a[1], b[2])),
}


def obj(name, model_json):
    """A Wavefront OBJ (and its MTL) of the model, to open in Blender: 1 block = 1 metre."""
    out = [f'# {name}: ᴠᴀɴɪʟʟᴀ sᴍᴘ legendary weapon', f'mtllib {name}.mtl', f'o {name}', f'usemtl {name}']
    vi = 1
    for e in model_json['elements']:
        a, b = e['from'], e['to']
        for direction, face in e['faces'].items():
            u0, v0, u1, v1 = face['uv']
            for x, y, z in CORNERS[direction](a, b):          # TL, BL, BR, TR seen from outside
                out.append(f'v {x / 16:.5f} {y / 16:.5f} {z / 16:.5f}')
            for u, v in ((u0, v0), (u0, v1), (u1, v1), (u1, v0)):
                out.append(f'vt {u / 16:.5f} {1 - v / 16:.5f}')
            out.append(f'f {vi}/{vi} {vi + 1}/{vi + 1} {vi + 2}/{vi + 2} {vi + 3}/{vi + 3}')
            vi += 4
    mtl = [f'newmtl {name}', 'Ka 1 1 1', 'Kd 1 1 1', 'Ks 0 0 0', 'd 1', 'illum 1',
           f'map_Kd {name}.png', f'map_d {name}.png']
    return '\n'.join(out) + '\n', '\n'.join(mtl) + '\n'
