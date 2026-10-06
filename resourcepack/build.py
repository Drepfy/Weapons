"""Builds the server resource pack: the Lifesteal Heart and the five legendary weapons.

Run from anywhere: python3 resourcepack/build.py
Writes release/VanillaSMP-ResourcePack.zip and prints its SHA-1 for server.properties.
"""
import hashlib
import io
import json
import os
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(ROOT, 'lifesteal', 'resourcepack'))
import textures  # noqa: E402
import tooltips  # noqa: E402
import make_heart  # noqa: E402

OUT = os.path.join(ROOT, 'release', 'VanillaSMP-ResourcePack.zip')

# custom-model-data in Legendary's config.yml (1001-1005) and Lifesteal's (1001 on red dye).
SWORDS = [(1001, 'kurogane'), (1002, 'sugarcrash'), (1003, 'riftblade')]
AXES = [(1004, 'gravebreaker'), (1005, 'starforged')]


def as_json(data):
    return (json.dumps(data, indent=2) + '\n').encode('utf-8')


def files():
    out = {}
    out['pack.mcmeta'] = as_json({'pack': {
        'pack_format': 34,
        'supported_formats': {'min_inclusive': 15, 'max_inclusive': 99},
        'min_format': 15,
        'max_format': 99,
        'description': 'ᴠᴀɴɪʟʟᴀ sᴍᴘ: Hearts and Legendary weapons',
    }})
    out['pack.png'] = icon()

    # ---- Lifesteal: the Heart (red dye with custom model data 1001) ----
    out['assets/lifesteal/textures/item/heart.png'] = make_heart.png(make_heart.build())
    out['assets/lifesteal/models/item/heart.json'] = as_json(
        {'parent': 'minecraft:item/generated', 'textures': {'layer0': 'lifesteal:item/heart'}})
    out['assets/minecraft/models/item/red_dye.json'] = as_json({
        'parent': 'minecraft:item/generated',
        'textures': {'layer0': 'minecraft:item/red_dye'},
        'overrides': [{'predicate': {'custom_model_data': 1001}, 'model': 'lifesteal:item/heart'}],
    })
    out['assets/minecraft/items/red_dye.json'] = as_json(dispatch('red_dye', [(1001, 'lifesteal:item/heart')]))

    # ---- Legendary weapons ----
    for item, weapons in (('netherite_sword', SWORDS), ('netherite_axe', AXES)):
        for _, name in weapons:
            out[f'assets/legendary/textures/item/{name}.png'] = textures.texture(name)
            glow = textures.glow_texture(name)
            if glow:
                # the glowing parts: a small animated texture
                out[f'assets/legendary/textures/item/{name}_glow.png'] = glow
                out[f'assets/legendary/textures/item/{name}_glow.png.mcmeta'] = textures.animation(name)
            # a 3D model: no item/generated parent, or Minecraft would flatten it again
            out[f'assets/legendary/models/item/{name}.json'] = (
                json.dumps(textures.model(name), separators=(',', ':'), ensure_ascii=False) + '\n').encode('utf-8')
            # 1.21.4+: the plugin sets item_model legendary:<name> (and a tooltip style of its own)
            out[f'assets/legendary/items/{name}.json'] = as_json(
                {'model': {'type': 'minecraft:model', 'model': f'legendary:item/{name}'}})
            out[f'assets/legendary/textures/gui/sprites/tooltip/{name}_background.png'] = textures.png.encode(
                tooltips.background(name))
            out[f'assets/legendary/textures/gui/sprites/tooltip/{name}_background.png.mcmeta'] = as_json(tooltips.meta(9))
            out[f'assets/legendary/textures/gui/sprites/tooltip/{name}_frame.png'] = textures.png.encode(
                tooltips.frame(name))
            out[f'assets/legendary/textures/gui/sprites/tooltip/{name}_frame.png.mcmeta'] = as_json(tooltips.meta(10))
        # 1.20 - 1.21.3: model overrides
        out[f'assets/minecraft/models/item/{item}.json'] = as_json({
            'parent': 'minecraft:item/handheld',
            'textures': {'layer0': f'minecraft:item/{item}'},
            'overrides': [{'predicate': {'custom_model_data': cmd}, 'model': f'legendary:item/{name}'}
                          for cmd, name in weapons],
        })
        # 1.21.4+: item model definitions
        out[f'assets/minecraft/items/{item}.json'] = as_json(
            dispatch(item, [(cmd, f'legendary:item/{name}') for cmd, name in weapons]))
    # The abilities' effects are vanilla particles and blocks, and their sounds vanilla sounds:
    # nothing for them in the pack.
    return out


def dispatch(item, entries):
    return {'model': {
        'type': 'minecraft:range_dispatch',
        'property': 'minecraft:custom_model_data',
        'index': 0,
        'entries': [{'threshold': cmd, 'model': {'type': 'minecraft:model', 'model': model}}
                    for cmd, model in entries],
        'fallback': {'type': 'minecraft:model', 'model': f'minecraft:item/{item}'},
    }}


def icon():
    """64x64: Kurogane and Starforged crossed, as 3D models, on a dark background."""
    size = 64
    pixels = [[(24, 22, 32)] * size for _ in range(size)]
    for name, mirror in (('starforged', True), ('kurogane', False)):
        image = textures.gui_image(name, size)
        for y in range(size):
            for x in range(size):
                p = image[y][size - 1 - x if mirror else x]
                if p is not None:
                    a = p[3] / 255.0
                    pixels[y][x] = tuple(int(p[i] * a + pixels[y][x][i] * (1 - a)) for i in range(3))
    return textures.png.encode(pixels)


def main():
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, 'w', zipfile.ZIP_DEFLATED) as archive:
        for path, data in sorted(files().items()):
            entry = zipfile.ZipInfo(path, (2024, 1, 1, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(entry, data)
    data = buffer.getvalue()
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, 'wb') as target:
        target.write(data)
    print('wrote', os.path.relpath(OUT, ROOT))
    print('sha1', hashlib.sha1(data).hexdigest())


if __name__ == '__main__':
    main()
