"""2D outlines for the cards: polygons, curves and distances (model units, y up)."""
import math


def bezier(p0, p1, p2, p3, n=24):
    out = []
    for i in range(n + 1):
        t = i / n
        u = 1 - t
        out.append((u ** 3 * p0[0] + 3 * u * u * t * p1[0] + 3 * u * t * t * p2[0] + t ** 3 * p3[0],
                    u ** 3 * p0[1] + 3 * u * u * t * p1[1] + 3 * u * t * t * p2[1] + t ** 3 * p3[1]))
    return out


def inside(poly, x, y):
    c = False
    n = len(poly)
    for i in range(n):
        (x1, y1), (x2, y2) = poly[i], poly[(i + 1) % n]
        if (y1 > y) != (y2 > y) and x < (x2 - x1) * (y - y1) / (y2 - y1) + x1:
            c = not c
    return c


def seg_dist(px, py, a, b):
    (x1, y1), (x2, y2) = a, b
    dx, dy = x2 - x1, y2 - y1
    t = max(0.0, min(1.0, ((px - x1) * dx + (py - y1) * dy) / float(dx * dx + dy * dy or 1)))
    return math.hypot(px - x1 - t * dx, py - y1 - t * dy)


def line_dist(pts, x, y):
    return min(seg_dist(x, y, pts[i], pts[i + 1]) for i in range(len(pts) - 1))


def mirror_x(pts, cx=8.0):
    return [(2 * cx - x, y) for x, y in pts]


def worst_fit(poly):
    """How far the outline reaches into the inventory slot's diamond (must be <= 11.3)."""
    return max(abs(x - 8) + abs(y - 8) for x, y in poly)
