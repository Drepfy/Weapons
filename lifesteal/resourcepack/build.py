"""Builds Lifesteal-ResourcePack.zip from pack/ (run from this folder: python3 build.py)."""
import os
import subprocess
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
PACK = os.path.join(HERE, 'pack')
OUT = os.path.join(HERE, '..', 'src', 'main', 'resources', 'Lifesteal-ResourcePack.zip')

subprocess.run([sys.executable, os.path.join(HERE, 'make_heart.py'),
                os.path.join(PACK, 'assets', 'lifesteal', 'textures', 'item', 'heart.png')],
               check=True, stdout=subprocess.DEVNULL)
with zipfile.ZipFile(OUT, 'w', zipfile.ZIP_DEFLATED) as archive:
    for root, _, files in sorted(os.walk(PACK)):
        for name in sorted(files):
            path = os.path.join(root, name)
            entry = zipfile.ZipInfo(os.path.relpath(path, PACK).replace(os.sep, '/'), (2024, 1, 1, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_DEFLATED
            with open(path, 'rb') as source:
                archive.writestr(entry, source.read())
print('wrote', os.path.normpath(OUT))
