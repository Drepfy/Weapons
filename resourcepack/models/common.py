"""What all five weapon models share: how they are held, shown in the inventory, dropped and framed.

The models stand upright (16 units tall). In the inventory they are turned 45 degrees and scaled
to fill the slot from corner to corner. Held by others (third person) they are turned like
vanilla's sword and moved so the middle of their grip sits exactly where a vanilla sword's handle
does, a little bigger than a vanilla sword. In your own hand (first person) they are held further
into the screen than vanilla's sword, which mostly sits off the right edge: the whole weapon is
in view, its face turned towards you, and the crosshair stays clear.
"""
import math

# a vanilla sword's handle, relative to the model's centre (its sprite is diagonal)
VANILLA_GRIP = (-5.0, -5.0)
THIRD_PERSON = {'rotation_z': 55.0, 'translation': (0.0, 4.0, 0.5), 'scale': 0.85}
# Where Minecraft puts an item in the right hand before its first person transform (blocks; the
# eye at 0, x right, y up, z towards you).
HAND = (0.56, -0.52, -0.72)
# First person: where the middle of the grip is, which way the weapon points, which way its right
# side faces, and its size (1 = 16 units to a block). Wide heads (the scythe, the axes) sit a
# little further right and smaller, so they never reach the crosshair.
FIRST_PERSON = {
    'sword': ((0.50, -0.55, -0.80), (-0.30, 1.0, -0.45), (0.7, 0.0, 0.7), 1.05),
    'scythe': ((0.62, -0.56, -0.80), (-0.18, 1.0, -0.45), (0.7, 0.0, 0.7), 0.95),
    'axe': ((0.66, -0.56, -0.80), (-0.12, 1.0, -0.45), (0.7, 0.0, 0.7), 0.88),
}


def _rz(p, angle):
    c, s = math.cos(math.radians(angle)), math.sin(math.radians(angle))
    return p[0] * c - p[1] * s, p[0] * s + p[1] * c


def _held(vanilla, grip_y, scale):
    """Rotation and translation that put our grip where vanilla's is (right hand)."""
    turn = vanilla['rotation_z'] - 45.0                     # our model is upright, vanilla's diagonal
    vx, vy = _rz(VANILLA_GRIP, vanilla['rotation_z'])
    ox, oy = _rz((0.0, grip_y - 8.0), turn)
    dx = vx * vanilla['scale'] - ox * scale
    dy = vy * vanilla['scale'] - oy * scale
    tx, ty, tz = vanilla['translation']
    # the y rotation of -90 degrees maps (x, y, 0) to (0, y, x)
    return [0, -90, round(turn, 3)], [round(tx, 3), round(ty + dy, 3), round(tz + dx, 3)]


def _unit(v):
    n = math.sqrt(sum(c * c for c in v))
    return tuple(c / n for c in v)


def _cross(a, b):
    return (a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])


def _first_person(grip_y, hold):
    """The display rotation (x, y, z degrees, applied z first), translation and scale that put the
    grip at its place in front of the eye, the weapon pointing its way and its right side facing
    its way."""
    grip_at, points, side, scale = FIRST_PERSON[hold]
    y = _unit(points)
    z = _unit(_cross(_unit(side), y))
    x = _cross(y, z)
    rot = [[x[i], y[i], z[i]] for i in range(3)]          # model axes -> view
    b = math.asin(max(-1.0, min(1.0, rot[0][2])))
    a = math.atan2(-rot[1][2], rot[2][2])
    c = math.atan2(-rot[0][1], rot[0][0])
    g = (0.0, (grip_y - 8.0) / 16.0 * scale, 0.0)
    moved = [rot[i][1] * g[1] for i in range(3)]
    t = [(grip_at[i] - HAND[i] - moved[i]) * 16 for i in range(3)]
    return ([round(math.degrees(v), 2) for v in (a, b, c)], [round(v, 3) for v in t], [scale] * 3)


def gui_fit(points, slot=15.4, most=1.5):
    """Scale and move the weapon so it fills the slot (turned 45 degrees) without spilling out.
    points: (x, y) model units of its outline seen from the front."""
    pts = [(x - 8, y - 8) for x, y in points]
    gx = [(x + y) * math.sqrt(0.5) for x, y in pts]
    gy = [(y - x) * math.sqrt(0.5) for x, y in pts]
    s = min(most, slot / (max(gx) - min(gx)), slot / (max(gy) - min(gy)))
    cx, cy = (max(gx) + min(gx)) / 2, (max(gy) + min(gy)) / 2
    return round(s, 3), [round(-cx * s, 3), round(-cy * s, 3), 0]


def display(grip_y, points, hand_scale=1.3, hold='sword'):
    gs, gt = gui_fit(points)
    r3, t3 = _held(THIRD_PERSON, grip_y, hand_scale)
    r1, t1, s1 = _first_person(grip_y, hold)
    s3 = [hand_scale] * 3
    return {
        'thirdperson_righthand': {'rotation': r3, 'translation': t3, 'scale': s3},
        'thirdperson_lefthand': {'rotation': [r3[0], -r3[1], -r3[2]], 'translation': t3, 'scale': s3},
        'firstperson_righthand': {'rotation': r1, 'translation': t1, 'scale': s1},
        'firstperson_lefthand': {'rotation': [r1[0], -r1[1], -r1[2]], 'translation': t1, 'scale': s1},
        'gui': {'rotation': [0, 0, -45], 'translation': gt, 'scale': [gs, gs, gs]},
        'ground': {'rotation': [0, 0, -45], 'translation': [0, 2, 0], 'scale': [0.6, 0.6, 0.6]},
        'head': {'rotation': [0, 180, -45], 'translation': [0, 13, 7], 'scale': [1, 1, 1]},
        'fixed': {'rotation': [0, 180, -45], 'translation': [0, 0, 0], 'scale': [1.3, 1.3, 1.3]},
    }


# the showcase view: the weapon upright, turned to show its depth
SHOWCASE = (12, -30, 0)
