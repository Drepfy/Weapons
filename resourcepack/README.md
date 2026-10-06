# Server resource pack

One pack for everything on the server: the Lifesteal **Heart** and the five **legendary
weapons** (Kurogane, Sugarcrash, Riftblade, Gravebreaker, Starforged). Built as
`release/VanillaSMP-ResourcePack.zip` by `python3 resourcepack/build.py`, which also prints its
SHA-1. Made for 1.21.11; the weapons also show on 1.20 to 1.21.3, because both the old model
overrides and the 1.21.4+ item model files are inside.

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
   require-resource-pack=true
   ```
3. Restart the server. Players are asked to download the pack when they join.

`require-resource-pack=true` is recommended: without the pack, the weapons' tooltip frames show
as a missing texture. To let players join without it, keep it `false` and set
`tooltip-style: ""` for each weapon in Legendary's `config.yml`.

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

## 1.21 item look, tooltips, effects and sounds (Legendary 1.2 and 1.3)

- **Item models** (`assets/legendary/items/<weapon>.json`, 1.21.4+): Legendary sets each
  weapon's `item_model` to `legendary:<weapon>`, so the model no longer depends on custom model
  data (which is still there for older clients).
- **Tooltips** (`textures/gui/sprites/tooltip/<weapon>_background.png` and `_frame.png`,
  1.21.2+): each weapon has its own tooltip, a dark background tinted in its colours with a
  faint glow at the edge, and a thin frame shading from one colour to another with small gems
  in the corners. Nine-sliced like the vanilla one, so it fits any length of lore. Drawn by
  `tooltips.py`.
- **Ability effects** (`items/fx/*.json`, `models/fx`, `textures/fx`): 19 glowing effects the
  abilities show with display entities: Kurogane's crimson streak, slash, cut and rune circle;
  Sugarcrash's candy-cane hook, candy burst, candy ring and the dizzy candy stars over a stunned
  player; Riftblade's rift, void portal and void burst; Gravebreaker's shockwave, ember ring and
  a 3D gravestone; Starforged's rune circle, star, nova, black hole and accretion disk. Each is
  painted with soft edges at 64 to 256 pixels by `fx.py` (flat ones lie on the ground, upright
  ones stand facing the viewer), lit at full brightness so they glow at night. Without the pack
  they show as paper.
- **Sounds** (`sounds/legendary/<weapon>/*.ogg`, `sounds.json`): 36 sounds, for every ability,
  passive and hit (`legendary:kurogane.parry`, `legendary:sugarcrash.hook`,
  `legendary:starforged.nova`...), with subtitles. They are made from scratch by
  `sounds/make_sounds.py` (`pip install numpy soundfile`): blade swishes and clangs, whooshes,
  sparkles, void hums, rumbles, bells and impacts built from noise and tones. The `.ogg` files
  are kept in the repository, so `build.py` only copies them.
