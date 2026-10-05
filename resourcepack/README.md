# Server resource pack

One pack for everything on the server: the Lifesteal **Heart** and the five **legendary
weapons** (Kurogane, Sugarcrash, Riftblade, Gravebreaker, Starforged). Built as
`release/VanillaSMP-ResourcePack.zip` by `python3 resourcepack/build.py`, which also prints its
SHA-1. Works on Minecraft 1.20 to 1.21.x and newer, because both the old model overrides and
the 1.21.4+ item model files are inside.

| Item | Looks like | How the pack finds it |
|---|---|---|
| Heart (Lifesteal) | a red heart | red dye with custom model data 1001 |
| Kurogane | a crimson-steel katana: a mirror-polished curved blade with a frosted edge and a wavy temper line that glows crimson and pulses up the blade, a red-lacquered groove, gold collar, round black-iron guard rimmed in gold, round handle bound in crimson silk over white ray skin | netherite sword, 1001 |
| Sugarcrash | a candy war-scythe: a crescent blade of glossy crimson hard candy with a white candy spine swirled in red and a glowing pink cutting edge, a silver collar set with a cut gem, a round candy-cane shaft | netherite sword, 1002 |
| Riftblade | a broad greatsword of dark void steel flecked with stars, silver bevels, split by a glowing violet rift that flows up the blade and tears its point in two; a crescent guard with silver rims, cut violet crystals in the guard and pommel, a grip bound in violet wire | netherite sword, 1003 |
| Gravebreaker | a bearded battle axe: dark hammered steel with a freshly ground edge, a riveted iron plate split by a smouldering ember crack, back and top spikes, round iron socket, walnut haft with iron bands, leather-wrapped grip | netherite axe, 1004 |
| Starforged | a double-bladed battle axe of navy star-steel trimmed in gold: frosted edges crackling with cyan lightning, lightning bolts and stars set in the blades, a cut ice crystal pulsing in a gold setting, gold rings, gold-threaded grip, ice shard on top | netherite axe, 1005 |

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

The weapons are **smooth 3D models with high-resolution painted textures** (512 x 512, 32
texels per model unit, four times sharper than before and not pixel art), in the style of the
best fantasy weapon packs but our own designs. Every texel is lit smoothly from its own slope, so
blades have real bevels and highlights, metal reflects light, gems show their facets and
glowing parts bloom onto the steel round them. Grips, hafts, collars and the katana's guard are
round (8 or 16 sided, lit as if perfectly round), the outlines of blades and axe heads are
smooth curves, and their sides are long straight facets that follow the curves, so nothing
looks stepped when you turn them. They stand upright and are turned onto the diagonal: in the
inventory they fill the slot from corner to corner, and in the hand the middle of the grip sits
exactly where a vanilla sword's handle does (a little bigger than a vanilla sword). The glowing
parts (Kurogane's temper line, Sugarcrash's edge, Riftblade's rift and crystals, Gravebreaker's
crack, Starforged's edges, lightning, stars and crystal) shimmer through a small animated
texture, and on newer clients they light up in the dark. No renaming and no mods: the plugin's
custom model data picks the model, on any client from 1.20 on.

Each weapon is built by its own file in `models/`: its parts and how each looks at every point
(colour, surface shape, metal, gloss, glow). `models/paint.py` does the light and materials,
`models/forge.py` builds the 3D model and bakes its textures (flat parts get a smooth cut-out
outline, a solid body and turned rim boxes along their edges; round parts get prisms with their
surface unwrapped onto the texture; gems get crisp turned boxes), `models/looks.py` has shared
looks (cut gems, wound grips, wood, hammered iron), `models/common.py` sets how it is held and
shown, and `models/render.py` draws the showcase. `python3 textures.py --preview out.png` draws
`release/Weapons-Showcase.png`, and `python3 textures.py --obj folder` writes every weapon as
`.obj` + `.mtl` + `.png` to open in Blender or Blockbench (`release/Weapons-3D-Models.zip`).
