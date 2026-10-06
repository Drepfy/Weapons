"""Checks the server pack against the Legendary plugin: every JSON parses, every model, texture,
effect and sound the plugin uses exists, and every texture an item model uses is in a folder the
block atlas loads (item/ or block/), or it shows as the purple and black missing texture.

    python3 resourcepack/check.py      (after build.py)
"""
import glob
import hashlib
import json
import os
import re
import sys
import zipfile

root = sys.argv[1] if len(sys.argv) > 1 else os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
z = zipfile.ZipFile(f'{root}/release/VanillaSMP-ResourcePack.zip')
names = set(z.namelist())
problems = []
for n in names:
    if n.endswith(('.json', '.mcmeta')):
        try:
            json.loads(z.read(n))
        except Exception as e:
            problems.append(f'bad json {n}: {e}')
def tex_file(ref):
    ns, p = ref.split(':') if ':' in ref else ('minecraft', ref)
    return ns, p, f'assets/{ns}/textures/{p}.png'
def model_file(ref):
    ns, p = ref.split(':') if ':' in ref else ('minecraft', ref)
    return f'assets/{ns}/models/{p}.json'
for n in sorted(names):
    if '/models/' in n and n.endswith('.json'):
        m = json.loads(z.read(n))
        for key, t in m.get('textures', {}).items():
            if not isinstance(t, str) or t.startswith('#'):
                continue
            ns, p, f = tex_file(t)
            if not (p.startswith('item/') or p.startswith('block/')):
                problems.append(f'{n}: texture {t} is outside item/ and block/ (missing texture in game)')
            if ns != 'minecraft' and f not in names:
                problems.append(f'{n}: texture {t} missing')
        parent = m.get('parent')
        if parent and not parent.startswith(('minecraft:', 'item/', 'block/', 'builtin/')) and model_file(parent) not in names:
            problems.append(f'{n}: parent {parent} missing')
def walk(node, out):
    if isinstance(node, dict):
        if 'model' in node and isinstance(node['model'], str):
            out.append(node['model'])
        for v in node.values():
            walk(v, out)
    elif isinstance(node, list):
        for v in node:
            walk(v, out)
for n in sorted(names):
    if re.match(r'assets/[^/]+/items/.*\.json$', n):
        refs = []
        walk(json.loads(z.read(n)), refs)
        for r in refs:
            if not r.startswith('minecraft:') and model_file(r) not in names:
                problems.append(f'{n}: model {r} missing')
src = ''.join(open(p).read() for p in glob.glob(f'{root}/legendary/src/main/java/**/*.java', recursive=True))
fx = sorted(set(re.findall(r'spawn\("([a-z_]+)"', src)))
for e in fx:
    if f'assets/legendary/items/fx/{e}.json' not in names:
        problems.append(f'effect {e} missing')
for w in ['kurogane', 'sugarcrash', 'riftblade', 'gravebreaker', 'starforged']:
    for f in [f'assets/legendary/items/{w}.json', f'assets/legendary/textures/gui/sprites/tooltip/{w}_frame.png',
              f'assets/legendary/textures/gui/sprites/tooltip/{w}_background.png']:
        if f not in names:
            problems.append(f'{f} missing')
sj = json.loads(z.read('assets/legendary/sounds.json'))
cfg = open(f'{root}/legendary/src/main/resources/config.yml').read()
used = sorted(set(re.findall(r'legendary:([a-z_]+\.[a-z_]+)', cfg)))
for s in used:
    if s not in sj:
        problems.append(f'sound event {s} missing')
for ev, d in sj.items():
    for snd in d['sounds']:
        ns, p = snd['name'].split(':')
        if f'assets/{ns}/sounds/{p}.ogg' not in names:
            problems.append(f'sound file {snd["name"]} missing')
print(f'{len(names)} files, {len(fx)} effects used, {len(used)} sounds used ({len(sj)} in the pack)')
print('unused sounds:', sorted(set(sj) - set(used)))
print('problems:', problems or 'none')
print('sha1', hashlib.sha1(open(f'{root}/release/VanillaSMP-ResourcePack.zip', 'rb').read()).hexdigest())
sys.exit(1 if problems else 0)
