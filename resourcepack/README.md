# Server resource pack

One pack for everything on the server: the Lifesteal **Heart** and the five **legendary
weapons** (Kurogane, Sugarcrash, Riftblade, Gravebreaker, Starforged). Built as
`release/VanillaSMP-ResourcePack.zip` by `python3 resourcepack/build.py`, which also prints its
SHA-1. Works on Minecraft 1.20 to 1.21.x and newer, because both the old model overrides and
the 1.21.4+ item model files are inside.

| Item | Looks like | How the pack finds it |
|---|---|---|
| Heart (Lifesteal) | a red heart | red dye with custom model data 1001 |
| Kurogane | a katana: polished blade with a wavy temper line, raised spine section with a crimson groove, gold collar, square guard with a crimson ring, handle bound in crimson cord, crimson gems in the pommel | netherite sword, 1001 |
| Sugarcrash | a crescent blade: ivory spine, crimson layers and a pink glowing edge, silver crossguard with curled tips and a crimson gem, glossy candy-striped handle | netherite sword, 1002 |
| Riftblade | a broad dark violet sword split by a glowing rift that opens into a forked point, crescent guard with violet crystals, crystal hanging from the pommel | netherite sword, 1003 |
| Gravebreaker | a bearded battle axe: polished edge, forged-iron centre with rivets and a glowing crack, back and top spikes, wooden haft with iron bands, leather grip, spiked pommel | netherite axe, 1004 |
| Starforged | a navy double-bladed axe: glowing cyan edges, lightning and stars in the blades, an ice crystal in a gold setting, gold rings, ice shard on top | netherite axe, 1005 |

The numbers are the `custom-model-data` values in Legendary's and Lifesteal's `config.yml`;
if you change them there, change them in `build.py` too. Without the pack the weapons look
like normal netherite swords and axes with their legendary names.

## Putting it on the server

1. Upload `VanillaSMP-ResourcePack.zip` somewhere that gives a direct download link, for
   example [mc-packs.net](https://mc-packs.net) (it shows you the link and the SHA-1).
2. In `server.properties`:
   ```
   resource-pack=<the direct link>
   resource-pack-sha1=<the SHA-1>
   require-resource-pack=false
   ```
3. Restart the server. Players are asked to download the pack when they join.

If you used Lifesteal's own `resource-pack.url` setting before, empty it (`url: ""`): this pack
replaces the heart-only pack and already contains the Heart.

The weapons are **3D models** with smooth painted textures (512x512 each, no pixel art). Minecraft
item models can only be built from boxes, so each weapon is made the way server 3D weapons are
made in Blockbench: thin cut-out plates give the blades their smooth curved outlines, thicker
boxes give the spines, guards, handles and heads their depth, and boxes turned 45 degrees make
the gems and points. You see them in 3D in your hand, on the ground and in item frames; in the
inventory they are tilted a little so the depth shows. They are held like a vanilla sword or
axe, and need no mods: any client from 1.20 on shows them.

Each weapon is built by its own file in `models/` (its parts and how each is painted: polished
steel, gold, leather, wood, crystal, glow). `models/mesh.py` packs the painted faces into one
texture and writes the model, `models/render.py` draws the models for the showcase, and
`models/png.py` reads and writes PNG files (no image libraries needed).
`python3 textures.py --preview out.png` draws the showcase (`release/Weapons-Showcase.png`) and
`python3 textures.py --obj folder` writes every weapon as `.obj` + `.mtl` + `.png` to open in
Blender or Blockbench (`release/Weapons-3D-Models.zip`).
