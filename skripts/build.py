"""Builds all-in-one/VanillaSMP.sk from the files in scripts/ (run: python3 build.py)."""
import os

HERE = os.path.dirname(os.path.abspath(__file__))
ORDER = ['settings', 'economy', 'scoreboard', 'killrewards', 'market', 'baltop', 'ranks', 'teleport', 'ah']
HEADER = """# ============================================================
#  VANILLA SMP - every script in one file
#  Requires Skript 2.7+ and SkBee. Put ONLY this file in plugins/Skript/scripts/
#  (delete the separate settings/economy/market/... .sk files first!)
# ============================================================

"""

parts = []
for name in ORDER:
    with open(os.path.join(HERE, 'scripts', name + '.sk'), encoding='utf-8') as source:
        parts.append(source.read().rstrip('\n') + '\n')
out = os.path.join(HERE, 'all-in-one', 'VanillaSMP.sk')
with open(out, 'w', encoding='utf-8', newline='\n') as target:
    target.write(HEADER + '\n\n'.join(parts))
print('wrote', os.path.relpath(out, HERE))
