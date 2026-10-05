"""Reading and writing PNG files without image libraries (RGBA, 8 bits per channel)."""
import struct
import zlib


def encode(rows):
    """rows: lists of (r, g, b, a) tuples (or None for transparent). Returns PNG bytes."""
    h, w = len(rows), len(rows[0])
    raw = bytearray()
    for row in rows:
        raw += b'\x00'
        for p in row:
            raw += b'\x00\x00\x00\x00' if p is None else bytes(p if len(p) == 4 else tuple(p) + (255,))

    def chunk(kind, data):
        body = kind + data
        return struct.pack('>I', len(data)) + body + struct.pack('>I', zlib.crc32(body) & 0xFFFFFFFF)
    return (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 6, 0, 0, 0))
            + chunk(b'IDAT', zlib.compress(bytes(raw), 9)) + chunk(b'IEND', b''))


def save(path, rows):
    with open(path, 'wb') as f:
        f.write(encode(rows))


def decode(data):
    """PNG bytes (8-bit RGBA or RGB, any filter) -> rows of (r, g, b, a)."""
    assert data[:8] == b'\x89PNG\r\n\x1a\n'
    pos, idat = 8, b''
    while pos < len(data):
        n, = struct.unpack('>I', data[pos:pos + 4])
        kind, body = data[pos + 4:pos + 8], data[pos + 8:pos + 8 + n]
        if kind == b'IHDR':
            w, h, depth, ctype = struct.unpack('>IIBB', body[:10])
            assert depth == 8 and ctype in (2, 6)
        elif kind == b'IDAT':
            idat += body
        pos += 12 + n
    bpp = 4 if ctype == 6 else 3
    raw = zlib.decompress(idat)
    stride = w * bpp
    prev = bytearray(stride)
    rows = []
    i = 0
    for _ in range(h):
        f = raw[i]
        line = bytearray(raw[i + 1:i + 1 + stride])
        i += 1 + stride
        for x in range(stride):
            a = line[x - bpp] if x >= bpp else 0
            b = prev[x]
            c = prev[x - bpp] if x >= bpp else 0
            if f == 1:
                line[x] = (line[x] + a) & 255
            elif f == 2:
                line[x] = (line[x] + b) & 255
            elif f == 3:
                line[x] = (line[x] + (a + b) // 2) & 255
            elif f == 4:
                pa, pb, pc = abs(b - c), abs(a - c), abs(a + b - 2 * c)
                line[x] = (line[x] + (a if pa <= pb and pa <= pc else b if pb <= pc else c)) & 255
        prev = line
        rows.append([tuple(line[x * bpp:x * bpp + 3]) + ((line[x * bpp + 3],) if bpp == 4 else (255,))
                     for x in range(w)])
    return rows
