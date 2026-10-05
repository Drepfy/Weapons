# Server resource pack

One pack for everything on the server: the Lifesteal **Heart** and the five **legendary
weapons** (Kurogane, Sugarcrash, Riftblade, Gravebreaker, Starforged). Built as
`release/VanillaSMP-ResourcePack.zip` by `python3 resourcepack/build.py`, which also prints its
SHA-1. Works on Minecraft 1.20 to 1.21.x and newer, because both the old model overrides and
the 1.21.4+ item model files are inside.

| Item | Looks like | How the pack finds it |
|---|---|---|
| Heart (Lifesteal) | a red heart | red dye with custom model data 1001 |
| Kurogane | a crimson-steel katana: polished blade, a glowing crimson temper line that shimmers along it, a dark groove, gold collar, black and gold guard, handle bound in crimson cord | netherite sword, 1001 |
| Sugarcrash | a candy war-scythe: a broad crescent blade (ivory spine, crimson body, glowing pink edge) on a glossy candy-striped shaft, silver collar with a crimson gem | netherite sword, 1002 |
| Riftblade | a broad void greatsword split by a glowing violet rift that flows up the blade and tears its point in two, crescent guard with a crystal, wire-bound grip, crystal pommel | netherite sword, 1003 |
| Gravebreaker | a bearded battle axe: polished edge, forged-iron centre with rivets and a smouldering crack, back and top spikes, dark-wood haft with iron bands, leather grip | netherite axe, 1004 |
| Starforged | a navy double-bladed battle axe: crackling cyan edges, lightning and stars, a pulsing ice crystal in gold, gold rings, ice shard on top | netherite axe, 1005 |

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

The weapons are **3D models with detailed pixel-art textures** (128 x 128, 8 pixels per model
unit), in the style of the best fantasy weapon packs but our own designs. They stand upright and
are turned onto the diagonal: in the inventory they fill the slot from corner to corner, and in
the hand the middle of the grip sits exactly where a vanilla sword's handle does (a little
bigger than a vanilla sword). Edges are thin, guards, grips and gems are thicker, so they have
real depth in your hand, on the ground and in item frames. The glowing parts (Kurogane's temper
line, Sugarcrash's edge, Riftblade's rift, Gravebreaker's crack, Starforged's edges, lightning
and crystal) are animated, and on newer clients they light up in the dark. No renaming and no
mods: the plugin's custom model data picks the model, on any client from 1.20 on.

Each weapon is built by its own file in `models/`: its parts as shapes with a height map,
colours and a thickness. `models/pixel.py` paints them with consistent lighting (light from the
top left in the inventory) and animates the glow, `models/voxel.py` turns the pixels into the
3D model, `models/common.py` sets how it is held and shown, and `models/render.py` draws the
showcase. `python3 textures.py --preview out.png` draws `release/Weapons-Showcase.png`, and
`python3 textures.py --obj folder` writes every weapon as `.obj` + `.mtl` + `.png` to open in
Blender or Blockbench (`release/Weapons-3D-Models.zip`).
