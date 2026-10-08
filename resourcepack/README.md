# Server resource pack

One pack for everything on the server: the Lifesteal **Heart** and the four **legendary
weapons** of Legendary 3.0 (Katana, Candy Cane, Crush, Reaper). Built as
`release/VanillaSMP-ResourcePack.zip` by `python3 resourcepack/build.py`, which also prints its
SHA-1. Made for 1.21.11; the weapons also show on 1.20 to 1.21.3, because both the old model
overrides and the 1.21.4+ item model files are inside.

| Item | Looks like | How the pack finds it |
|---|---|---|
| Heart (Lifesteal) | a red heart | red dye with custom model data 1001 |
| Katana | an obsidian katana: a long curved blade of black volcanic glass polished to a mirror, its cutting edge burning crimson behind a rolling temper line, a silver ridge line and a crimson vein glowing in the groove along its back; a gold collar, a round black iron guard inlaid with gold waves between gold washers, a handle of black ray skin bound in crimson silk with gold ornaments, a black and gold pommel and a crimson silk tassel on a gold ring | netherite sword, 1001 |
| Candy Cane | a peppermint broadsword: a broad blade of glossy white hard candy twisted with bold red stripes, its edges turning to clear pink sugar glass that glows; two candy canes curling down round the hand as the guard, a pink rock-candy crystal where they meet, a grip bound in red and white ribbon and a peppermint candy as the pommel | netherite sword, 1002 |
| Reaper | a soul-harvesting sickle sword: a black-violet blade that sweeps forward into a hooked point like a scythe's, its inner edge burning with spectral green soul-fire, glowing soul runes down its middle and barbs along its back; a guard of two ribs round a bone knuckle, a grip of vertebrae bound in violet leather and a skull pommel with glowing eyes | netherite sword, 1003 |
| Crush | a war axe with a hammer back: a great crescent blade of dark hammered steel with a mirror-bright edge and a bronze-inlaid border, azure light breaking out of a crystal core and running through the steel in glowing cracks; a heavy square hammer behind the haft, cracked by the same light; bronze bands and rivets, a top spike, a dark steel haft, a grip in dark blue leather and bronze wire, and a bronze pommel with a small azure crystal | netherite axe, 1004 |

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
   require-resource-pack=true
   ```
3. Restart the server. Players are asked to download the pack when they join.

`require-resource-pack=true` is recommended: without the pack, the weapons' tooltip frames show
as a missing texture. To let players join without it, keep it `false` and set
`tooltip-style: ""` for each weapon in Legendary's `config.yml`.

If you used Lifesteal's own `resource-pack.url` setting before, empty it (`url: ""`): this pack
replaces the heart-only pack and already contains the Heart.

The weapons are **smooth 3D models with high-resolution painted textures** (512 x 512, 32
texels per model unit, not pixel art), our own designs. The materials read as what they are:
the Katana's black glass with a clear glossy coat that reflects the light untinted (`coat` in
`models/paint.py`), so it reads as polished obsidian rather than grey steel; the Candy Cane's
glossy hard candy with sugar sparkles and crisp twisted stripes; Crush's forged blued steel
with broad hammer facets and a mirror-bright ground edge; the Reaper's smoky black-violet steel
and weathered bone. Up close each has fine detail worked in: the Katana's guard is inlaid with
gold waves and gold ornaments sit under the silk cord; the Candy Cane's canes carry a thin mint
stripe; Crush has a bronze line inlaid round its blade, rivets and the scratches of use; the
Reaper has runes cut down its blade, hairline cracks in its bones and a carved skull. Every
texel is lit smoothly from its own slope, so blades have real bevels and highlights, metal
reflects light, gems show their facets and glowing parts light up the surfaces round them (how
far is set for each part with `bloom`, so a glowing edge never washes a whole blade in its
colour). Grips, hafts and collars are round (8 or 16 sided, lit as if perfectly round), the
outlines of blades and axe heads are smooth curves, and their sides are long straight facets
that follow the curves, so nothing looks stepped when you turn them. They stand upright and are
turned onto the diagonal: in the inventory they fill the slot from corner to corner, and in the
hand the middle of the grip sits exactly where a vanilla sword's handle does (a little bigger
than a vanilla sword). The glowing parts (the Katana's molten edge and groove vein, the Candy
Cane's sugar edge and crystal, Crush's cracks and crystals, the Reaper's soul-fire, runes and
skull eyes) shimmer through a small animated texture, and on newer clients they light up in the
dark. No renaming and no mods: the plugin's custom model data picks the model, on any client
from 1.20 on.

Each weapon is built by its own file in `models/`: its parts and how each looks at every point
(colour, surface shape, metal, gloss, glow). `models/paint.py` does the light and materials,
`models/forge.py` builds the 3D model and bakes its textures (flat parts get a smooth cut-out
outline, a solid body and turned rim boxes along their edges; round parts get prisms with their
surface unwrapped onto the texture; gems get crisp turned boxes), `models/looks.py` has shared
looks (cut gems, wound grips, wood, hammered iron), `models/common.py` sets how it is held and
shown, and `models/render.py` draws the showcase. `python3 textures.py --preview out.png` draws
`release/Weapons-Showcase.png`, and `python3 textures.py --obj folder` writes every weapon as
`.obj` + `.mtl` + `.png` to open in Blender or Blockbench (`release/Weapons-3D-Models.zip`).

## 1.21 item look and tooltips

- **Item models** (`assets/legendary/items/<weapon>.json`, 1.21.4+): Legendary sets each
  weapon's `item_model` to `legendary:<weapon>`, so the model no longer depends on custom model
  data (which is still there for older clients).
- **Tooltips** (`textures/gui/sprites/tooltip/<weapon>_background.png` and `_frame.png`,
  1.21.2+): each weapon has its own tooltip, a dark background tinted in its colours with a
  faint glow at the edge, and a thin frame shading from one colour to another with small gems
  in the corners. Nine-sliced like the vanilla one, so it fits any length of lore. Drawn by
  `tooltips.py`.
- **Ability effects** (`items/fx/*.json`, `models/fx`, `textures/item/fx`): 8 effects the
  abilities show with display entities, each weapon in its own style and none shared:
  - Katana: `katana_glint` (a fine white line with a four-pointed flare, fringed crimson: the
    blade catching the light as Draw is used) and `katana_cut` (a razor-thin white-hot line
    along a faint curve, needle-sharp ends, brightest where the blade finished, a thin crimson
    trail behind it). It is painted for being shown about 4.5 blocks wide and a quarter of a
    block tall: the plugin sweeps it across in two ticks and closes it up in two more.
  - Candy Cane: `candy_goo` (a glossy glob of pink goo with a lump pulling away and drops
    strung out behind; flung out to make the traps) and `candy_puddle` (the trap: a wet,
    glossy puddle of pink candy goo lying on the ground, marbled, with bubbles, sprinkles and
    splashes round its edge). The goo's particles are vanilla item particles of `candy_goo`,
    and they show its particle texture `candy_goo_bits` (little glossy goo blobs), so the
    spray is the same goo.
  - Crush: `crush_crater` (the ground broken into tipped plates round a hollow still glowing
    azure, cracks splitting out past them, grit and dust thrown out in streaks). It darkens
    and lightens the real ground under it rather than covering it, and the plugin adds chunks
    of the real block thrown up (block displays). No block is ever changed.
  - Reaper: `reap_soul` (the centrepiece: a wisp of a reaped soul, a bright round heart of
    soul-light with a flickering flame-tail, three of them spiral up round the victim),
    `reap_scythe` (a small spectral scythe: a dark shaft and a ghostly blade, white at its edge
    and burning soul-green into violet; it circles the victim) and `reap_slash` (its cut).

  Each is painted at 128 or 256 pixels by `fx.py` (flat ones lie on the ground, upright ones
  stand facing the viewer), lit at full brightness. The goo and the broken ground are shaded
  like real surfaces (a height field lit from high in the north, so a wet highlight and
  rounded edges); the glowing ones have a soft glow of their own colour round them so they
  stand out in daylight and from afar too (`BOLD` in `fx.py`). Without the pack they show as
  paper. `python3 fx.py out.png` draws a sheet of them all.
- **No sounds:** the abilities play vanilla sounds (each weapon its own), so everyone hears
  them, pack or not, and the pack holds no sound files.

After `build.py`, `python3 check.py` checks the pack against the plugin: every file parses,
every model and texture the weapons and every effect the plugin uses is there, every texture
a model uses is in `textures/item/` or `textures/block/` (anywhere else it shows as the purple
and black missing texture), and the plugin asks the pack for no sound.
