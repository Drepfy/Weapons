"""Pixel-texture designs for the five weapons.

Each weapon is drawn on a character grid. Every cell has a colour key, a
thickness (in texture pixels) used when the model is extruded to 3D, and an
optional glow flag. Outlines are added automatically around every shape.
"""
import math

# key: (hex colour, default thickness, glows)
PALETTE = {
    # outline
    "O": ("#141218", 0, False),
    # bright steel
    "W": ("#f4f7fb", 2, False), "L": ("#cbd3de", 2, False), "M": ("#949fae", 2, False),
    "D": ("#636b7a", 2, False), "K": ("#3e434e", 2, False),
    # dark iron
    "h": ("#77717f", 4, False), "e": ("#4d4857", 4, False), "f": ("#34303c", 4, False),
    "g": ("#211e27", 4, False),
    # crimson
    "R": ("#ff4d5a", 2, True), "r": ("#d01f30", 2, False), "s": ("#911224", 2, False),
    "t": ("#560a16", 2, False), "P": ("#ffc2c9", 2, False),
    # leather
    "n": ("#7b4b34", 3, False), "m": ("#573323", 3, False), "q": ("#3a2016", 3, False),
    # gold
    "Y": ("#ffe38f", 3, False), "y": ("#e2a93a", 3, False), "u": ("#98671c", 3, False),
    # bone / ivory
    "B": ("#f3eee4", 2, False), "b": ("#cfc5b3", 2, False), "c": ("#9d927f", 2, False),
    # void purple
    "V": ("#f4c4ff", 2, True), "v": ("#c24dff", 2, True), "w": ("#8a24d8", 2, False),
    "x": ("#561489", 2, False), "z": ("#2d0b4b", 2, False),
    # cyan
    "Q": ("#8af5ff", 2, True), "j": ("#26b5d6", 2, False),
    # ice / navy
    "A": ("#e8fcff", 2, False), "I": ("#95e4ff", 2, False), "i": ("#3aa6ee", 2, False),
    "l": ("#1f5cc0", 2, False), "E": ("#c6f8ff", 2, True),
    "X": ("#5068a6", 3, False), "N": ("#273766", 3, False), "k": ("#172243", 3, False),
    "o": ("#0d1430", 3, False),
    # dark wood
    "d": ("#6d503b", 3, False), "a": ("#4c3628", 3, False), "Z": ("#2f2119", 3, False),
}


class Canvas:
    def __init__(self, w, h):
        self.w, self.h = w, h
        self.px = {}  # (x, y) -> (key, depth)

    def set(self, x, y, key, depth=None):
        if 0 <= x < self.w and 0 <= y < self.h:
            self.px[(x, y)] = (key, PALETTE[key][1] if depth is None else depth)

    def row(self, y, x0, keys, depth=None):
        for i, k in enumerate(keys):
            if k != ".":
                self.set(x0 + i, y, k, depth)

    def outline(self):
        add = {}
        for (x, y) in list(self.px):
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                n = (x + dx, y + dy)
                if n not in self.px and 0 <= n[0] < self.w and 0 <= n[1] < self.h:
                    best = max(self.px[m][1] for m in
                               [(n[0] + a, n[1] + b) for a, b in ((1, 0), (-1, 0), (0, 1), (0, -1))]
                               if m in self.px)
                    add[n] = ("O", best)
        self.px.update(add)
        return self


# ---------------------------------------------------------------- 1. Crimson Warden
def crimson_warden():
    c = Canvas(25, 64)
    cx = 12
    # tip
    c.set(cx, 1, "W", 1)
    for y in (2, 3):
        c.row(y, cx - 1, "WLD", 1)
    for y in (4, 5):
        c.row(y, cx - 2, "WLMDK")
        c.set(cx - 2, y, "W", 1); c.set(cx + 2, y, "K", 1)
    # blade body
    for y in range(6, 36):
        fuller = "g"
        if y % 6 == 2:
            fuller = "r"
        if y in (11, 20, 29):
            fuller = "R"
        if 7 <= y <= 9:
            fuller = "e"
        cols = ["W", "L", "M", fuller, "M", "D", "K"]
        if 14 <= y <= 16 or y == 24:  # specular sheen
            cols = ["W", "W", "L", fuller, "L", "M", "D"]
        c.row(y, cx - 3, cols)
        c.set(cx - 3, y, cols[0], 1); c.set(cx + 3, y, cols[-1], 1)
    # ricasso flare with hooks
    c.set(cx - 5, 35, "h"); c.set(cx + 5, 35, "f")
    c.row(36, cx - 5, ["h", "e", "W", "L", "M", "s", "M", "D", "K", "f", "f"])
    c.row(37, cx - 5, ["h", "e", "L", "M", "D", "t", "D", "K", "K", "f", "g"])
    # guard: down-swept wings
    c.row(38, cx - 9, "hhhhhhheeeeeeffffff")
    c.row(39, cx - 11, "hhesssssseeeeeffssssffg")
    c.row(40, cx - 11, "hre")
    c.row(40, cx - 3, "herRsef")
    c.row(40, cx + 9, "fsg")
    c.row(41, cx - 11, "rr")
    c.row(41, cx - 2, "efsgf")
    c.row(41, cx + 10, "st")
    c.set(cx - 11, 42, "s"); c.set(cx + 11, 42, "t")
    c.row(42, cx - 1, "fsg")
    for x in range(cx - 1, cx + 2):
        c.set(x, 39, "eer"[x - cx + 1] if x != cx else "r", 5)
    c.set(cx, 40, "R", 5); c.set(cx - 1, 40, "r", 5); c.set(cx + 1, 40, "s", 5)
    c.set(cx, 41, "s", 5)
    # grip
    for y in range(43, 54):
        pat = [["n", "n", "m"], ["n", "m", "q"], ["m", "q", "q"]][y % 3]
        if y == 48:
            pat = ["L", "M", "D"]
        c.row(y, cx - 1, pat)
    # pommel
    c.row(54, cx - 2, "hheef")
    c.row(55, cx - 3, "hherfff")
    c.row(56, cx - 3, "herRsfg")
    c.row(57, cx - 3, "eefsffg")
    c.row(58, cx - 2, "effgg")
    c.row(59, cx - 1, "fgg")
    c.set(cx, 60, "g")
    for (x, y) in [(cx, 55), (cx - 1, 56), (cx, 56), (cx + 1, 56), (cx, 57)]:
        c.set(x, y, c.px[(x, y)][0], 5)
    return c.outline()


# ---------------------------------------------------------------- 2. Bloodfang
def bloodfang():
    c = Canvas(30, 64)
    gx = 11  # grip centre
    top, base = 1, 40
    for y in range(top, base + 1):
        t = (y - top) / (base - top)          # 0 at tip, 1 at base
        spine = round(gx + 1 + 9 * (1 - t) ** 2)
        w = min(7, 1 + (y - top) // 2)
        if y > base - 3:
            w = 6
        full = ["P", "r", "r", "s", "g?", "B", "b"]
        layers = full if w >= 7 else (["P"] + full[-(w - 1):] if w > 1 else ["P"])
        for i, k in enumerate(layers):
            x = spine - len(layers) + 1 + i
            if k == "g?":
                k = "R" if (y % 5 in (0, 1) and 8 < y < base - 4) else "t"
            d = 1 if i == 0 else 2
            if k in "Bb":
                d = 3
            c.set(x, y, k, d)
        if y % 7 == 3 and w == 7:  # glint along edge
            c.set(spine - 5, y, "P", 2)
    # guard: crescent of bone with crimson core and gold rim
    c.row(41, gx - 4, "yYYyyyyyu")
    c.row(42, gx - 5, "ybBBrRrbbcu")
    c.row(43, gx - 4, "ucbsRsbcu")
    c.row(44, gx - 2, "uyyyu")
    for x in range(gx - 1, gx + 2):
        for y in (42, 43):
            if c.px.get((x, y), ("",))[0] in "rRs":
                c.set(x, y, c.px[(x, y)][0], 4)
    for y in range(41, 45):
        for x in range(gx - 5, gx + 6):
            if (x, y) in c.px and c.px[(x, y)][0] in "yYuBbc":
                c.set(x, y, c.px[(x, y)][0], 4)
    # grip with diamond wrap
    for y in range(45, 57):
        pat = [["B", "s", "b"], ["s", "R", "t"], ["b", "s", "c"], ["t", "q", "t"]][y % 4]
        if pat[1] == "R":
            pat = ["s", "r", "t"]
        c.row(y, gx - 1, pat, 3)
    # pommel
    c.row(57, gx - 2, "YyyyU".replace("U", "u"), 4)
    c.row(58, gx - 2, "ysRtu", 4)
    c.row(59, gx - 1, "yuu", 4)
    c.set(gx, 60, "u", 3)
    return c.outline()


# ---------------------------------------------------------------- 3. Voidrender
def voidrender():
    c = Canvas(27, 64)
    cx = 13
    # tip
    c.set(cx, 0, "Q", 1)
    c.row(1, cx - 1, "jhf", 1)
    c.row(2, cx - 1, "jef", 1)
    for y in range(3, 7):
        c.row(y, cx - 2, "jhefg")
        c.set(cx - 2, y, "j", 1); c.set(cx + 2, y, "g", 1)
    c.row(6, cx - 2, "jhxfg")
    # blade: dark frame, open void channel, stepped barbs
    for y in range(7, 37):
        k = (y * 7) % 5
        core = ["x", "v", "w"]
        if y % 4 == 1:
            core = ["w", "V", "x"]
        if y % 9 == 4:
            core = ["v", "Q", "w"]
        if y in (7, 36):
            core = ["z", "x", "z"]
        cols = ["j", "h", "e", "z"] + core + ["z", "f", "g", "g"]
        c.row(y, cx - 5, cols)
        c.set(cx - 5, y, "j", 1); c.set(cx + 5, y, "g", 1)
        if y % 6 == 3 and 9 < y < 34:  # upward barbs
            c.set(cx - 6, y, "j", 1); c.set(cx + 6, y, "f", 1)
            c.set(cx - 6, y + 1, "h", 1); c.set(cx + 6, y + 1, "g", 1)
            c.set(cx - 7, y - 1, "j", 1); c.set(cx + 7, y - 1, "f", 1)
    # guard: horns sweeping upward
    for i, y in enumerate(range(31, 38)):
        c.set(cx - 11 + i // 3, y, "j" if i < 2 else "h")
        c.set(cx + 11 - i // 3, y, "f" if i < 2 else "g")
        c.set(cx - 10 + i // 3, y, "h" if i > 1 else "e")
        c.set(cx + 10 - i // 3, y, "f")
    c.row(38, cx - 10, "hhheeeeeexxxffffffgg"[:20] + "g")
    c.row(39, cx - 9, "heexwvvwxxwvvwxfffg"[:19])
    c.row(40, cx - 7, "eexzzVvzzxxff"[:15])
    c.row(40, cx - 7, "eefxzzzzzzxfffg")
    c.row(41, cx - 4, "efxzzzzxff")
    c.row(41, cx - 4, "efxxzzzxfg")
    # small set core crystal
    for (x, y, k) in [(cx, 38, "v"), (cx - 1, 39, "v"), (cx, 39, "V"), (cx + 1, 39, "w"),
                      (cx - 1, 40, "w"), (cx, 40, "v"), (cx + 1, 40, "x"), (cx, 41, "x")]:
        c.set(x, y, k, 5)
    c.set(cx - 2, 39, "x", 4); c.set(cx + 2, 39, "x", 4)
    # grip: black wrap with violet binding
    for y in range(42, 54):
        pat = [["e", "g", "g"], ["x", "w", "z"], ["g", "e", "g"], ["z", "x", "w"]][y % 4]
        c.row(y, cx - 1, pat, 3)
    # pommel: split crescent with cyan point
    c.row(54, cx - 2, "heefg", 4)
    c.row(55, cx - 3, "he.Q.fg", 4)
    c.set(cx, 55, "x", 4)
    c.row(56, cx - 3, "e.....g", 4)
    c.set(cx, 56, "j", 3)
    c.set(cx, 57, "Q", 3)
    return c.outline()


# ---------------------------------------------------------------- 4. Doomcleaver
def doomcleaver():
    c = Canvas(34, 64)
    cx = 22
    # top spike
    c.set(cx, 0, "L", 2)
    c.row(1, cx - 1, "WMD", 2)
    c.row(2, cx - 1, "LMK", 3)
    # haft
    for y in range(3, 60):
        pat = [["d", "a", "Z"], ["d", "d", "a"], ["a", "d", "Z"], ["d", "a", "a"], ["a", "a", "Z"]][(y * 3) % 5]
        c.row(y, cx - 1, pat, 3)
    for y in (33, 34, 58):
        c.row(y, cx - 2, "hhefg", 4)
    for y in range(44, 56):
        c.row(y, cx - 1, [["n", "n", "m"], ["m", "n", "q"], ["n", "m", "q"]][y % 3], 3)
    c.row(60, cx - 2, "heefg", 4)
    c.row(61, cx - 1, "efg", 4)
    c.set(cx, 62, "f", 3)
    # head: bearded crescent
    y0, y1 = 3, 34
    for y in range(y0, y1 + 1):
        t = (y - y0) / (y1 - y0)
        xe = cx - 9 - round(9 * math.sin(math.pi * t ** 0.9))
        if y < 11:
            xi = cx - 3 - round((11 - y) * 1.15)
        elif y > 22:
            xi = cx - 3 - round((y - 22) * 0.85)
        else:
            xi = cx - 3
        if xe > xi:
            continue
        for x in range(xe, xi + 1):
            dist = x - xe
            if dist == 0:
                k, d = "W", 1
            elif dist == 1:
                k, d = ("L" if y < 22 else "M"), 2
            elif dist == 2:
                k, d = "M", 3
            else:
                k, d = ("D" if (x + y) % 9 else "M"), 3
                if x >= cx - 7 and 9 <= y <= 24:
                    k, d = ("e" if x < cx - 4 else "f"), 4
            if 10 <= y <= 23 and x == cx - 8:
                k, d = "K", 3
            c.set(x, y, k, d)
    # sheen streak across head
    for i in range(6):
        c.set(cx - 15 + i, 10 + i, "L", 3)
    # gold accent rune + rivets on plate
    for (x, y) in [(cx - 6, 11), (cx - 6, 22), (cx - 5, 16)]:
        c.set(x, y, "y", 5)
    c.set(cx - 5, 15, "Y", 5); c.set(cx - 5, 17, "u", 5); c.set(cx - 6, 16, "y", 5); c.set(cx - 4, 16, "u", 5)
    # socket
    for y in range(8, 26):
        c.row(y, cx - 2, ["h", "e", "e", "f", "g"], 5)
    for y in (9, 16, 24):
        c.set(cx, y, "y", 6)
    # back spike
    for i, y in enumerate(range(13, 20)):
        L = 7 - abs(y - 16) * 2
        for x in range(cx + 3, cx + 3 + max(L, 1)):
            c.set(x, y, "M" if y < 16 else ("D" if y > 16 else "L"), 3)
        c.set(cx + 2 + max(L, 1), y, "K" if y >= 16 else "D", 2)
    c.set(cx + 10, 16, "W", 1)
    return c.outline()


# ---------------------------------------------------------------- 5. Stormpiercer
def stormpiercer():
    c = Canvas(23, 64)
    cx = 11
    widths = {0: 1, 1: 1, 2: 3, 3: 3, 4: 3, 5: 5, 6: 5, 7: 5, 8: 7, 9: 7, 10: 7, 11: 7, 12: 9,
              13: 9, 14: 9, 15: 7, 16: 7, 17: 5, 18: 5, 19: 3, 20: 3}
    bolt = {3: 0, 4: 0, 5: -1, 6: -1, 7: 0, 8: 1, 9: 1, 10: 0, 11: -1, 12: -2, 13: -1, 14: 0,
            15: 1, 16: 0, 17: 0}
    for y, w in widths.items():
        h = w // 2
        for x in range(cx - h, cx + h + 1):
            rel = x - cx
            if rel < 0:
                k = "A" if rel == -h else ("I" if (rel + y) % 4 else "A")
            elif rel == 0:
                k = "I"
            else:
                k = "l" if rel == h else ("i" if (rel + y) % 3 else "l")
            d = 1 if abs(rel) == h and w > 1 else 2
            c.set(x, y, k, d)
        # facet ridge
        if w >= 5:
            c.set(cx - h + 1, y, "I", 2)
        if y in bolt:
            c.set(cx + bolt[y], y, "E", 3)
    c.set(cx, 0, "A", 1)
    # navy socket + side prongs
    c.row(21, cx - 2, "XNNkk", 4)
    c.row(22, cx - 3, "yXNNkko", 4)
    c.row(23, cx - 4, "XyyYyyuk", 4)
    c.set(cx + 3, 23, "u", 4)
    c.row(23, cx - 4, "XyyYyyuuk"[:9], 4)
    for i, y in enumerate((19, 20, 21, 22)):
        c.set(cx - 5 + i // 2, y, "X" if i else "I", 3)
        c.set(cx + 5 - i // 2, y, "k" if i else "i", 3)
    c.set(cx - 4, 22, "N", 3); c.set(cx + 4, 22, "o", 3)
    c.row(24, cx - 2, "XNiNk", 4)
    c.row(25, cx - 2, "NNQNk", 4)
    c.set(cx, 25, "E", 4)
    c.row(26, cx - 2, "yyYyu", 4)
    # shaft
    for y in range(27, 59):
        c.row(y, cx - 1, ["X", "N", "k"], 3)
    # spiral lightning wrap near top
    for y, x in [(28, -1), (29, 0), (30, 1), (32, -1), (33, 0), (34, 1)]:
        c.set(cx + x, y, "i" if x else "E", 3)
    # grip wrap with cyan stitching
    for y in range(40, 52):
        c.row(y, cx - 1, [["o", "k", "o"], ["k", "i", "o"], ["o", "o", "k"]][y % 3], 3)
    c.row(39, cx - 2, "yyYyu", 4)
    c.row(52, cx - 2, "yyYyu", 4)
    # butt spike
    c.row(59, cx - 2, "XNNko", 4)
    c.row(60, cx - 1, "Nko", 3)
    c.row(61, cx - 1, "yYu", 3)
    c.set(cx, 62, "k", 2)
    return c.outline()


WEAPONS = [
    ("crimson_warden", crimson_warden),
    ("bloodfang", bloodfang),
    ("voidrender", voidrender),
    ("doomcleaver", doomcleaver),
    ("stormpiercer", stormpiercer),
]
