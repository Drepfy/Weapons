"""Looks shared by the weapons: cut gems, wound grips, wood, hammered iron."""
import math

from paint import clamp, fbm, mix, noise, smooth


def gem(cx, cy, rx, ry, colours, facets=8, table=0.42, turn=0.0, depth=0.22):
    """A cut gem seen from the front: (mask, colour, height). The height is a faceted dome (a flat
    table in the middle, sloping facets round it), so the light picks out every facet; the colour
    deepens towards the rim and glows from inside at the lower right."""
    def polar(x, y):
        dx, dy = (x - cx) / rx, (y - cy) / ry
        return math.hypot(dx, dy), math.atan2(dy, dx)

    def mask(x, y):
        rho, a = polar(x, y)
        # a polygon with `facets` sides
        k = math.cos(math.pi / facets) / math.cos(((a - turn) % (2 * math.pi / facets)) - math.pi / facets)
        return rho <= k
    mask.box = (cx - rx, cy - ry, cx + rx, cy + ry)
    if rx == ry and facets in (4, 8):
        mask.solid = (facets, cx, cy, rx, turn)        # built from turned boxes: crisp facets in 3D

    def height(s):
        rho, a = polar(s.x, s.y)
        step = 2 * math.pi / facets
        f = math.floor((a - turn) / step)
        mid = turn + (f + 0.5) * step                       # the facet this point is on
        dx, dy = (s.x - cx) / rx, (s.y - cy) / ry
        along = dx * math.cos(mid) + dy * math.sin(mid)     # flat facets, not a round dome
        return depth * min(1 - table * 0.6, 1 - along * 0.85) * min(rx, ry)

    def colour(s):
        rho, a = polar(s.x, s.y)
        inner = smooth(0.95, 0.2, rho)
        glint = smooth(0.7, 0.0, math.hypot((s.x - cx) / rx - 0.25, (s.y - cy) / ry + 0.3))
        return colours(clamp(0.25 + 0.35 * inner + 0.4 * glint))
    return mask, colour, height


def helix(s, pitch, turns=1.0, phase=0.0):
    """Position (0..1) across a helical stripe wound round a rod: rods only."""
    return (s.y / pitch + turns * s.a / (2 * math.pi) + phase) % 1.0


def wrap(s, pitch, width, colours, gap_colour, seed=1):
    """A strap or cord wound round a rod with gaps between the turns: (colour, height)."""
    p = helix(s, pitch)
    d = abs(p - 0.5) / (width / 2)
    if d < 1:
        grain = 0.9 + 0.1 * noise(s.a * 12, s.y * 30, seed)
        c = colours(0.35 + 0.5 * math.sqrt(1 - d * d))
        return tuple(v * grain for v in c), 0.04 * math.sqrt(1 - d * d)
    return gap_colour, 0.0


def wood(s, colours, seed=3):
    """Long grain along the haft."""
    g = fbm(s.a * 2.2, s.y * 0.35, seed)
    streak = 0.5 + 0.5 * math.sin((s.a * 3 + g * 9) * 2.2)
    return colours(clamp(0.25 + 0.45 * g + 0.25 * streak * streak))


def hammered(x, y, base, seed=7, amount=0.25):
    """Forged iron: soft hammer dents and a little rust-brown in the hollows."""
    f = fbm(x * 3.2, y * 3.2, seed)
    c = tuple(v * (1 - amount / 2 + amount * f) for v in base)
    return mix(c, (0.23, 0.15, 0.11), 0.25 * smooth(0.45, 0.2, f))


def dent(x, y, seed=7):
    return 0.04 * fbm(x * 3.2, y * 3.2, seed)
