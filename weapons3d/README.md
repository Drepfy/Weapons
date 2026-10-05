# 3D weapon set

Five blocky 3D weapons with Minecraft-style pixel textures (nearest-neighbour, like vanilla held items).

| Model | Theme |
|---|---|
| `crimson_warden` | Steel greatsword, crimson fuller runes, down-swept iron guard |
| `bloodfang` | Curved crimson/bone saber with glowing blood groove |
| `voidrender` | Dark-iron sword, void-energy channel, barbed edges, horned guard |
| `doomcleaver` | Bearded battle axe, iron socket, gold rivets, back spike |
| `stormpiercer` | Ice-crystal spear with lightning vein, navy shaft, gold rings |

- **`weapons.blend`**: open in Blender. The textures are packed inside the file. Use *Material Preview* or *Rendered* view to see them.
- `glb/*.glb`: each weapon on its own, for Blockbench or other tools.
- `textures/*.png`: the pixel textures (`*_glow.png` = emission masks).
- `showcase.png`: preview render.

Rebuild after editing `designs.py`: `blender -b -P build_blend.py`
