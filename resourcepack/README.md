# Server resource pack

One pack for everything on the server: the Lifesteal **Heart** and the five **legendary
weapons** (Kurogane, Sugarcrash, Riftblade, Gravebreaker, Starforged). Built as
`release/VanillaSMP-ResourcePack.zip` by `python3 resourcepack/build.py`, which also prints its
SHA-1. Works on Minecraft 1.20 to 1.21.x and newer, because both the old model overrides and
the 1.21.4+ item model files are inside.

| Item | Looks like | How the pack finds it |
|---|---|---|
| Heart (Lifesteal) | a red heart | red dye with custom model data 1001 |
| Kurogane | a broad crimson-steel katana: polished edge with a temper line, raised ridge, sunken crimson groove, thick octagonal guard with a gold collar, black grip with a raised crimson wrap | netherite sword, 1001 |
| Sugarcrash | a thick crescent blade: ivory spine, crimson layers, thin pink glowing edge; silver crossguard with a raised gem; candy-striped grip whose stripes stand out | netherite sword, 1002 |
| Riftblade | a broad dark sword split by a sunken violet rift that opens into a forked tip; crescent guard and pommel with raised crystals | netherite sword, 1003 |
| Gravebreaker | a bearded battle axe: thin polished edge, thick forged-iron centre with raised rivets and a glowing crack, spikes, dark wood haft, thick leather grip | netherite axe, 1004 |
| Starforged | a navy double-bladed axe: thin cyan edges, lightning inlays and stars, an ice crystal standing out of a gold setting, ice shard on top, gold rings | netherite axe, 1005 |

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

The weapons are **3D models**: chunky pixel art with real depth. Every pixel of the 32x32
texture has a thickness, so edges are thin, spines and guards are thick, and gems, rivets and
grip wraps stand out, the way you see them in your hand, on the ground and in item frames. In
the inventory they are tilted a little so the depth shows there too. They are held exactly like
a vanilla sword or axe (vanilla's own hand positions), and the models need no mods: any client
from 1.20 on shows them.

Each weapon is drawn by its own file in `art/` (shape, colours and how thick each part is);
`art/model3d.py` turns that into the model, `art/render3d.py` draws the models for the showcase,
and `art/pix.py` is the small drawing toolkit (no image libraries needed).
`python3 textures.py` prints the sprites, `python3 textures.py --preview out.png` draws the
showcase (the same picture as `release/Weapons-Showcase.png`) and `--flat out.png` the flat
sprites.
