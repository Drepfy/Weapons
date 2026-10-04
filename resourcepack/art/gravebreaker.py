"""Gravebreaker: a bearded battle axe. Polished steel edge, forged-iron centre with a glowing
fissure and rivets, back spike and top spike; dark wood haft with iron bands and a leather grip.
"""
from pix import Sprite, inside, line_dist, bezier

PAL = {
    'k': '#110f0e', 'q': '#1c120c',                       # outlines: iron / wood
    'W': '#f4f7fa', 'E': '#d3dbe4', 'H': '#a9b3c0', 'S': '#7d8796',   # polished steel
    'I': '#5a5f69', 'M': '#3d4049', 'm': '#2a2c33',       # forged iron
    'O': '#ff8a2a', 'Y': '#ffd27a', 'o': '#a8380f',       # ember in the forge crack
    'D': '#6b4527', 'd': '#4a2f1b', 'e': '#8a5c35', 'f': '#33200f',   # dark wood
    'B': '#1d1714', 'b': '#2e2420', 'n': '#4a3c34',       # leather
}
OUT = {k: 'k' for k in 'WEHSIMmOYoBbn'}
OUT.update({k: 'q' for k in 'Dedf'})

# cutting edge: top horn -> belly -> beard tip (pixel coordinates)
EDGE = bezier((17.5, 1.5), (8.0, 3.0), (2.5, 9.5), (2.5, 16.5), 16)
# underside of the beard back to the socket
BEARD = bezier((2.5, 16.5), (7.0, 13.0), (12.0, 12.5), (17.5, 14.5), 10)
HEAD = EDGE + BEARD[1:] + [(22.0, 12.0), (23.5, 8.0), (21.5, 5.5), (19.5, 3.5)]
SPIKE = [(21.5, 13.0), (24.0, 10.5), (27.5, 15.5)]           # back spike, pointing down and out

def make():
    sp = Sprite(PAL, OUT)

    def fn(s, o, x, y):
        p = o - 2                                              # across the shaft (shaft centre: x + y = 33)
        cx, cy = x + 0.5, y + 0.5
        if 18 <= s <= 23.5 and abs(p) <= 2:                    # socket around the shaft
            if p == -2:
                return 'I'
            if s in (18, 23.5):
                return 'm' if p > 0 else 'M'
            return 'M' if p <= 0 else 'm'
        if inside(HEAD, cx, cy):                               # head
            d = line_dist(EDGE, cx, cy)
            if d < 1.3:
                return 'W' if y < 10 else 'E'
            if d < 2.4:
                return 'E' if y < 11 else 'H'
            if d < 2.9:
                return 'S'                                     # bevel line
            if d < 5.9:
                return 'H' if d < 4.4 else 'S'
            if d < 6.6:
                return 'm'                                     # seam between steel and forged iron
            return 'I' if (x + y) < 25 else 'M'
        if inside(SPIKE, cx, cy):                              # back spike
            return 'H' if (x - y) >= 12 else 'S'
        if 24 <= s <= 27 and abs(p) <= (1 if s <= 25 else 0):  # top spike
            return 'E' if p < 0 else ('H' if p == 0 else 'S')
        if 3.5 <= s <= 18 and abs(p) <= 1:                     # haft
            if s in (11, 11.5, 16.5):
                return {-1: 'H', 0: 'I', 1: 'M'}[p]            # iron bands
            if s <= 10:                                        # leather grip, spiral wrap
                if (int(2 * s) + p) % 4 == 0:
                    return 'n' if p < 1 else 'b'
                return 'b' if p == -1 else 'B'
            if p == 0 and int(2 * s) % 5 == 1:
                return 'f'                                     # wood grain
            return {-1: 'e', 0: 'D', 1: 'd'}[p]
        if 1.5 <= s <= 3 and abs(p) <= 1 and not (s == 1.5 and p != 0):   # pommel cap with a stud
            return 'E' if p < 0 else ('H' if p == 0 and s >= 2 else 'S' if p == 0 else 'M')
        return None

    sp.paint(fn)
    for (x, y), ch in {(21, 8): 'E', (22, 9): 'm', (19, 12): 'E', (20, 13): 'm',        # steel rivets
                       (12, 12): 'o', (13, 11): 'O', (14, 10): 'Y', (15, 10): 'O', (16, 9): 'O',
                       (17, 8): 'o'}.items():
        sp.set(x, y, ch)                                       # a glowing fissure in the forged iron
    sp.outline()
    return sp
