# Server resource pack

One pack for everything on the server: the Lifesteal **Heart** and the five **legendary
weapons** (Kurogane, Sugarcrash, Wyrmfang, Gravebreaker, Starforged). Built as
`release/VanillaSMP-ResourcePack.zip` by `python3 resourcepack/build.py`, which also prints its
SHA-1. Made for 1.21.11; the weapons also show on 1.20 to 1.21.3, because both the old model
overrides and the 1.21.4+ item model files are inside.

| Item | Looks like | How the pack finds it |
|---|---|---|
| Heart (Lifesteal) | a red heart | red dye with custom model data 1001 |
| Kurogane | a crimson-steel katana: a mirror-polished curved blade with a frosted edge and a wavy temper line that glows crimson and pulses up the blade, a red-lacquered groove, gold collar, round black-iron guard rimmed in gold, round handle bound in crimson silk over white ray skin | netherite sword, 1001 |
| Sugarcrash | a candy war-scythe: a crescent blade of glossy crimson hard candy with a white candy spine swirled in red and a glowing pink cutting edge, a silver collar set with a cut gem, a round candy-cane shaft | netherite sword, 1002 |
| Wyrmfang | a dragon greatsword: a broad blade of clouded, veined jade with pale bone bevels, dragon teeth along both edges and scales carved into its base, a venom-green vein glowing up the middle; gold dragon wings as the guard round an amber dragon's eye with a slit pupil, a dark green grip bound in gold wire, a gold claw clutching a jade orb as the pommel | netherite sword, 1003 |
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
best fantasy weapon packs but our own designs. The materials read as what they are: Kurogane's
mirror-polished steel with a rolling temper line, sparkling crystals along it and a dark
burnished back; Sugarcrash's glossy hard candy with a clear shine, sugar sparkles and crisp
stripes; Wyrmfang's polished jade, clouded and veined like the real stone; Gravebreaker's
forged blued steel with shallow hammer facets and a mirror-bright ground edge (not rough stone);
Starforged's star-steel and gold. Polished metal reflects a studio with a crisp horizon, so it
looks like steel and not grey plastic. Up close each weapon has fine detail worked into the
metal, the way a real smith would: Kurogane's guard is inlaid with a gold blossom and a gold
ornament sits under the silk cord; Sugarcrash's silver collar is engraved with scrollwork;
Wyrmfang has scales carved into the base of its blade and picked out in gold, and bony fingers
raised along its wings;
Gravebreaker has a band of runes cut along the edge, an engraved border round its plate and
the scratches of use; Starforged has a gold line inlaid round its blades with beads at the
ends and its stars joined into constellations. Worn steel catches the light along its
scratches (`scratches`, `runes` and `scroll` in `models/looks.py`). Every texel is lit smoothly from its own slope, so
blades have real bevels and highlights, metal reflects light, gems show their facets and
glowing parts bloom onto the steel round them. Grips, hafts, collars and the katana's guard are
round (8 or 16 sided, lit as if perfectly round), the outlines of blades and axe heads are
smooth curves, and their sides are long straight facets that follow the curves, so nothing
looks stepped when you turn them. They stand upright and are turned onto the diagonal: in the
inventory they fill the slot from corner to corner, and in the hand the middle of the grip sits
exactly where a vanilla sword's handle does (a little bigger than a vanilla sword). The glowing
parts (Kurogane's temper line, Sugarcrash's edge, Wyrmfang's venom vein, dragon eye and orb, Gravebreaker's
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

## 1.21 item look and tooltips

- **Item models** (`assets/legendary/items/<weapon>.json`, 1.21.4+): Legendary sets each
  weapon's `item_model` to `legendary:<weapon>`, so the model no longer depends on custom model
  data (which is still there for older clients).
- **Tooltips** (`textures/gui/sprites/tooltip/<weapon>_background.png` and `_frame.png`,
  1.21.2+): each weapon has its own tooltip, a dark background tinted in its colours with a
  faint glow at the edge, and a thin frame shading from one colour to another with small gems
  in the corners. Nine-sliced like the vanilla one, so it fits any length of lore. Drawn by
  `tooltips.py`.
- **Ability effects** (`items/fx/*.json`, `models/fx`, `textures/item/fx`): 14 glowing effects the
  abilities show with display entities: Kurogane's crimson streak, slash, cut and rune circle;
  Sugarcrash's candy burst and the candy rings of Sugar Rush (the thrown scythe is the weapon's
  own model); Wyrmfang's jade crescent, dragon claw marks and green dragon fire; Gravebreaker's
  shockwave and ember ring (the rocks of Earthsplitter are real blocks shown by block displays,
  none placed); Starforged's rune circle, star and nova. Each is painted with soft edges at 64 to 256 pixels by `fx.py` (flat ones
  lie on the ground, upright ones stand facing the viewer), lit at full brightness so they glow
  at night. Thin lines are thickened and every effect has a soft glow of its own colour round
  it, so they stand out in daylight and from afar too (`BOLD` in `fx.py`). Without the pack
  they show as paper.
- **No sounds:** the abilities play a few vanilla sounds (one per ability), so everyone hears
  them, pack or not, and the pack holds no sound files.

After `build.py`, `python3 check.py` checks the pack against the plugin: every file parses,
every model and texture the weapons and every effect the plugin uses is there, every texture
a model uses is in `textures/item/` or `textures/block/` (anywhere else it shows as the purple
and black missing texture), and the plugin asks the pack for no sound.
