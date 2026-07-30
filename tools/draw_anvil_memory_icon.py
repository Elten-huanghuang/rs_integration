import struct
import zlib
from pathlib import Path


W = H = 16


def png(path, pixels, scale=1):
    width, height = W * scale, H * scale
    raw = bytearray()
    for y in range(height):
        raw.append(0)
        for x in range(width):
            raw.extend(pixels[y // scale][x // scale])
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)
    payload = b"\x89PNG\r\n\x1a\n"
    payload += chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
    payload += chunk(b"IDAT", zlib.compress(bytes(raw), 9))
    payload += chunk(b"IEND", b"")
    path.write_bytes(payload)


def icon(accent, bright, metal, shadow):
    clear = (0, 0, 0, 0)
    p = [[clear for _ in range(W)] for _ in range(H)]

    def dot(x, y, color):
        if 0 <= x < W and 0 <= y < H:
            p[y][x] = color

    # Compact clockwise memory arrow.
    for x, y in [
        (5, 2), (6, 1), (7, 1), (8, 1), (9, 2), (10, 2), (11, 3),
        (12, 4), (12, 5), (13, 5), (13, 6), (13, 7), (13, 8),
        (12, 9), (12, 10), (11, 11), (10, 12), (9, 12), (8, 13),
        (7, 13), (6, 13), (5, 12), (4, 12), (3, 11), (2, 10),
        (2, 9), (1, 9), (1, 8), (1, 7),
    ]:
        dot(x, y, accent)
    for x, y in [(10, 1), (11, 1), (12, 1), (12, 2), (12, 3), (13, 3), (14, 3), (12, 4)]:
        dot(x, y, bright)
    for x, y in [(1, 6), (2, 6), (3, 6), (3, 7), (3, 8), (2, 8), (1, 8), (0, 8)]:
        dot(x, y, accent)

    # Remembered material: a tiny ingot at the center.
    for y in range(6, 10):
        for x in range(5, 11):
            dot(x, y, metal)
    for x in range(6, 10):
        dot(x, 5, bright)
    dot(5, 6, bright)
    dot(10, 6, shadow)
    for x in range(6, 11):
        dot(x, 9, shadow)
    dot(5, 9, shadow)
    return p


def main():
    out = Path("src/main/resources/assets/rs_integration/textures/gui/anvil_memory")
    out.mkdir(parents=True, exist_ok=True)
    normal = icon((69, 184, 166, 255), (151, 246, 225, 255), (188, 200, 203, 255), (83, 98, 102, 255))
    hover = icon((82, 226, 202, 255), (220, 255, 245, 255), (231, 239, 240, 255), (91, 119, 116, 255))
    disabled = icon((78, 83, 86, 180), (117, 122, 125, 180), (104, 109, 111, 180), (55, 59, 61, 180))
    png(out / "memory_restock.png", normal)
    png(out / "memory_restock_hover.png", hover)
    png(out / "memory_restock_disabled.png", disabled)
    png(out / "memory_restock_preview.png", hover, 16)


if __name__ == "__main__":
    main()
