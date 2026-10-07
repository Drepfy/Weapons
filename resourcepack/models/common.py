"""What all the weapon models share: how they are held, shown in the inventory, dropped and framed.

The models stand upright (16 units tall). In the inventory they are turned 45 degrees and scaled
to fill the slot from corner to corner. In the hand they are turned like vanilla's sword and
moved so the middle of their grip sits exactly where a vanilla sword's handle does, a little
bigger than a vanilla sword.
"""
import math

# a vanilla sword's handle, relative to the model's centre (its sprite is diagonal)
VANILLA_GRIP = (-5.0, -5.0)
THIRD_PERSON = {'rotation_z': 55.0, 'translation': (0.0, 4.0, 0.5), 'scale': 0.85}
FIRST_PERSON = {'rotation_z': 25.0, 'translation': (1.13, 3.2, 1.13), 'scale': 0.68}


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


def gui_fit(points, slot=15.4, most=1.5):
    """Scale and move the weapon so it fills the slot (turned 45 degrees) without spilling out.
    points: (x, y) model units of its outline seen from the front."""
    pts = [(x - 8, y - 8) for x, y in points]
    gx = [(x + y) * math.sqrt(0.5) for x, y in pts]
    gy = [(y - x) * math.sqrt(0.5) for x, y in pts]
    s = min(most, slot / (max(gx) - min(gx)), slot / (max(gy) - min(gy)))
    cx, cy = (max(gx) + min(gx)) / 2, (max(gy) + min(gy)) / 2
    return round(s, 3), [round(-cx * s, 3), round(-cy * s, 3), 0]


def display(grip_y, points, hand_scale=1.3, first_scale=1.0):
    gs, gt = gui_fit(points)
    r3, t3 = _held(THIRD_PERSON, grip_y, hand_scale)
    r1, t1 = _held(FIRST_PERSON, grip_y, first_scale)
    s3, s1 = [hand_scale] * 3, [first_scale] * 3
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
