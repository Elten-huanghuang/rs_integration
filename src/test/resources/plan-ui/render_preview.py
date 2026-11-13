"""回放 PlanWorkspaceRenderTest 导出的生产绘制命令；保留真实坐标和裁剪。

文字由系统字体替代；物品使用原版平面贴图；JEI 内部由测试占位区标出。
运行前：gradlew test --tests '*PlanWorkspaceRenderTest'
"""
import argparse
import io
import json
from pathlib import Path
from zipfile import ZipFile

from PIL import Image, ImageDraw, ImageFont


def replay(source, destination, minecraft_jar, scale=2):
    data = json.loads(source.read_text(encoding='utf-8'))
    canvas = Image.new('RGBA', (data['width'] * scale, data['height'] * scale), '#0b110e')
    canvas_draw = ImageDraw.Draw(canvas)
    fonts = {}
    textures = {}
    with ZipFile(minecraft_jar) as archive:
        archive_paths = set(archive.namelist())
        for command in data['commands']:
            # 点阵和实心 GUI 矩形直接绘制，避免每个像素点都分配整张透明画布。
            if command['type'] == 'fill' and (command['color'] & 0xff000000) == 0xff000000:
                x1, y1 = [round(v * scale) for v in command['point']]
                x2, y2 = [round(v * scale) for v in command['right']]
                clip = command.get('clip', (0, 0, data['width'], data['height']))
                cx1, cy1, cx2, cy2 = [round(v * scale) for v in clip]
                x1, y1, x2, y2 = max(x1, cx1), max(y1, cy1), min(x2, cx2), min(y2, cy2)
                color = command['color'] & 0xffffffff
                if x2 > x1 and y2 > y1:
                    canvas_draw.rectangle((x1, y1, x2 - 1, y2 - 1),
                                          fill=((color >> 16) & 255, (color >> 8) & 255, color & 255, 255))
                continue
            overlay = Image.new('RGBA', canvas.size)
            draw = ImageDraw.Draw(overlay)
            x, y = [round(v * scale) for v in command['point']]
            kind = command['type']
            color = command.get('color', 0xffffffff) & 0xffffffff
            rgba = ((color >> 16) & 255, (color >> 8) & 255, color & 255, color >> 24)
            if kind == 'fill':
                x2, y2 = [round(v * scale) for v in command['right']]
                if x2 > x and y2 > y:
                    draw.rectangle((x, y, x2 - 1, y2 - 1), fill=rgba)
            elif kind == 'text':
                size = max(6, round(9 * scale * command['scale']))
                key = (size, command.get('bold', False))
                if key not in fonts:
                    face = 'msyhbd.ttc' if key[1] else 'msyh.ttc'
                    fonts[key] = ImageFont.truetype('C:/Windows/Fonts/' + face, size)
                font = fonts[key]
                # 与测试 Font 的 6/9 像素步进一致，避免回放字体重排造成误判。
                step = 0
                for char in command['text']:
                    draw.text((x + step, y - round(size * .15)), char, font=font, fill=rgba)
                    step += (9 if ord(char) > 255 else 6) * scale * command['scale']
            elif kind == 'item':
                item_id = command['id'].split(':')[1]
                if item_id not in textures:
                    for group in ('item', 'block'):
                        path = f'assets/minecraft/textures/{group}/{item_id}.png'
                        if path in archive_paths:
                            textures[item_id] = Image.open(io.BytesIO(archive.read(path))).convert('RGBA')
                            break
                texture = textures.get(item_id)
                if texture:
                    side = max(1, round(16 * scale * command['scale']))
                    overlay.alpha_composite(texture.resize((side, side), Image.Resampling.NEAREST), (x, y))
            clip = command.get('clip')
            if clip:
                clip = [max(0, round(v * scale)) for v in clip]
                mask = Image.new('L', canvas.size)
                if clip[2] > clip[0] and clip[3] > clip[1]:
                    ImageDraw.Draw(mask).rectangle((clip[0], clip[1], clip[2] - 1, clip[3] - 1), fill=255)
                from PIL import ImageChops
                overlay.putalpha(ImageChops.multiply(overlay.getchannel('A'), mask))
            canvas.alpha_composite(overlay)
    canvas.convert('RGB').save(destination)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, default=Path('build/plan-ui-render'))
    parser.add_argument('--output', type=Path, default=Path('build/plan-ui-preview'))
    parser.add_argument('--minecraft-jar', type=Path, default=Path.home() / '.gradle/caches/forge_gradle/minecraft_repo/versions/1.20.1/client.jar')
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    for source in sorted(args.source.glob('*/*.json')):
        destination = args.output / (source.parent.name + '-' + source.stem + '.png')
        replay(source, destination, args.minecraft_jar)
        print(destination)


if __name__ == '__main__':
    main()
