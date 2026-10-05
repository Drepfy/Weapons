"""Colours, smooth noise and the materials the weapons are painted with.

A material paints a face from the point it is given; everything is smooth (gradients, soft
noise, highlights), nothing is pixel art. Light falls from the top left: faces get brighter
towards their top-left edges and darker towards the bottom-right, which makes boxes read as
bevelled metal.
"""
import math


def hexrgb(h):
    h = h.lstrip('#')
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def mix(a, b, t):
    t = min(max(t, 0.0), 1.0)
    return tuple(a[i] + (b[i] - a[i]) * t for i in range(3))


def scale(c, k):
    return tuple(min(255.0, v * k) for v in c)


def add(c, k):
    return tuple(min(255.0, max(0.0, v + k)) for v in c)


def ramp(stops, t):
    """stops: [(position, colour)] sorted by position."""
    t = min(max(t, stops[0][0]), stops[-1][0])
    for (p0, c0), (p1, c1) in zip(stops, stops[1:]):
        if t <= p1:
            return mix(c0, c1, (t - p0) / (p1 - p0 or 1))
    return stops[-1][1]


def smooth(t):
    t = min(max(t, 0.0), 1.0)
    return t * t * (3 - 2 * t)


def _hash(ix, iy, seed):
    n = (ix * 374761393 + iy * 668265263 + seed * 2147483647) & 0xFFFFFFFF
    n = ((n ^ (n >> 13)) * 1274126177) & 0xFFFFFFFF
    return ((n ^ (n >> 16)) & 0xFFFF) / 65535.0


def noise(x, y, seed=0):
    """Smooth value noise in 0..1."""
    ix, iy = math.floor(x), math.floor(y)
    fx, fy = smooth(x - ix), smooth(y - iy)
    a, b = _hash(ix, iy, seed), _hash(ix + 1, iy, seed)
    c, d = _hash(ix, iy + 1, seed), _hash(ix + 1, iy + 1, seed)
    return a + (b - a) * fx + (c - a) * fy + (a - b - c + d) * fx * fy


def fbm(x, y, seed=0, octaves=3):
    total, amp, norm = 0.0, 1.0, 0.0
    for i in range(octaves):
        total += noise(x, y, seed + i * 31) * amp
        norm += amp
        x, y, amp = x * 2.03, y * 2.03, amp * 0.5
    return total / norm


def bevel(face, s, t, c, width=0.12, light=1.18, dark=0.72):
    """Brighter along a face's top and left edges, darker along its bottom and right."""
    w, h = max(face.w, 1e-6), max(face.h, 1e-6)
    d_top, d_left = t * h, s * w
    d_bottom, d_right = (1 - t) * h, (1 - s) * w
    k = 1.0
    if min(d_top, d_left) < width:
        k = max(k, light - (light - 1) * min(d_top, d_left) / width)
    if min(d_bottom, d_right) < width:
        k = min(k, dark + (1 - dark) * min(d_bottom, d_right) / width)
    return scale(c, k)


# ---- materials --------------------------------------------------------------------------------------
# Each takes (face, s, t, p) like a box paint and returns a colour. make_* build one with colours.

def metal(dark, mid, light, streak=0.08, seed=1, edge=True):
    """Brushed metal: a top-to-bottom sheen, fine streaks along the length, bevelled edges."""
    def paint(face, s, t, p):
        x, y, z = p
        k = 0.5 + 0.5 * math.sin((y * 0.55 + x * 0.35 + z * 0.2) * 1.3)      # broad soft reflection
        c = ramp([(0, dark), (0.55, mid), (1, light)], 0.25 + 0.6 * k)
        n = fbm(x * 0.6 + z * 0.6, y * 9.0, seed) - 0.5                        # streaks along y
        c = add(c, n * 255 * streak)
        return bevel(face, s, t, c) if edge else c
    return paint


def gold(seed=3):
    return metal(hexrgb('#7a4a12'), hexrgb('#d39b35'), hexrgb('#ffe9a6'), 0.06, seed)


def leather(base, band, band_light, turns=2.4, seed=5, wrap_width=0.42):
    """A grip wrapped in a spiral band; the band is lit on its upper edge."""
    def paint(face, s, t, p):
        x, y, z = p
        around = math.atan2(z - 8, x - 8) / (2 * math.pi)                   # 0..1 round the grip
        phase = (y * turns * 0.5 + around) % 1.0
        n = fbm(x * 3 + z * 3, y * 3, seed) - 0.5
        if phase < wrap_width:
            q = phase / wrap_width
            c = mix(band_light, band, smooth(q))
            c = scale(c, 1.0 - 0.35 * smooth((q - 0.75) / 0.25))
        else:
            c = scale(base, 0.85 + 0.3 * fbm(x * 8, y * 8, seed + 9))
            q = (phase - wrap_width) / (1 - wrap_width)
            c = scale(c, 0.65 + 0.35 * smooth(q * 3))                         # shadow under the band
        return bevel(face, s, t, add(c, n * 18), 0.08)
    return paint


def wood(dark, mid, light, seed=7):
    def paint(face, s, t, p):
        x, y, z = p
        g = fbm(x * 2.5 + z * 2.5, y * 0.35, seed)
        rings = 0.5 + 0.5 * math.sin(g * 18.0)
        c = ramp([(0, dark), (0.6, mid), (1, light)], 0.25 + 0.55 * rings * (0.6 + 0.4 * g))
        return bevel(face, s, t, c, 0.1)
    return paint


def crystal(deep, mid, bright, glow=1.0, seed=11):
    """A cut gem: facets as soft triangles of light and shade, a bright corner highlight."""
    def paint(face, s, t, p):
        u, v = s - 0.5, t - 0.5
        facet = (math.atan2(v, u) / math.pi + 1) * 2.0                       # 4 facets
        k = 0.35 + 0.35 * math.cos((facet % 1.0) * math.pi) * (0.5 + abs(u) + abs(v))
        k += 0.45 * max(0.0, 1 - math.hypot(s - 0.28, t - 0.25) * 3.2)       # highlight top-left
        k -= 0.3 * max(0.0, (s + t - 1.2))
        c = ramp([(0, deep), (0.5, mid), (1, bright)], k)
        return scale(c, glow)
    return paint


def flat(colour):
    def paint(face, s, t, p):
        return bevel(face, s, t, colour, 0.08)
    return paint


def glow(core, edge, seed=13):
    """Light: bright in the middle of the face, fading to the edge colour, softly flickering."""
    def paint(face, s, t, p):
        x, y, z = p
        d = min(s, 1 - s, t, 1 - t) * 2
        n = fbm(x * 2, y * 2 + z, seed)
        return mix(edge, core, smooth(d * 1.4) * (0.75 + 0.5 * n))
    return paint
