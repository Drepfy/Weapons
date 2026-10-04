"""Draws the 16x16 Heart texture (no image libraries needed) and prints it as text."""
import struct
import sys
import zlib

SIZE = 16


SHAPE = [
    "................",
    "................",
    "...OOO....OOO...",
    "..ORRRO..ORRRO..",
    ".ORRRRROORRRRRO.",
    ".ORRRRRRRRRRRRO.",
    ".ORRRRRRRRRRRRO.",
    ".ORRRRRRRRRRRRO.",
    "..ORRRRRRRRRRO..",
    "...ORRRRRRRRO...",
    "....ORRRRRRO....",
    ".....ORRRRO.....",
    "......ORRO......",
    ".......OO.......",
    "................",
    "................",
]


def build():
    grid = [list(row) for row in SHAPE]
    for y in range(SIZE):
        for x in range(SIZE):
            if grid[y][x] == 'R' and x + y >= 19:
                grid[y][x] = 'D'  # shade towards the bottom right
    # Shine on the upper left lobe.
    for x, y, c in ((3, 4, 'W'), (4, 4, 'H'), (3, 5, 'H'), (4, 3, 'H')):
        grid[y][x] = c
    return grid


COLORS = {
    '.': (0, 0, 0, 0),
    'O': (74, 6, 12, 255),
    'R': (222, 32, 44, 255),
    'D': (160, 14, 26, 255),
    'H': (255, 120, 120, 255),
    'W': (255, 228, 228, 255),
}


def png(grid):
    raw = b''.join(b'\x00' + b''.join(bytes(COLORS[c]) for c in row) for row in grid)

    def chunk(kind, data):
        body = kind + data
        return struct.pack('>I', len(data)) + body + struct.pack('>I', zlib.crc32(body) & 0xFFFFFFFF)

    header = struct.pack('>IIBBBBB', SIZE, SIZE, 8, 6, 0, 0, 0)
    return b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', header) + chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b'')


if __name__ == '__main__':
    grid = build()
    for row in grid:
        print(''.join(row))
    with open(sys.argv[1], 'wb') as out:
        out.write(png(grid))
