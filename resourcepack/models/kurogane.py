"""Kurogane: a crimson-steel katana. A long curved blade of polished steel with a glowing crimson
temper line that shimmers along it, a dark groove and a blackened spine; a gold collar, a black
iron guard capped in gold, a handle bound in crimson cord over pale ray skin, and a black and
gold pommel.
"""
import math

from pixel import Art, column, noise, ramp

STEEL = ramp('#161a23', '#2e3542', '#4b5466', '#6f7a8e', '#98a3b5', '#c3ccd8', '#e6ecf3', '#ffffff')
SPINE = ramp('#0e0f13', '#1d1f26', '#30333d', '#474b58', '#636878')
GROOVE = ramp('#230209', '#45060f', '#6c0b1a', '#981328', '#c41d36')
CRIMSON_GLOW = ramp('#5e0715', '#9c0f25', '#d01a35', '#ff3d58', '#ff8c9c', '#ffd2d8')
GOLD = ramp('#3b2204', '#6b410c', '#9e6719', '#d29a33', '#f3cb63', '#fff0b0')
IRON = ramp('#0a0a0e', '#17181e', '#272932', '#3b3e4a', '#555968', '#747a8b')
CORD = ramp('#2b030a', '#560815', '#8a0f22', '#c01a33', '#e9425a', '#ff8796')
SKIN = ramp('#5b5560', '#8c8592', '#b8b1bd', '#dcd6df', '#f4f0f5')

Y_BLADE, Y_KISSAKI, Y_TIP = 6.1, 14.4, 15.85


def bow(y):
    """How far the blade curves towards its spine (right) at height y."""
    return 0.42 * max(0.0, (y - Y_BLADE) / (Y_TIP - Y_BLADE)) ** 2


def edge(y):
    x = 8 - 0.68 + bow(y)
    if y > Y_KISSAKI:
        q = (y - Y_KISSAKI) / (Y_TIP - Y_KISSAKI)
        x += (spine(y) - x) * q ** 1.5
    return x


def spine(y):
    x = 8 + 0.68 + bow(y)
    if y > Y_KISSAKI:
        x -= 0.25 * ((y - Y_KISSAKI) / (Y_TIP - Y_KISSAKI)) ** 2.2
    return x


def across(x, y):
    """0 at the cutting edge, 1 at the spine."""
    return (x - edge(y)) / max(spine(y) - edge(y), 1e-6)


def in_blade(x, y):
    return Y_BLADE <= y <= Y_TIP and edge(y) <= x <= spine(y)


def hamon(y):
    return 0.27 + 0.05 * math.sin(y * 2.6)


def build():
    art = Art()

    def blade_tone(x, y):
        u = across(x, y)
        sheen = 0.08 if (y * 0.8 + u * 1.5) % 3.2 < 0.5 else 0.0          # soft reflections
        if y > Y_KISSAKI:
            return 1.0 if u < 0.2 or abs(y - Y_KISSAKI) < 0.07 else 0.78 + sheen
        if u < 0.12:
            return 1.0                                                     # the cutting edge
        if u < hamon(y):
            return 0.84 + sheen                                            # polished, hardened steel
        if u < 0.6:
            return 0.52 + sheen + 0.1 * (y - Y_BLADE) / (Y_TIP - Y_BLADE)
        if u < 0.66:
            return 0.86                                                    # the ridge line
        return 0.4

    art.add('blade', in_blade, lambda x, y: 0.5, STEEL, depth=2, tone=blade_tone)
    art.add('spine', lambda x, y: in_blade(x, y) and across(x, y) > 0.82 and y < Y_KISSAKI,
            lambda x, y: 0.5, SPINE, depth=3, outline=False, shadow=False, tone=lambda x, y: 0.55)
    art.add('groove', lambda x, y: in_blade(x, y) and 0.66 <= across(x, y) <= 0.82 and Y_BLADE + 0.6 < y < 12.9,
            lambda x, y: 0.5, GROOVE, depth=2, outline=False, shadow=False,
            tone=lambda x, y: 0.25 if across(x, y) < 0.74 else 0.6)
    art.add('temper line', lambda x, y: in_blade(x, y) and Y_BLADE + 0.2 < y < Y_KISSAKI
            and column(x) == column(edge(y) + hamon(y) * (spine(y) - edge(y))),
            lambda x, y: 0.5, CRIMSON_GLOW, depth=2, outline=False, shadow=False,
            glow=lambda x, y, t: 0.5 + 0.5 * max(0.0, math.sin(2 * math.pi * (t - y * 0.11))) ** 2)
    # gold collar (habaki)
    art.add('collar', lambda x, y: 7.33 <= x <= 8.67 + bow(y) and 5.55 <= y <= 6.25,
            lambda x, y: math.sqrt(max(0.0, 1 - ((x - 8) / 0.7) ** 2)), GOLD, depth=5, relief=2.0, shine=0.8)
    # guard (tsuba): black iron, gold caps at the ends
    art.add('guard', lambda x, y: 6.35 <= x <= 9.65 and 4.95 <= y <= 5.6 and not (abs(x - 8) > 1.5 and abs(y - 5.27) > 0.22),
            lambda x, y: math.sqrt(max(0.0, 1 - ((y - 5.27) / 0.36) ** 2)), IRON, depth=7, relief=2.0, shine=0.4)
    art.add('guard caps', lambda x, y: (6.35 <= x <= 6.8 or 9.2 <= x <= 9.65) and 4.95 <= y <= 5.6
            and not (abs(x - 8) > 1.5 and abs(y - 5.27) > 0.22),
            lambda x, y: math.sqrt(max(0.0, 1 - ((y - 5.27) / 0.36) ** 2)), GOLD, depth=7, relief=2.0, shine=0.8,
            shadow=False)
    # handle: pale ray skin under a crimson cord wound in diamonds
    art.add('handle', lambda x, y: 7.44 <= x <= 8.56 and 0.95 <= y <= 4.95,
            lambda x, y: math.sqrt(max(0.0, 1 - ((x - 8) / 0.6) ** 2)) * 0.6, SKIN, depth=4, relief=2.0)

    def cord(x, y):
        a = (y * 1.05 + (x - 8) * 1.05) % 1.0
        b = (y * 1.05 - (x - 8) * 1.05) % 1.0
        return min(a, b)

    art.add('cord', lambda x, y: 7.44 <= x <= 8.56 and 1.0 <= y <= 4.85 and cord(x, y) < 0.26,
            lambda x, y: math.sqrt(max(0.0, 1 - ((x - 8) / 0.6) ** 2)), CORD, depth=5, relief=2.2, outline=True)
    art.add('collar 2', lambda x, y: 7.38 <= x <= 8.62 and 4.7 <= y <= 4.98,
            lambda x, y: math.sqrt(max(0.0, 1 - ((x - 8) / 0.66) ** 2)), GOLD, depth=5, relief=2.0, shine=0.8)
    # pommel (kashira)
    art.add('pommel', lambda x, y: 7.36 <= x <= 8.64 and 0.3 <= y <= 1.0 and not (abs(x - 8) > 0.5 and y < 0.42),
            lambda x, y: math.sqrt(max(0.0, 1 - ((x - 8) / 0.68) ** 2)), IRON, depth=6, relief=2.0, shine=0.5)
    art.add('pommel band', lambda x, y: 7.36 <= x <= 8.64 and 0.88 <= y <= 1.0,
            lambda x, y: math.sqrt(max(0.0, 1 - ((x - 8) / 0.68) ** 2)), GOLD, depth=6, relief=2.0, shine=0.8,
            shadow=False)
    return art, 3.0          # the art, and the height of the middle of the grip
