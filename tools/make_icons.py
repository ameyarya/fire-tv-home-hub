"""Draws Smart Home's web app icons (the TV banner's green with the white house) as PNGs, with no image library.

    python3 tools/make_icons.py      writes smarthome/app/src/main/assets/icon-{180,192,512}.png
"""
import os
import struct
import zlib

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "smarthome", "app", "src", "main", "assets")
TOP, BOTTOM = (0x3B, 0xA8, 0x77), (0x0F, 0x3D, 0x2C)   # the banner's gradient
# The house from ic_home (24x24): roof apex (12,3), eaves (2,12)-(22,12), walls x 5..19 down to 20, door x 10..14 from 14.
HOUSE = [(12, 3), (22, 12), (19, 12), (19, 20), (14, 20), (14, 14), (10, 14), (10, 20), (5, 20), (5, 12), (2, 12)]


def inside(poly, x, y):
    n, hit = len(poly), False
    for i in range(n):
        (x1, y1), (x2, y2) = poly[i], poly[(i + 1) % n]
        if (y1 > y) != (y2 > y) and x < x1 + (y - y1) * (x2 - x1) / (y2 - y1):
            hit = not hit
    return hit


def draw(size, rounded):
    ss = 3                                  # 3x3 samples per pixel, for smooth edges
    radius = size * 0.22 if rounded else 0
    pad = size * 0.2                        # the house fills the middle 60%
    scale = (size - 2 * pad) / 24
    rows = []
    for py in range(size):
        row = bytearray([0])
        for px in range(size):
            acc = [0, 0, 0, 0]
            for sy in range(ss):
                for sx in range(ss):
                    x, y = px + (sx + .5) / ss, py + (sy + .5) / ss
                    # outside the rounded corners: transparent
                    cx = min(max(x, radius), size - radius)
                    cy = min(max(y, radius), size - radius)
                    if radius and (x - cx) ** 2 + (y - cy) ** 2 > radius ** 2:
                        continue
                    t = (x + y) / (2 * size)
                    col = [round(TOP[i] + (BOTTOM[i] - TOP[i]) * t) for i in range(3)]
                    if inside(HOUSE, (x - pad) / scale, (y - pad) / scale):
                        col = [255, 255, 255]
                    for i in range(3):
                        acc[i] += col[i]
                    acc[3] += 255
            n = ss * ss
            a = acc[3] // n
            row += bytes([acc[0] // max(1, acc[3] // 255), acc[1] // max(1, acc[3] // 255),
                          acc[2] // max(1, acc[3] // 255), a] if a else [0, 0, 0, 0])
        rows.append(bytes(row))
    raw = zlib.compress(b"".join(rows), 9)

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xffffffff)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0)) \
        + chunk(b"IDAT", raw) + chunk(b"IEND", b"")


if __name__ == "__main__":
    # iOS rounds the corners itself and wants none of its own; Android and the browser tab get rounded ones.
    for size, rounded in ((180, False), (192, True), (512, True)):
        with open(os.path.join(OUT, f"icon-{size}.png"), "wb") as f:
            f.write(draw(size, rounded))
        print(f"icon-{size}.png")
