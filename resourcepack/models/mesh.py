"""3D item models built from boxes, the only shape Minecraft item models allow, painted with
smooth high-resolution textures.

A Model is a list of boxes. Each box face is painted by a function that gets the 3D point it
paints, so neighbouring faces line up; the faces are then packed into one texture atlas. Thin
"cards" (boxes showing only their front and back) with transparent texels give smooth curved
outlines; thicker boxes give the depth. Boxes can be turned by -45, -22.5, 22.5 or 45 degrees
around one axis, which is all Minecraft allows.

Weapons are modelled standing upright along +y, centred on x = z = 8; DISPLAY turns them onto
the diagonal like a vanilla sword. They must fit the diamond |x - 8| + |y - 8| <= 11.3 to fill an
inventory slot like a vanilla item.
"""
import math

from png import encode

DIRS = ('north', 'south', 'east', 'west', 'up', 'down')
NORMALS = {'north': (0, 0, -1), 'south': (0, 0, 1), 'east': (1, 0, 0), 'west': (-1, 0, 0),
           'up': (0, 1, 0), 'down': (0, -1, 0)}
ANGLES = (-45.0, -22.5, 0.0, 22.5, 45.0)
PAD = 2  # texels around every face in the atlas, so mipmaps do not bleed between faces


def corners(direction, a, b):
    """Top-left, top-right and bottom-left corners of a box face, as Minecraft maps a texture onto
    it (u runs top-left to top-right, v top-left to bottom-left, seen from outside)."""
    (x0, y0, z0), (x1, y1, z1) = a, b
    return {
        'south': ((x0, y1, z1), (x1, y1, z1), (x0, y0, z1)),
        'north': ((x1, y1, z0), (x0, y1, z0), (x1, y0, z0)),
        'east': ((x1, y1, z1), (x1, y1, z0), (x1, y0, z1)),
        'west': ((x0, y1, z0), (x0, y1, z1), (x0, y0, z0)),
        'up': ((x0, y1, z0), (x1, y1, z0), (x0, y1, z1)),
        'down': ((x0, y0, z1), (x1, y0, z1), (x0, y0, z0)),
    }[direction]


def rotate(p, rot):
    """Turns a point by an element rotation (axis, angle in degrees, origin), right-handed like
    Minecraft."""
    if not rot:
        return p
    axis, angle, (ox, oy, oz) = rot
    c, s = math.cos(math.radians(angle)), math.sin(math.radians(angle))
    x, y, z = p[0] - ox, p[1] - oy, p[2] - oz
    if axis == 'x':
        y, z = y * c - z * s, y * s + z * c
    elif axis == 'y':
        z, x = z * c - x * s, z * s + x * c
    else:
        x, y = x * c - y * s, x * s + y * c
    return x + ox, y + oy, z + oz


class Face:
    def __init__(self, box, direction):
        self.box = box
        self.dir = direction
        tl, tr, bl = corners(direction, box.a, box.b)
        self.tl, self.tr, self.bl = tl, tr, bl
        self.w = math.dist(tl, tr)                      # size in units
        self.h = math.dist(tl, bl)
        self.normal = NORMALS[direction]
        self.region = None                              # (x, y, w, h) in the atlas, without padding
        self.uv = None

    def point(self, s, t):
        return tuple(self.tl[i] + s * (self.tr[i] - self.tl[i]) + t * (self.bl[i] - self.tl[i]) for i in range(3))


class Box:
    def __init__(self, a, b, paint, faces=DIRS, rot=None, tag=''):
        self.a = tuple(min(a[i], b[i]) for i in range(3))
        self.b = tuple(max(a[i], b[i]) for i in range(3))
        self.paint = paint
        self.rot = rot
        self.tag = tag
        self.faces = [Face(self, d) for d in faces]


class Model:
    def __init__(self, density=16):
        self.density = density                          # texels per unit
        self.boxes = []
        self.size = None
        self.texture = None

    # ---- shapes ---------------------------------------------------------------------------------------

    def box(self, a, b, paint, faces=DIRS, rot=None, tag=''):
        if rot is not None:
            assert rot[1] in ANGLES, rot
        box = Box(a, b, paint, faces, rot, tag)
        self.boxes.append(box)
        return box

    def card(self, x0, y0, x1, y1, thickness, paint, z=8.0, tag=''):
        """A thin plate facing front and back; its paint returns None outside the shape."""
        return self.box((x0, y0, z - thickness / 2), (x1, y1, z + thickness / 2), paint, ('south', 'north'),
                        tag=tag)

    def rod(self, y0, y1, r, paint, x=8.0, z=8.0, tag=''):
        """A rounded rod along y: a square box and the same box turned 45 degrees."""
        mid = (y0 + y1) / 2
        self.box((x - r, y0, z - r), (x + r, y1, z + r), paint, tag=tag)
        r2 = r * 0.94
        self.box((x - r2, y0, z - r2), (x + r2, y1, z + r2), paint, rot=('y', 45.0, (x, mid, z)), tag=tag)

    def segment(self, p0, p1, width, thickness, paint, z=8.0, faces=DIRS, overlap=0.15, tag=''):
        """A bar from p0 to p1 in the front plane, turned to the nearest allowed angle."""
        dx, dy = p1[0] - p0[0], p1[1] - p0[1]
        length = math.hypot(dx, dy) + overlap
        angle = math.degrees(math.atan2(dy, dx))       # 0 = along +x
        cx, cy = (p0[0] + p1[0]) / 2, (p0[1] + p1[1]) / 2
        a = (angle + 180) % 180                         # the bar's direction, 0..180
        if 45 < a < 135:                                # closer to vertical: a box along y
            turn = a - 90
            half = (width / 2, length / 2)
        else:                                           # closer to horizontal: a box along x
            turn = a if a <= 45 else a - 180
            half = (length / 2, width / 2)
        turn = min(ANGLES, key=lambda q: abs(q - turn))
        rot = ('z', turn, (cx, cy, z)) if turn else None
        return self.box((cx - half[0], cy - half[1], z - thickness / 2), (cx + half[0], cy + half[1], z + thickness / 2),
                        paint, faces, rot, tag)

    # ---- texture --------------------------------------------------------------------------------------

    def bake(self):
        """Packs every face into one square texture and paints it. Returns the texture rows."""
        faces = [f for b in self.boxes for f in b.faces]
        for f in faces:
            f.px = (max(1, round(f.w * self.density)), max(1, round(f.h * self.density)))
        size = 64
        while not self._pack(faces, size):
            size *= 2
            assert size <= 2048, 'model too big for one texture'
        self.size = size
        rows = [[None] * size for _ in range(size)]
        for f in faces:
            x0, y0 = f.region[0] - PAD, f.region[1] - PAD
            w, h = f.px
            for j in range(h + 2 * PAD):
                t = min(max((j - PAD + 0.5) / h, 0.0), 0.9999)
                for i in range(w + 2 * PAD):
                    s = min(max((i - PAD + 0.5) / w, 0.0), 0.9999)
                    c = f.box.paint(f, s, t, f.point(s, t))
                    if c is not None:
                        c = tuple(max(0, min(255, int(round(v)))) for v in c)
                        rows[y0 + j][x0 + i] = c if len(c) == 4 else c + (255,)
            k = 16.0 / size
            f.uv = [round(f.region[0] * k, 4), round(f.region[1] * k, 4),
                    round((f.region[0] + w) * k, 4), round((f.region[1] + h) * k, 4)]
        self.texture = rows
        return rows

    def _pack(self, faces, size):
        x = y = shelf = 0
        for f in sorted(faces, key=lambda f: (-f.px[1], -f.px[0])):
            w, h = f.px[0] + 2 * PAD, f.px[1] + 2 * PAD
            if x + w > size:
                x, y, shelf = 0, y + shelf, 0
            if y + h > size or w > size:
                return False
            f.region = (x + PAD, y + PAD)
            x += w
            shelf = max(shelf, h)
        return True

    def png(self):
        return encode(self.texture)

    # ---- export ---------------------------------------------------------------------------------------

    def json(self, texture, display, gui_light='front'):
        """The Minecraft model file (a dict). texture: e.g. 'legendary:item/kurogane'."""
        elements = []
        for b in self.boxes:
            e = {'from': [round(v, 4) for v in b.a], 'to': [round(v, 4) for v in b.b]}
            if b.rot:
                e['rotation'] = {'angle': b.rot[1], 'axis': b.rot[0], 'origin': [round(v, 4) for v in b.rot[2]]}
            e['faces'] = {f.dir: {'uv': f.uv, 'texture': '#0'} for f in b.faces}
            elements.append(e)
        return {
            'credit': 'ᴠᴀɴɪʟʟᴀ sᴍᴘ legendary weapons',
            'texture_size': [self.size, self.size],
            'gui_light': gui_light,
            'textures': {'0': texture, 'particle': texture},
            'elements': elements,
            'display': display,
        }

    def obj(self, name):
        """A Wavefront OBJ (and its MTL) of the model, to open in Blender: 1 block = 1 metre."""
        out = [f'# {name}: ᴠᴀɴɪʟʟᴀ sᴍᴘ legendary weapon', f'mtllib {name}.mtl', f'o {name}', f'usemtl {name}']
        vi = 1
        for b in self.boxes:
            for f in b.faces:
                tl, tr, bl = f.tl, f.tr, f.bl
                br = tuple(tr[i] + bl[i] - tl[i] for i in range(3))
                for p in (tl, bl, br, tr):
                    x, y, z = rotate(p, b.rot)
                    out.append(f'v {x / 16:.5f} {y / 16:.5f} {z / 16:.5f}')
                u0, v0, u1, v1 = f.uv
                for u, v in ((u0, v0), (u0, v1), (u1, v1), (u1, v0)):
                    out.append(f'vt {u / 16:.5f} {1 - v / 16:.5f}')
                out.append(f'f {vi}/{vi} {vi + 1}/{vi + 1} {vi + 2}/{vi + 2} {vi + 3}/{vi + 3}')
                vi += 4
        mtl = [f'newmtl {name}', 'Ka 1 1 1', 'Kd 1 1 1', 'Ks 0 0 0', 'd 1', 'illum 1',
               f'map_Kd {name}.png', f'map_d {name}.png']
        return '\n'.join(out) + '\n', '\n'.join(mtl) + '\n'
