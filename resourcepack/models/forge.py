"""Builds a weapon as a smooth 3D Minecraft model with a high-resolution texture baked for it.

A weapon is made of parts, drawn in order (later parts sit on top of earlier ones):

  Sheet  a flat part with any outline: a blade, an axe head, a guard, a gem. Its outline comes
         from the texture (512 x 512, 32 texels per model unit, smoothed edges), so curves stay
         smooth; behind it the model has a solid body as thick as the part (finer than a pixel
         of a vanilla texture) so it has real depth when turned.
  Rod    a round part along the y axis: a grip, a haft, a collar, a ring, a round guard. It is an
         8 or 16 sided prism (boxes turned 22.5 / 45 degrees, which Minecraft allows), with its
         surface unwrapped onto its own patch of the texture and lit as if perfectly round.

Each part says how it looks at any point on it: its colour, the shape of its surface (a height,
from which the light is worked out), how metallic and glossy it is, and for glowing parts how
brightly they glow over time. Glowing parts get their own boxes that light up in the dark and a
small animated texture, so they shimmer without the large texture having to be animated.
"""
import math

import paint
import render

D = 32                  # texels per model unit in the main texture
N = 16 * D              # the main texture: N x N
CELL = 2                # texels per cell of the solid body (1/16 unit)
SS = 4                  # sub-samples per side on texels an outline runs through
FRAMES = 8              # animation frames of the glow texture
STEP = 1.0 / 16         # body thicknesses are rounded to this
RIM = 0.36              # how deep the smooth rims of the sheets reach in (units)
RIM_CELLS = 4           # how far the stepped inner body is cut back under them (cells)


def _r(v):
    return round(v, 4)


class Sample:
    """A point on a part's surface. Sheets: x, y and d (how far inside the outline, in units).
    Rods: x, y, z, a (angle round the rod: 0 facing the viewer, +90 degrees facing right),
    u (distance round the rod), v (= y), d (distance from the nearer end; on a cap, from the rim)
    and cap (0 on the side, 1 on the top, -1 on the bottom)."""
    __slots__ = ('x', 'y', 'z', 'd', 'a', 'u', 'v', 'cap')

    def __init__(self, x, y, d=0.0, z=8.0, a=0.0, u=0.0, v=0.0, cap=0):
        self.x, self.y, self.d, self.z, self.a, self.u, self.v, self.cap = x, y, d, z, a, u, v, cap


class Part:
    def __init__(self, name, colour, height=None, relief=1.0, metal=0.0, gloss=0.3, spec=0.5, sheen=None,
                 glow=None, glow_colours=None, glow_strength=1.0, emissive=False, bloom=1.0, coat=0.0):
        self.name = name
        self.colour = colour            # Sample -> (r, g, b) 0..1, or a hex code
        self.height = height            # Sample -> units: the surface's shape, for the light
        self.relief = relief            # how much the height is exaggerated for the light
        self.metal = metal              # 0..1 or Sample -> 0..1
        self.gloss = gloss
        self.spec = spec
        self.sheen = sheen              # Sample -> extra light reflected by metal (streaks)
        self.glow = glow                # (Sample, t) -> 0..1 for glowing parts; t = 0..1 over the animation
        self.glow_colours = glow_colours or paint.ramp('#000000', '#ffffff')
        self.glow_strength = glow_strength
        self.emissive = emissive        # lit up in the dark without being animated
        self.bloom = bloom              # how strongly the glow lights up the surfaces round it
        self.coat = coat                # 0..1: a clear glossy coat (glass, hard candy, lacquer)

    def get(self, attr, s):
        v = getattr(self, attr)
        return v(s) if callable(v) else v

    def albedo(self, s):
        c = self.colour(s) if callable(self.colour) else self.colour
        return paint.hexrgb(c) if isinstance(c, str) else c

    def emission(self, s, t):
        g = paint.clamp(self.glow(s, t))
        c = paint.lin(self.glow_colours(g))
        k = self.glow_strength * (0.25 + 0.75 * g)
        return (c[0] * k, c[1] * k, c[2] * k)


class Sheet(Part):
    def __init__(self, name, mask, thick, colour, box=None, **kw):
        super().__init__(name, colour, **kw)
        self.mask = mask                # (x, y) -> bool
        self.thick = thick              # units, or Sample -> units; None: as thick as the part under it
        self.box = box or getattr(mask, 'box', None)   # (x0, y0, x1, y1) where the outline lies, if known
        # a regular polygon (sides 4 or 8, centre x, y, radius to a corner, angle of a corner): its body
        # is then made of turned boxes with perfectly straight facets instead of small steps
        self.solid = getattr(mask, 'solid', None)


class Rod(Part):
    def __init__(self, name, x, y0, y1, r, colour, sides=8, caps=(False, False), z=8.0, **kw):
        super().__init__(name, colour, **kw)
        self.x, self.y0, self.y1, self.r, self.z = x, y0, y1, r, z
        self.sides = sides
        self.caps = caps                # (bottom, top): draw the end faces


def shape(poly):
    """A mask for a polygon, with its bounding box (much faster to scan)."""
    xs, ys = [p[0] for p in poly], [p[1] for p in poly]
    x0, x1, y0, y1 = min(xs), max(xs), min(ys), max(ys)

    def mask(x, y):
        return x0 <= x <= x1 and y0 <= y <= y1 and paint.inside(poly, x, y)
    mask.box = (x0, y0, x1, y1)
    return mask


class Weapon:
    def __init__(self, name, grip):
        self.name = name
        self.grip = grip                # height of the middle of the grip (for holding it)
        self.parts = []

    def sheet(self, name, mask, thick, colour, **kw):
        p = Sheet(name, mask, thick, colour, **kw)
        self.parts.append(p)
        return p

    def rod(self, name, x, y0, y1, r, colour, **kw):
        p = Rod(name, x, y0, y1, r, colour, **kw)
        self.parts.append(p)
        return p

    def bake(self):
        return Baked(self)


# ---- baking ----------------------------------------------------------------------------------------


def _xy(i, j):
    return (i + 0.5) / D, 16 - (j + 0.5) / D


class _Raster:
    """One sheet's outline on the texel grid (inside its bounding box) and its distance field."""

    def __init__(self, part):
        self.part = part
        box = part.box
        if box is None:
            hits = [(i, j) for j in range(0, N, 2) for i in range(0, N, 2) if part.mask(*_xy(i, j))]
            if not hits:
                raise ValueError(f'{part.name}: empty')
            box = (min(h[0] for h in hits) / D, 16 - max(h[1] for h in hits) / D,
                   max(h[0] for h in hits) / D, 16 - min(h[1] for h in hits) / D)
        m = 4
        self.i0 = max(0, int(box[0] * D) - m)
        self.i1 = min(N - 1, int(box[2] * D) + m)
        self.j0 = max(0, int((16 - box[3]) * D) - m)
        self.j1 = min(N - 1, int((16 - box[1]) * D) + m)
        self.m = [[part.mask(*_xy(i, j)) for i in range(self.i0, self.i1 + 1)] for j in range(self.j0, self.j1 + 1)]
        self.dist = paint.distance(self.m)

    def has(self, i, j):
        return self.i0 <= i <= self.i1 and self.j0 <= j <= self.j1 and self.m[j - self.j0][i - self.i0]

    def d(self, x, y):
        """Distance inside the outline at (x, y), units (bilinear)."""
        fi = x * D - 0.5 - self.i0
        fj = (16 - y) * D - 0.5 - self.j0
        h, w = len(self.dist), len(self.dist[0])
        i, j = int(math.floor(fi)), int(math.floor(fj))
        fx, fy = fi - i, fj - j

        def at(a, b):
            if 0 <= a < w and 0 <= b < h:
                return self.dist[b][a]
            return 0.0
        v = (at(i, j) * (1 - fx) * (1 - fy) + at(i + 1, j) * fx * (1 - fy)
             + at(i, j + 1) * (1 - fx) * fy + at(i + 1, j + 1) * fx * fy)
        return v / D


def _normal_sheet(part, ras, x, y, s0):
    if part.height is None:
        return (0.0, 0.0, 1.0)
    e = 0.75 / D
    hx1 = part.height(Sample(x + e, y, ras.d(x + e, y)))
    hx0 = part.height(Sample(x - e, y, ras.d(x - e, y)))
    hy1 = part.height(Sample(x, y + e, ras.d(x, y + e)))
    hy0 = part.height(Sample(x, y - e, ras.d(x, y - e)))
    k = part.relief / (2 * e)
    return paint.norm((-(hx1 - hx0) * k, -(hy1 - hy0) * k, 1.0))


class Baked:
    """Everything Minecraft needs for one weapon: model elements, the main texture (N x N) and the
    animated glow texture."""

    def __init__(self, weapon):
        self.weapon = weapon
        self.sheets = [p for p in weapon.parts if isinstance(p, Sheet)]
        self.rods = [p for p in weapon.parts if isinstance(p, Rod)]
        self.rasters = [_Raster(p) for p in self.sheets]
        self.tex = [[None] * N for _ in range(N)]          # final RGBA of the main texture
        self.used = [[False] * N for _ in range(N)]        # texels the model shows (for packing)
        self.elements = []
        self._design()
        self._body()
        self._rods()
        self._glow_texture()
        self._fill_empty()

    # -- the sheets, painted front-on ------------------------------------------------------------

    def _thick(self, k, x, y, i, j):
        part = self.sheets[k]
        while part.thick is None and k > 0:
            k -= 1
            while k > 0 and not self.rasters[k].has(i, j):
                k -= 1
            part = self.sheets[k]
        t = part.thick
        if callable(t):
            t = t(Sample(x, y, self.rasters[k].d(x, y)))
        return t or STEP

    def _owner_at(self, x, y, candidates):
        for k in candidates:
            if self.sheets[k].mask(x, y):
                return k
        return -1

    def _design(self):
        rs = self.rasters
        bx0 = min(r.i0 for r in rs) - 24
        by0 = min(r.j0 for r in rs) - 24
        bx1 = max(r.i1 for r in rs) + 24
        by1 = max(r.j1 for r in rs) + 24
        bx0, by0, bx1, by1 = max(0, bx0), max(0, by0), min(N - 1, bx1), min(N - 1, by1)
        self.crop = (bx0, by0, bx1, by1)
        owner = [[-1] * N for _ in range(N)]
        for k, r in enumerate(rs):
            for jj, row in enumerate(r.m):
                orow = owner[r.j0 + jj]
                for ii, v in enumerate(row):
                    if v:
                        orow[r.i0 + ii] = k
        self.owner = owner
        # coverage: texel -> list of (part, weight, x, y)
        cover = {}
        for j in range(by0, by1 + 1):
            for i in range(bx0, bx1 + 1):
                o = owner[j][i]
                nb = set()
                for dj in (-1, 0, 1):
                    for di in (-1, 0, 1):
                        nb.add(owner[min(N - 1, max(0, j + dj))][min(N - 1, max(0, i + di))])
                if len(nb) == 1:
                    if o >= 0:
                        x, y = _xy(i, j)
                        cover[(i, j)] = [(o, 1.0, x, y)]
                    continue
                cands = sorted((k for k in nb if k >= 0), reverse=True)
                acc = {}
                for sj in range(SS):
                    for si in range(SS):
                        x = (i + (si + 0.5) / SS) / D
                        y = 16 - (j + (sj + 0.5) / SS) / D
                        k = self._owner_at(x, y, cands)
                        if k >= 0:
                            a = acc.setdefault(k, [0, 0.0, 0.0])
                            a[0] += 1
                            a[1] += x
                            a[2] += y
                if acc:
                    cover[(i, j)] = [(k, a[0] / (SS * SS), a[1] / a[0], a[2] / a[0]) for k, a in acc.items()]
        self.cover = cover
        # front height (half the thickness) -> soft occlusion and shadows from raised parts
        W, H = bx1 - bx0 + 1, by1 - by0 + 1
        z = [[0.0] * W for _ in range(H)]
        for (i, j), parts in cover.items():
            z[j - by0][i - bx0] = sum(w * self._thick(k, x, y, i, j) / 2 for k, w, x, y in parts)
        near = paint.blur(z, 3)
        far = paint.blur(z, 7)
        lx, ly = paint.LIGHT[0], paint.LIGHT[1]
        n = math.hypot(lx, ly)
        ox, oy = -lx / n * 4, ly / n * 4          # towards the light, in texels (rows grow downwards)
        occl = {}
        for (i, j) in cover:
            a, b = i - bx0, j - by0
            here = z[b][a]
            ao = max(0.0, near[b][a] - here) * 5 + max(0.0, far[b][a] - here) * 2
            sa, sb = int(round(a + ox)), int(round(b - oy))
            sa, sb = min(W - 1, max(0, sa)), min(H - 1, max(0, sb))
            sh = max(0.0, near[sb][sa] - here - 0.03) * 4
            occl[(i, j)] = (1 - min(0.55, ao)) * (1 - min(0.45, sh))
        # light every texel; glowing parts also get their emission (averaged over the animation)
        self.base = {}
        self.glowing = {}               # texel -> [(part index, weight, x, y)] for the animation
        emit = [[[0.0, 0.0, 0.0] for _ in range(W)] for _ in range(H)]
        spill = [[[0.0, 0.0, 0.0] for _ in range(W)] for _ in range(H)]
        for (i, j), parts in cover.items():
            col = [0.0, 0.0, 0.0]
            alpha = 0.0
            for k, w, x, y in parts:
                part, ras = self.sheets[k], rs[k]
                s = Sample(x, y, ras.d(x, y))
                n = _normal_sheet(part, ras, x, y, s)
                c = paint.shade(part.albedo(s), n, part.get('metal', s), part.get('gloss', s),
                                part.get('spec', s), part.get('sheen', s) or 0.0, occl[(i, j)],
                                part.get('coat', s))
                for q in range(3):
                    col[q] += c[q] * w
                alpha += w
                if part.glow is not None:
                    self.glowing.setdefault((i, j), []).append((k, w, x, y))
                    e = [0.0, 0.0, 0.0]
                    for f in range(FRAMES):
                        ef = part.emission(s, f / FRAMES)
                        for q in range(3):
                            e[q] += ef[q] / FRAMES
                    cell = emit[j - by0][i - bx0]
                    out = spill[j - by0][i - bx0]
                    for q in range(3):
                        cell[q] += e[q] * w
                        out[q] += e[q] * w * part.bloom
            if alpha:
                col = [c / alpha for c in col]
            self.base[(i, j)] = (col, alpha)
        # bloom: glowing parts light up the surfaces round them
        self.halo = {}
        if self.glowing:
            chans = [[[spill[b][a][q] for a in range(W)] for b in range(H)] for q in range(3)]
            soft = [paint.blur(c, 4) for c in chans]
            wide = [paint.blur(c, 12) for c in chans]
            for (i, j) in cover:
                a, b = i - bx0, j - by0
                self.halo[(i, j)] = [soft[q][b][a] * 0.4 + wide[q][b][a] * 0.25 for q in range(3)]
        self.emit_static = {(i, j): emit[j - by0][i - bx0] for (i, j) in self.glowing}
        for (i, j), (col, alpha) in self.base.items():
            c = list(col)
            e = self.emit_static.get((i, j))
            h = self.halo.get((i, j))
            for q in range(3):
                if e:
                    c[q] += e[q]
                if h:
                    c[q] += h[q]
            self.tex[j][i] = paint.finish(c, 1.0 if alpha >= 0.5 else 0.0)
            self.used[j][i] = True
        self.design = [row[:] for row in self.tex]          # the painted sheets alone, for sampling

    def frame_colour(self, i, j, t):
        """A glowing texel at time t (the glow texture's frames)."""
        col, alpha = self.base[(i, j)]
        c = list(col)
        h = self.halo.get((i, j))
        for k, w, x, y in self.glowing.get((i, j), ()):
            part = self.sheets[k]
            e = part.emission(Sample(x, y, self.rasters[k].d(x, y)), t)
            for q in range(3):
                c[q] += e[q] * w
        if h:
            for q in range(3):
                c[q] += h[q]
        return paint.finish(c, 1.0)

    # -- the sheets' solid body ------------------------------------------------------------------

    def _body(self):
        G = N // CELL
        cells = {}
        for cj in range(G):
            for ci in range(G):
                ok = True
                for dj in range(CELL):
                    for di in range(CELL):
                        b = self.base.get((ci * CELL + di, cj * CELL + dj))
                        if b is None or b[1] < 0.999:
                            ok = False
                            break
                    if not ok:
                        break
                if not ok:
                    continue
                x, y = (ci + 0.5) * CELL / D, 16 - (cj + 0.5) * CELL / D
                i, j = ci * CELL + CELL // 2, cj * CELL + CELL // 2
                cands = sorted({self.owner[j - dj][i - di] for dj in (0, 1) for di in (0, 1)}, reverse=True)
                k = self._owner_at(x, y, [c for c in cands if c >= 0])
                if k < 0:
                    continue
                part = self.sheets[k]
                t = max(STEP, round(self._thick(k, x, y, i, j) / STEP) * STEP)
                cells[(ci, cj)] = (round(t, 4), bool(part.emissive), k if part.solid else -1)
        # solid parts (gems, settings) get their own turned boxes
        solids, solid_cells = {}, set()
        for c, v in list(cells.items()):
            if v[2] >= 0:
                solids[v[2]] = v[0]
                solid_cells.add(c)
                del cells[c]
        cells = {c: v[:2] for c, v in cells.items()}
        self.solid_faces = []
        for k, t in sorted(solids.items()):
            self._solid(self.sheets[k], t)
        # every side that can be seen gets a smooth rim: long boxes turned to follow the outline in
        # steps of 22.5 degrees (as Minecraft allows), RIM units deep. Under the rims the body is
        # cut back, so its small steps are hidden inside and only the clean rims show.
        self.bands = []
        drop = set()
        for key in sorted(set(cells.values())):
            t = key[0]

            def thinner(c):
                if c in solid_cells:
                    return False
                v = cells.get(c)
                return v is None or v[0] < t
            region = {c for c, v in cells.items() if v == key}
            edges = []
            for c, r in region:
                for (dc, dr), edge in (((0, -1), ((c, r), (c + 1, r))), ((1, 0), ((c + 1, r), (c + 1, r + 1))),
                                       ((0, 1), ((c + 1, r + 1), (c, r + 1))), ((-1, 0), ((c, r + 1), (c, r)))):
                    if thinner((c + dc, r + dr)):
                        edges.append(edge)
            for chain in _trace(edges):
                self._rim(chain, region, key)
            depth = RIM_CELLS
            front = {c for c in region if any(thinner((c[0] + dc, c[1] + dr))
                                               for dc, dr in ((1, 0), (-1, 0), (0, 1), (0, -1)))}
            seen = set(front)
            for _ in range(depth):
                nxt = set()
                for c, r in front:
                    for dc, dr in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                        nb = (c + dc, r + dr)
                        if nb in region and nb not in seen:
                            nxt.add(nb)
                seen |= nxt
                front = nxt
            drop |= seen
        # the body inside, on a grid twice as coarse (its sides are all hidden)
        inner = {c: v for c, v in cells.items() if c not in drop}
        coarse = {}
        for (ci, cj), v in inner.items():
            if not ci % 2 and not cj % 2 and all(inner.get((ci + a, cj + b)) == v for a in (0, 1) for b in (0, 1)):
                coarse[(ci // 2, cj // 2)] = v
        self.cells = {}
        for (ci, cj), v in coarse.items():
            for a in (0, 1):
                for b in (0, 1):
                    self.cells[(2 * ci + a, 2 * cj + b)] = v
        for ci, cj, c1, r1, key in _merge(coarse):
            self._box(2 * ci, 2 * cj, 2 * c1 + 1, 2 * r1 + 1, *key)
        self.thickness = cells          # the full body, for placing the glow panels
        # cards through the middle of the sheets carry their smooth outlines; they hug the shapes
        # (a strip of rows at a time) so the rest of the texture stays free for the rods
        for i0, j0, i1, j1 in _strips(self.cover):
            x0, x1, y0, y1 = i0 / D, i1 / D, 16 - j1 / D, 16 - j0 / D
            for jj in range(j0, j1):
                for ii in range(i0, i1):
                    self.used[jj][ii] = True
            self.elements.append({
                'from': [_r(x0), _r(y0), 8], 'to': [_r(x1), _r(y1), 8],
                'faces': {'south': {'uv': [_r(x0), _r(16 - y1), _r(x1), _r(16 - y0)], 'texture': '#0'},
                          'north': {'uv': [_r(x1), _r(16 - y1), _r(x0), _r(16 - y0)], 'texture': '#0'}},
            })

    def _solid(self, part, t):
        sides, cx, cy, R, turn = part.solid
        if sides == 4:
            h = R / math.sqrt(2)
            ang = (math.degrees(turn) - 45 + 45) % 90 - 45
            boxes = [((cx - h, cy - h), (cx + h, cy + h), ang, ['east', 'west', 'up', 'down'], None)]
        else:
            a = R * math.cos(math.pi / 8)
            s = 2 * a * math.tan(math.pi / 8)
            boxes = []
            for k in range(4):
                psi = math.degrees(turn) + 22.5 + 45 * k
                psi = (psi + 90) % 180 - 90
                if abs(psi) <= 45.01:
                    boxes.append(((cx - a, cy - s / 2), (cx + a, cy + s / 2), psi, ['east', 'west'], psi))
                else:
                    rot = psi - 90 if psi > 0 else psi + 90
                    boxes.append(((cx - s / 2, cy - a), (cx + s / 2, cy + a), rot, ['up', 'down'], psi))
        for (x0, y0), (x1, y1), ang, rim, facing in boxes:
            e = {'from': [_r(x0), _r(y0), _r(8 - t / 2)], 'to': [_r(x1), _r(y1), _r(8 + t / 2)], 'faces': {}}
            if ang:
                e['rotation'] = {'angle': round(ang, 2), 'axis': 'z', 'origin': [_r(cx), _r(cy), 8]}
            if part.emissive:
                e['light_emission'] = 15
            self.elements.append(e)
            for face in ['south', 'north'] + rim:
                self.solid_faces.append((part, e, face, (cx, cy), facing))

    def _sample(self, x, y):
        """The main texture's colour at (x, y) (smoothly between texels), ignoring empty texels."""
        fi, fj = x * D - 0.5, (16 - y) * D - 0.5
        i, j = int(math.floor(fi)), int(math.floor(fj))
        fx, fy = fi - i, fj - j
        acc, wsum = [0.0, 0.0, 0.0], 0.0
        for a, b, wt in ((i, j, (1 - fx) * (1 - fy)), (i + 1, j, fx * (1 - fy)),
                         (i, j + 1, (1 - fx) * fy), (i + 1, j + 1, fx * fy)):
            p = self.design[b][a] if 0 <= a < N and 0 <= b < N else None
            if p and p[3] and wt > 0:
                for q in range(3):
                    acc[q] += p[q] * wt
                wsum += wt
        if not wsum:
            return None
        return tuple(int(round(v / wsum)) for v in acc) + (255,)

    def _bake_solid(self, e, face, centre, facing, tl, tr, bl, w, h):
        cx, cy = centre
        out = []
        sector = math.pi / 8 + 0.03
        for b in range(h):
            row = []
            for a in range(w):
                fu, fv = (a + 0.5) / w, (b + 0.5) / h
                p = [tl[q] + (tr[q] - tl[q]) * fu + (bl[q] - tl[q]) * fv for q in range(3)]
                x, y = p[0], p[1]
                if face in ('south', 'north'):
                    if facing is not None and math.hypot(x - cx, y - cy) > 1e-3:
                        diff = (math.atan2(y - cy, x - cx) - math.radians(facing)) % math.pi
                        if min(diff, math.pi - diff) > sector:
                            row.append((0, 0, 0, 0))     # this box's share of the front is elsewhere
                            continue
                    c = self._sample(x, y)
                else:
                    # a side: the colour just inside the outline, a little darker
                    d = math.hypot(x - cx, y - cy) or 1.0
                    k = max(0.0, 1 - 2.5 / D / d)
                    c = self._sample(cx + (x - cx) * k, cy + (y - cy) * k)
                    if c:
                        c = (int(c[0] * 0.8), int(c[1] * 0.8), int(c[2] * 0.8), 255)
                row.append(c or (0, 0, 0, 0))
            out.append(row)
        return out

    def _bake_rim(self, band, face, tl, tr, bl, w, h):
        e, key, (dx, dy), (nx, ny), o, s0, s1, aligned = band
        out = []
        for b in range(h):
            row = []
            for a in range(w):
                fu, fv = (a + 0.5) / w, (b + 0.5) / h
                x = tl[0] + (tr[0] - tl[0]) * fu + (bl[0] - tl[0]) * fv
                y = tl[1] + (tr[1] - tl[1]) * fu + (bl[1] - tl[1]) * fv
                if face == 'south':
                    if not self._owns(band, x, y):
                        row.append((0, 0, 0, 0))
                        continue
                    c = self._sample(x, y)
                else:
                    c = self._sample(x - nx * 2.5 / D, y - ny * 2.5 / D)
                    if c:
                        c = (int(c[0] * 0.85), int(c[1] * 0.85), int(c[2] * 0.85), 255)
                row.append(c or (0, 0, 0, 0))
            out.append(row)
        return out

    def _owns(self, band, x, y):
        """Whether this rim shows the front at (x, y): not where the inner body does, and only the
        rim whose outside is nearest (so no two faces ever lie on top of each other)."""
        cell = (int(x * D // CELL), int((16 - y) * D // CELL))
        if cell in self.cells:
            return False
        best, who = None, None
        for other in self.bands:
            if other[1] != band[1]:
                continue
            _, _, (dx, dy), (nx, ny), o, s0, s1, aligned = other
            s, n = x * dx + y * dy, x * nx + y * ny
            if s0 <= s <= s1 and o - RIM <= n <= o + 1e-6:
                if aligned:
                    return False             # straight rims show the design there exactly
                d = o - n
                if best is None or d < best:
                    best, who = d, other
        return who is band

    def _box(self, ci, cj, c1, r1, t, emissive):
        """A box of the inner body: front and back only (its sides are hidden under the rims)."""
        x0, x1 = ci * CELL / D, (c1 + 1) * CELL / D
        y1, y0 = 16 - cj * CELL / D, 16 - (r1 + 1) * CELL / D
        e = {'from': [_r(x0), _r(y0), _r(8 - t / 2)], 'to': [_r(x1), _r(y1), _r(8 + t / 2)], 'faces': {}}
        u0, u1, v0, v1 = x0, x1, 16 - y1, 16 - y0
        e['faces']['south'] = {'uv': [_r(u0), _r(v0), _r(u1), _r(v1)], 'texture': '#0'}
        e['faces']['north'] = {'uv': [_r(u1), _r(v0), _r(u0), _r(v1)], 'texture': '#0'}
        if emissive:
            e['light_emission'] = 15
        self.elements.append(e)

    def _rim(self, chain, region, key):
        """Turned boxes along one traced outline (a list of cell corners)."""
        t, emissive = key
        k = CELL / D
        closed = len(chain) > 2 and chain[0] == chain[-1]
        pts = [(c * k, 16 - r * k) for c, r in (chain[:-1] if closed else chain)]
        m = len(pts)
        if m < 2:
            return
        # smooth the staircase into the curve it stands for
        win = 4
        sp = []
        for i in range(m):
            acc, n = [0.0, 0.0], 0
            for o in range(-win, win + 1):
                j = i + o
                if closed:
                    j %= m
                elif j < 0 or j >= m:
                    continue
                acc[0] += pts[j][0]
                acc[1] += pts[j][1]
                n += 1
            sp.append((acc[0] / n, acc[1] / n))

        def at(i):
            return sp[i % m] if closed else sp[min(m - 1, max(0, i))]
        step = math.pi / 8
        q = []
        for i in range(m):
            a, b = at(i - 2), at(i + 2)
            q.append(round(math.atan2(b[1] - a[1], b[0] - a[0]) / step))
        # runs of the same direction; tiny runs join their neighbour
        runs = []
        for i in range(m):
            if runs and runs[-1][0] == q[i]:
                runs[-1][1].append(i)
            else:
                runs.append([q[i], [i]])
        if closed and len(runs) > 1 and runs[0][0] == runs[-1][0]:
            runs[0][1] = runs[-1][1] + runs[0][1]
            runs.pop()
        merged = []
        for r in runs:
            if merged and len(r[1]) < 3:
                merged[-1][1] += r[1]
            elif merged and len(merged[-1][1]) < 3:
                merged[-1] = [r[0], merged[-1][1] + r[1]]
            else:
                merged.append(r)
        if not merged:
            return
        for qi, idx in merged:
            run = [sp[i] for i in idx]
            nxt = idx[-1] + 1
            if closed or nxt < m:
                run.append(sp[nxt % m])                  # reach the next run: no gaps at the joints
            th = qi * step
            dx, dy = math.cos(th), math.sin(th)
            nx, ny = dy, -dx
            mx = sum(p[0] for p in run) / len(run)
            my = sum(p[1] for p in run) / len(run)
            probe = (int((mx + nx * 0.12) * D // CELL), int((16 - (my + ny * 0.12)) * D // CELL))
            if probe in region:
                nx, ny = -nx, -ny                        # make n point out of the shape
            o = sum(p[0] * nx + p[1] * ny for p in run) / len(run)
            s = [p[0] * dx + p[1] * dy for p in run]
            ext = 1.5 * k
            s0, s1 = min(s) - ext, max(s) + ext
            if s1 - s0 < 2 * k:
                continue
            mid = (s0 + s1) / 2
            cx, cy = dx * mid + nx * (o - RIM / 2), dy * mid + ny * (o - RIM / 2)
            ang = math.degrees(th) % 180
            if ang > 90:
                ang -= 180
            half_l, half_w = (s1 - s0) / 2, RIM / 2
            if abs(ang) <= 45.01:
                lo, hi, rot = (cx - half_l, cy - half_w), (cx + half_l, cy + half_w), ang
            else:
                lo, hi = (cx - half_w, cy - half_l), (cx + half_w, cy + half_l)
                rot = ang - 90 if ang > 0 else ang + 90
            e = {'from': [_r(lo[0]), _r(lo[1]), _r(8 - t / 2)], 'to': [_r(hi[0]), _r(hi[1]), _r(8 + t / 2)],
                 'faces': {}}
            if abs(rot) > 1e-6:
                e['rotation'] = {'angle': round(rot, 2), 'axis': 'z', 'origin': [_r(cx), _r(cy), 8]}
            if emissive:
                e['light_emission'] = 15
            # which of its sides faces out
            rr = ('z', rot, (cx, cy, 8.0))
            best, outer = -2, None
            for face, normal in (('east', (1, 0, 0)), ('west', (-1, 0, 0)), ('up', (0, 1, 0)), ('down', (0, -1, 0))):
                p0 = render.rotate((0.0, 0.0, 0.0), rr)
                p1 = render.rotate(normal, rr)
                dot = (p1[0] - p0[0]) * nx + (p1[1] - p0[1]) * ny
                if dot > best:
                    best, outer = dot, face
            self.elements.append(e)
            aligned = abs(rot) < 1e-6
            band = (e, key, (dx, dy), (nx, ny), o, s0, s1, aligned)
            self.bands.append(band)
            if aligned:
                # straight up or across: its front is simply the painted design
                (x0, y0), (x1, y1) = lo, hi
                e['faces']['south'] = {'uv': [_r(x0), _r(16 - y1), _r(x1), _r(16 - y0)], 'texture': '#0'}
                e['faces']['north'] = {'uv': [_r(x1), _r(16 - y1), _r(x0), _r(16 - y0)], 'texture': '#0'}
                for jj in range(max(0, int((16 - y1) * D)), min(N, int(math.ceil((16 - y0) * D)))):
                    for ii in range(max(0, int(x0 * D)), min(N, int(math.ceil(x1 * D)))):
                        self.used[jj][ii] = True
            else:
                self.solid_faces.append(('rim', e, 'south', band, None))
            self.solid_faces.append(('rim', e, outer, band, None))

    # -- rods ------------------------------------------------------------------------------------

    def _rods(self):
        patches = []          # (element face dict, w, h, bake function)
        for rod in self.rods:
            for e, faces in _rod_elements(rod):
                self.elements.append(e)
                for direction, cap in faces:
                    patches.append((rod, e, direction, cap))
        # bake each face into its own patch of the texture
        jobs = []
        for job in patches + self.solid_faces:
            e, direction = job[1], job[2]
            a, b = tuple(e['from']), tuple(e['to'])
            rot = e.get('rotation')
            rr = (rot['axis'], rot['angle'], tuple(rot['origin'])) if rot else None
            tl, tr, bl = (render.rotate(c, rr) for c in render.corners(direction, a, b))
            wu = math.dist(tl, tr)
            wv = math.dist(tl, bl)
            dens = D / 2 if not isinstance(job[0], Rod) and direction not in ('south', 'north') else D
            w, h = max(1, int(math.ceil(wu * dens))), max(1, int(math.ceil(wv * dens)))
            jobs.append((job, tl, tr, bl, w, h))
        jobs.sort(key=lambda j: -j[5])
        packer = _Packer(self.used)
        spots = {}
        # big patches one by one; small ones packed tightly into blocks first
        big = [k for k, j in enumerate(jobs) if j[4] > 48 or j[5] > 48]
        small = [k for k, j in enumerate(jobs) if not (j[4] > 48 or j[5] > 48)]
        for k in big:
            x, y = packer.place(jobs[k][4] + 2, jobs[k][5] + 2)
            spots[k] = (x + 1, y + 1)

        def put(group):
            rects = [(jobs[k][4] + 2, jobs[k][5] + 2) for k in group]
            # a strip 64 texels wide, or just as wide as a patch on its own (it fits in smaller gaps)
            width = max(r[0] for r in rects) if len(group) == 1 else max(64, max(r[0] for r in rects))
            where, height = _skyline(rects, width)
            try:
                bx, by = packer.place(width, height)
            except ValueError:
                if len(group) == 1:
                    raise
                put(group[:len(group) // 2])
                put(group[len(group) // 2:])
                return
            for k, (x, y) in zip(group, where):
                spots[k] = (bx + x + 1, by + y + 1)
        if small:
            put(small)
        for k, (job, tl, tr, bl, w, h) in enumerate(jobs):
            x, y = spots[k]
            if isinstance(job[0], Rod):
                rod, e, direction, cap = job
                pix = _bake_face(rod, tl, tr, bl, w, h, cap, e)
            elif job[0] == 'rim':
                _, e, direction, band, _ = job
                pix = self._bake_rim(band, direction, tl, tr, bl, w, h)
            else:
                part, e, direction, centre, facing = job
                pix = self._bake_solid(e, direction, centre, facing, tl, tr, bl, w, h)
            for b in range(-1, h + 1):
                for a in range(-1, w + 1):
                    p = pix[min(h - 1, max(0, b))][min(w - 1, max(0, a))]
                    self.tex[y + b][x + a] = p
                    self.used[y + b][x + a] = True
            k = 16.0 / N
            e['faces'][direction] = {'uv': [_r(x * k), _r(y * k), _r((x + w) * k), _r((y + h) * k)], 'texture': '#0'}
            if job[0] == 'rim' and direction == 'south':
                e['faces']['north'] = {'uv': [_r((x + w) * k), _r(y * k), _r(x * k), _r((y + h) * k)], 'texture': '#0'}

    # -- the animated glow texture ---------------------------------------------------------------

    def _glow_texture(self):
        """Glowing parts are covered by thin panels just above their surface (front and back) that
        light up in the dark and show a small animated texture: the glow shimmers while the large
        texture stays still."""
        self.glow_size = 0
        self.glow_frames = []
        mine = {}                                   # glow part -> its texels
        for (i, j), owners in self.glowing.items():
            k, w = max(((k, sum(o[1] for o in owners if o[0] == k)) for k in {o[0] for o in owners}),
                       key=lambda kw: kw[1])
            if w >= 0.5:
                mine.setdefault(k, set()).add((i, j))
        panels = []
        for k, texels in sorted(mine.items()):
            for i0, j0, i1, j1 in _strips(texels):
                # just above the thickest surface under it
                t = 0.0
                for cj in range(j0 // CELL, (j1 - 1) // CELL + 1):
                    for ci in range(i0 // CELL, (i1 - 1) // CELL + 1):
                        c = self.thickness.get((ci, cj))
                        if c:
                            t = max(t, c[0])
                if not t:
                    x, y = _xy((i0 + i1) // 2, (j0 + j1) // 2)
                    t = self._thick(k, x, y, (i0 + i1) // 2, (j0 + j1) // 2)
                panels.append((k, texels, (i0, j0, i1, j1), t / 2 + 0.02))
        if not panels:
            return
        rects = [(p[2][2] - p[2][0], p[2][3] - p[2][1]) for p in panels]
        size = 16
        while True:
            spots = _shelf(rects, size)
            if spots:
                break
            size *= 2
        self.glow_size = size
        frames = [[[(0, 0, 0, 0)] * size for _ in range(size)] for _ in range(FRAMES)]
        q = 16.0 / size
        for (k, texels, (i0, j0, i1, j1), dz), (gx, gy) in zip(panels, spots):
            for j in range(j0, j1):
                for i in range(i0, i1):
                    if (i, j) in texels:
                        for f in range(FRAMES):
                            frames[f][gy + j - j0][gx + i - i0] = self.frame_colour(i, j, f / FRAMES)
            x0, x1, y0, y1 = i0 / D, i1 / D, 16 - j1 / D, 16 - j0 / D
            u0, v0, u1, v1 = gx * q, gy * q, (gx + i1 - i0) * q, (gy + j1 - j0) * q
            for z, face, uv in ((8 + dz, 'south', [u0, v0, u1, v1]), (8 - dz, 'north', [u1, v0, u0, v1])):
                self.elements.append({
                    'from': [_r(x0), _r(y0), _r(z)], 'to': [_r(x1), _r(y1), _r(z)],
                    'faces': {face: {'uv': [_r(v) for v in uv], 'texture': '#1'}},
                    'light_emission': 15,
                })
        self.glow_frames = frames

    def _fill_empty(self):
        for row in self.tex:
            for i in range(N):
                if row[i] is None:
                    row[i] = (0, 0, 0, 0)

    # -- what the rest of the build uses -----------------------------------------------------------

    def points(self):
        """Outline points (x, y) of everything visible from the front, for fitting the slot."""
        pts = []
        for (i, j), (col, alpha) in self.base.items():
            if alpha >= 0.5:
                x, y = _xy(i, j)
                pts.append((x - 0.5 / D, y - 0.5 / D))
                pts.append((x + 0.5 / D, y + 0.5 / D))
        for rod in self.rods:
            pts += [(rod.x - rod.r, rod.y0), (rod.x + rod.r, rod.y1)]
        return pts


def _greedy(cells):
    """Merges cells {(c, r): key} into rectangles of equal keys, row by row:
    [(c0, r0, c1, r1, key)], inclusive."""
    done = set()
    out = []
    for c, r in sorted(cells, key=lambda p: (p[1], p[0])):
        if (c, r) in done:
            continue
        key = cells[(c, r)]
        c1 = c
        while (c1 + 1, r) in cells and (c1 + 1, r) not in done and cells[(c1 + 1, r)] == key:
            c1 += 1
        r1 = r
        while all((x, r1 + 1) in cells and (x, r1 + 1) not in done and cells[(x, r1 + 1)] == key
                  for x in range(c, c1 + 1)):
            r1 += 1
        for rr in range(r, r1 + 1):
            for cc in range(c, c1 + 1):
                done.add((cc, rr))
        out.append((c, r, c1, r1, key))
    return out


def _trace(edges):
    """Joins directed edges (corner -> corner) into chains: closed loops end where they start."""
    out_edges = {}
    incoming = {}
    for a, b in edges:
        out_edges.setdefault(a, []).append(b)
        incoming[b] = incoming.get(b, 0) + 1
    chains = []

    def walk(start):
        chain = [start]
        cur = start
        while out_edges.get(cur):
            nxt = out_edges[cur].pop()
            chain.append(nxt)
            cur = nxt
            if cur == start:
                break
        return chain
    for a in sorted(out_edges):
        if not incoming.get(a) and out_edges[a]:
            chains.append(walk(a))
    for a in sorted(out_edges):
        while out_edges[a]:
            chains.append(walk(a))
    return chains


def _merge(cells):
    """Fewest rectangles: rows first or columns first, whichever gives fewer."""
    rows = _greedy(cells)
    cols = [(r0, c0, r1, c1, key) for c0, r0, c1, r1, key in _greedy({(r, c): v for (c, r), v in cells.items()})]
    return min(rows, cols, key=len)


def _strips(texels, band=D // 2, gap=D // 2):
    """Covers a set of texels with few rectangles that hug it: strips half a unit wide (rows or
    columns, whichever wastes less), each split where the texels have a gap.
    [(i0, j0, i1, j1)], exclusive."""
    def by_rows(pts):
        rows = {}
        for i, j in pts:
            rows.setdefault(j // band, {}).setdefault(i, []).append(j)
        out = []
        for b, cols in sorted(rows.items()):
            xs = sorted(cols)
            runs, start, last = [], xs[0], xs[0]
            for i in xs[1:]:
                if i - last > gap:
                    runs.append((start, last + 1))
                    start = i
                last = i
            runs.append((start, last + 1))
            for i0, i1 in runs:
                js = [j for i in range(i0, i1) for j in cols.get(i, ())]
                out.append((i0, min(js), i1, max(js) + 1))
        return out
    rows = by_rows(texels)
    cols = [(j0, i0, j1, i1) for i0, j0, i1, j1 in by_rows([(j, i) for i, j in texels])]
    area = lambda rs: sum((r[2] - r[0]) * (r[3] - r[1]) for r in rs)
    return min(rows, cols, key=area)


def _rod_elements(rod):
    """The boxes of an 8 or 16 sided prism, with the faces each shows: [(element, [(face, cap)])]."""
    a = rod.r
    s = 2 * a * math.tan(math.pi / rod.sides)
    cx, cz = rod.x, rod.z
    if rod.sides == 8:
        bands = [('x', 0), ('z', 0), ('x', 45), ('x', -45)]
    else:
        bands = [('x', 0), ('x', 22.5), ('x', -22.5), ('x', 45), ('x', -45), ('z', 0), ('z', 22.5), ('z', -22.5)]
    out = []
    pieces = max(1, int(math.ceil((rod.y1 - rod.y0) / 4.0)))
    for p in range(pieces):
        y0 = rod.y0 + (rod.y1 - rod.y0) * p / pieces
        y1 = rod.y0 + (rod.y1 - rod.y0) * (p + 1) / pieces
        for base, ang in bands:
            if base == 'x':
                e = {'from': [_r(cx - a), _r(y0), _r(cz - s / 2)], 'to': [_r(cx + a), _r(y1), _r(cz + s / 2)]}
                sides = ['east', 'west']
            else:
                e = {'from': [_r(cx - s / 2), _r(y0), _r(cz - a)], 'to': [_r(cx + s / 2), _r(y1), _r(cz + a)]}
                sides = ['south', 'north']
            if ang:
                e['rotation'] = {'angle': ang, 'axis': 'y', 'origin': [_r(cx), _r(y0), _r(cz)]}
            e['faces'] = {}
            if rod.emissive:
                e['light_emission'] = 15
            faces = [(f, 0) for f in sides]
            if rod.caps[0] and p == 0:
                faces.append(('down', -1))
            if rod.caps[1] and p == pieces - 1:
                faces.append(('up', 1))
            out.append((e, faces))
    return out


def _bake_face(rod, tl, tr, bl, w, h, cap, e):
    """The texels of one face of a rod: position on the face -> the round surface's look."""
    rot = e.get('rotation')
    ang = math.radians(rot['angle']) if rot else 0.0
    # the angle this box's sides face (for splitting the caps between the boxes)
    if e['to'][0] - e['from'][0] > e['to'][2] - e['from'][2]:
        facing = math.atan2(math.cos(ang), -math.sin(ang))      # 'x' box: its east face
    else:
        facing = math.atan2(math.sin(ang), math.cos(ang))       # 'z' box: its south face
    sector = math.pi / rod.sides + 0.03
    out = []
    r = rod.r
    for b in range(h):
        row = []
        for a in range(w):
            fu, fv = (a + 0.5) / w, (b + 0.5) / h
            p = [tl[q] + (tr[q] - tl[q]) * fu + (bl[q] - tl[q]) * fv for q in range(3)]
            dx, dz = p[0] - rod.x, p[2] - rod.z
            th = math.atan2(dx, dz)
            if cap:
                rho = math.hypot(dx, dz)
                if rho > 1e-3:
                    diff = (th - facing) % math.pi
                    diff = min(diff, math.pi - diff)
                    if diff > sector:
                        row.append((0, 0, 0, 0))
                        continue
                s = Sample(p[0], p[1], r - rho, p[2], th, p[0], p[2], cap)
                n = (0.0, float(cap), 0.0)
                if rod.height is not None:
                    eps = 0.75 / D
                    hx1 = rod.height(Sample(p[0] + eps, p[1], r - math.hypot(dx + eps, dz), p[2], th, p[0] + eps, p[2], cap))
                    hx0 = rod.height(Sample(p[0] - eps, p[1], r - math.hypot(dx - eps, dz), p[2], th, p[0] - eps, p[2], cap))
                    hz1 = rod.height(Sample(p[0], p[1], r - math.hypot(dx, dz + eps), p[2] + eps, th, p[0], p[2] + eps, cap))
                    hz0 = rod.height(Sample(p[0], p[1], r - math.hypot(dx, dz - eps), p[2] - eps, th, p[0], p[2] - eps, cap))
                    k = rod.relief / (2 * eps)
                    n = paint.norm((-(hx1 - hx0) * k, float(cap), -(hz1 - hz0) * k))
            else:
                ends = min(p[1] - rod.y0, rod.y1 - p[1])
                s = Sample(p[0], p[1], ends, p[2], th, th * r, p[1], 0)
                n = (math.sin(th), 0.0, math.cos(th))
                if rod.height is not None:
                    eps = 0.75 / D
                    du = eps / r
                    hu1 = rod.height(Sample(p[0], p[1], ends, p[2], th + du, (th + du) * r, p[1], 0))
                    hu0 = rod.height(Sample(p[0], p[1], ends, p[2], th - du, (th - du) * r, p[1], 0))
                    hv1 = rod.height(Sample(p[0], p[1] + eps, ends + eps, p[2], th, th * r, p[1] + eps, 0))
                    hv0 = rod.height(Sample(p[0], p[1] - eps, ends - eps, p[2], th, th * r, p[1] - eps, 0))
                    k = rod.relief / (2 * eps)
                    gu, gv = (hu1 - hu0) * k, (hv1 - hv0) * k
                    tu = (math.cos(th), 0.0, -math.sin(th))
                    n = paint.norm((n[0] - gu * tu[0], n[1] - gv, n[2] - gu * tu[2]))
            c = paint.shade(rod.albedo(s), n, rod.get('metal', s), rod.get('gloss', s), rod.get('spec', s),
                            rod.get('sheen', s) or 0.0, coat=rod.get('coat', s))
            if rod.glow is not None:
                em = rod.emission(s, 0.0)
                c = [c[q] + em[q] for q in range(3)]
            row.append(paint.finish(c, 1.0))
        out.append(row)
    return out


class _Packer:
    """Finds free rectangles in the main texture (away from everything already painted), on a
    grid of 4 x 4 texel blocks."""
    B = 2

    def __init__(self, used):
        B = self.B
        self.n = N // B
        self.busy = [[any(used[j][i] for j in range(bj * B, bj * B + B) for i in range(bi * B, bi * B + B))
                      for bi in range(self.n)] for bj in range(self.n)]

    def place(self, w, h):
        B, n = self.B, self.n
        bw, bh = -(-w // B), -(-h // B)                  # rounded up to whole blocks
        busy = self.busy
        sat = [[0] * (n + 1) for _ in range(n + 1)]
        for j in range(n):
            acc = 0
            for i in range(n):
                acc += busy[j][i]
                sat[j + 1][i + 1] = sat[j][i + 1] + acc
        for y in range(n - bh + 1):
            for x in range(n - bw + 1):
                if sat[y + bh][x + bw] - sat[y][x + bw] - sat[y + bh][x] + sat[y][x] == 0:
                    for j in range(y, y + bh):
                        for i in range(x, x + bw):
                            busy[j][i] = True
                    return x * B, y * B
        raise ValueError('texture full')


def _skyline(rects, width):
    """Packs rectangles into a strip `width` wide, as low as possible: (spots, height used)."""
    order = sorted(range(len(rects)), key=lambda k: (-rects[k][1], -rects[k][0]))
    sky = [0] * width
    spots = [None] * len(rects)
    for k in order:
        w, h = rects[k]
        best = None
        for x in range(0, width - w + 1):
            y = max(sky[x:x + w])
            if best is None or y < best[1]:
                best = (x, y)
        x, y = best
        for a in range(x, x + w):
            sky[a] = y + h
        spots[k] = best
    return spots, max(sky)


def _shelf(rects, size):
    """Places rectangles in a size x size square (a skyline: each goes as low as it can, the
    tallest first). None if they don't fit."""
    order = sorted(range(len(rects)), key=lambda k: (-rects[k][1], -rects[k][0]))
    sky = [0] * size
    spots = [None] * len(rects)
    for k in order:
        w, h = rects[k]
        best = None
        for x in range(0, size - w + 1):
            y = max(sky[x:x + w])
            if y + h <= size and (best is None or y < best[1]):
                best = (x, y)
        if best is None:
            return None
        x, y = best
        for a in range(x, x + w):
            sky[a] = y + h
        spots[k] = best
    return spots


# ---- the model file and a Blender export -----------------------------------------------------------


def model(baked, texture, glow_texture, display):
    textures = {'0': texture, 'particle': texture}
    if baked.glow_size:
        textures['1'] = glow_texture
    return {
        'credit': 'ᴠᴀɴɪʟʟᴀ sᴍᴘ legendary weapons',
        'texture_size': [N, N],
        'gui_light': 'front',
        'textures': textures,
        'elements': baked.elements,
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
    """A Wavefront OBJ (and its MTL) of the model, to open in Blender: 1 block = 1 metre. The glow
    parts use the first frame of the glow texture."""
    out = [f'# {name}: ᴠᴀɴɪʟʟᴀ sᴍᴘ legendary weapon', f'mtllib {name}.mtl', f'o {name}']
    vi = 1
    for tex, mat in (('#0', name), ('#1', name + '_glow')):
        lines = []
        for e in model_json['elements']:
            a, b = e['from'], e['to']
            r = e.get('rotation')
            rot = (r['axis'], r['angle'], tuple(r['origin'])) if r else None
            for direction, face in e['faces'].items():
                if face.get('texture', '#0') != tex:
                    continue
                u0, v0, u1, v1 = face['uv']
                for x, y, z in CORNERS[direction](a, b):          # TL, BL, BR, TR seen from outside
                    x, y, z = render.rotate((x, y, z), rot)
                    lines.append(f'v {x / 16:.5f} {y / 16:.5f} {z / 16:.5f}')
                for u, v in ((u0, v0), (u0, v1), (u1, v1), (u1, v0)):
                    lines.append(f'vt {u / 16:.5f} {1 - v / 16:.5f}')
                lines.append(f'f {vi}/{vi} {vi + 1}/{vi + 1} {vi + 2}/{vi + 2} {vi + 3}/{vi + 3}')
                vi += 4
        if lines:
            out.append(f'usemtl {mat}')
            out += lines
    mtl = []
    for mat in (name, name + '_glow'):
        mtl += [f'newmtl {mat}', 'Ka 1 1 1', 'Kd 1 1 1', 'Ks 0 0 0', 'd 1', 'illum 1',
                f'map_Kd {mat}.png', f'map_d {mat}.png', '']
    return '\n'.join(out) + '\n', '\n'.join(mtl)
