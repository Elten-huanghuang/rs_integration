"""万界归墟盘：以用户选定底图为基础，仅修改青玉龙首浮雕。"""
from pathlib import Path
import struct
import zlib
from PIL import Image, ImageOps

ROOT = Path(__file__).resolve().parents[1]
TARGET = ROOT / "src/main/resources/assets/rs_integration/textures/item/unified_storage_disk.png"
OUT = ROOT / "build/art/guixu_disk"
SIZE = 64
PALETTE = {
    "edge": (17, 27, 30, 255),
    "shadow": (26, 41, 43, 255),
    "jade_dark": (32, 61, 59, 255),
    "jade": (45, 84, 72, 255),
    "jade_light": (67, 112, 88, 255),
    "jade_highlight": (98, 139, 105, 255),
    "gold_dark": (91, 69, 41, 255),
    "gold": (151, 113, 58, 255),
    "gold_light": (202, 160, 88, 255),
    "gold_highlight": (231, 200, 133, 255),
    "abyss": (11, 27, 32, 255),
    "water_dark": (18, 56, 63, 255),
    "water": (30, 101, 105, 255),
    "water_light": (68, 156, 145, 255),
    "water_highlight": (130, 203, 170, 255),
    "indigo_dark": (26, 27, 42, 255),
    "indigo": (42, 43, 63, 255),
    "indigo_light": (60, 62, 80, 255),
    "dragon": (216, 232, 197, 255),
}


def png(path, pixels):
    height, width = len(pixels), len(pixels[0])
    raw = b"".join(b"\0" + bytes(channel for pixel in row for channel in pixel) for row in pixels)
    def chunk(kind, payload):
        return struct.pack(">I", len(payload)) + kind + payload + struct.pack(">I", zlib.crc32(kind + payload))
    encoded = b"\x89PNG\r\n\x1a\n"
    encoded += chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
    encoded += chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(encoded)


def edit_selected_base():
    """以用户选定的旧版为底，只修改龙首；盘身和所有侧纹逐像素保留。"""
    base = Image.open(ROOT / "art/guixu_disk_base_reference.png").convert("RGB")
    assert base.size == (384, 384)
    base = base.resize((64, 64), Image.Resampling.NEAREST)
    backdrop = {(43, 45, 47), (36, 38, 40)}
    original = [[(*base.getpixel((x, y)), 255) if base.getpixel((x, y)) not in backdrop
                 else (0, 0, 0, 0) for x in range(64)] for y in range(64)]
    pixels = [row.copy() for row in original]
    reference = Image.open(ROOT / "art/guixu_dragon_reference.png").convert("RGB")
    mask = ImageOps.grayscale(reference).point(lambda p: 255 if p < 110 else 0)
    cropped = mask.crop(mask.getbbox())
    old = cropped.resize((32, 36), Image.Resampling.NEAREST)
    new = cropped.resize((20, 22), Image.Resampling.NEAREST)

    def face(x, y):
        dx, dy = x / 2 - 15.5, y / 2 - 12.5
        return (dx - dy) / 24, (9 * dx + 15 * dy) / 264

    def old_dragon(u, v):
        gx, gy = int((u + .29) / .58 * 32), int((v + .32) / .64 * 36)
        return 0 <= gx < 32 and 0 <= gy < 36 and old.getpixel((gx, gy)) > 0

    def new_dragon(x, y):
        gx, gy = x - 21, y - 13
        return 0 <= gx < 20 and 0 <= gy < 22 and new.getpixel((gx, gy)) > 0

    # 龙首及一像素浮雕阴影全部落在墨绿盘面内，和内侧边缘留出间距。
    dragon_pixels = [(x, y) for y in range(64) for x in range(64)
                     if new_dragon(x, y) or new_dragon(x - 1, y - 1)]
    assert dragon_pixels
    assert all(max(map(abs, face(x, y))) < .345 for x, y in dragon_pixels)

    for y in range(64):
        for x in range(64):
            u, v = face(x, y)
            edge = max(abs(u), abs(v))
            if edge < .365 and (old_dragon(u, v) or old_dragon(u - .018, v - .025)):
                pixels[y][x] = PALETTE["shadow"]
            if new_dragon(x - 1, y - 1):
                pixels[y][x] = PALETTE["abyss"]
            if new_dragon(x, y):
                # u + v = 0 是盘面顶角到底角的真实对角线（5x + y = 180）。
                # 左侧受光、右侧背光，两个色块沿同一条透视斜线划分。
                pixels[y][x] = PALETTE["water_highlight" if u + v < 0 else "water"]

    # 龙眼横向两格，左端向上延伸一格，形成清晰的折角。
    for x, y in ((35, 22), (35, 23), (36, 23)):
        pixels[y][x] = PALETTE["abyss"]

    changed = [(x, y) for y in range(64) for x in range(64) if pixels[y][x] != original[y][x]]
    assert changed
    # 验证盘体、倒角和侧边完全未动，修改严格局限于中央龙首。
    assert all(max(map(abs, face(x, y))) < .365 for x, y in changed)
    png(OUT / "selected_base_texture.png", original)
    print(f"仅修改中央龙首：{len(changed)} 像素；盘身与侧边一致")
    return pixels


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    previous = OUT / "previous_texture.png"
    if TARGET.exists() and not previous.exists():
        previous.write_bytes(TARGET.read_bytes())
    pixels = edit_selected_base()
    png(TARGET, pixels)
    # 最近邻放大，方便检查像素边缘；棋盘底仅存在于预览。
    scale = 6
    preview = []
    for y in range(SIZE * scale):
        row = []
        for x in range(SIZE * scale):
            pixel = pixels[y // scale][x // scale]
            gray = 36 if (x // 48 + y // 48) % 2 else 43
            row.append(pixel if pixel[3] else (gray, gray + 2, gray + 4, 255))
        preview.append(row)
    png(OUT / "guixu_disk_preview.png", preview)
    assert len({pixel for row in pixels for pixel in row if pixel[3]}) <= len(PALETTE)
    assert all(pixel[3] in (0, 255) for row in pixels for pixel in row)
    print(TARGET)
    print(OUT / "guixu_disk_preview.png")


if __name__ == "__main__":
    main()
