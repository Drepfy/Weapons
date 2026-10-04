# Server resource pack

One pack for everything on the server: the Lifesteal **Heart** and the five **legendary
weapons** (Kurogane, Sugarcrash, Riftblade, Gravebreaker, Starforged). Built as
`release/VanillaSMP-ResourcePack.zip` by `python3 resourcepack/build.py`, which also prints its
SHA-1. Works on Minecraft 1.20 to 1.21.x and newer, because both the old model overrides and
the 1.21.4+ item model files are inside.

| Item | Looks like | How the pack finds it |
|---|---|---|
| Heart (Lifesteal) | a red heart | red dye with custom model data 1001 |
| Kurogane | a katana: polished blade with a wavy temper line, blackened spine and crimson groove; a round guard with a crimson ring and gold collar; black grip with crimson wrap | netherite sword, 1001 |
| Sugarcrash | a crescent blade on a candy-striped grip: ivory back, crimson layers, pink glow along the edge; slim silver guard with a crimson gem | netherite sword, 1002 |
| Riftblade | a dark metal sword split by a violet rift that opens into a forked tip; crescent guard with a rift crystal; crystal pommel | netherite sword, 1003 |
| Gravebreaker | a bearded battle axe: polished steel edge, forged-iron centre with a glowing fissure and rivets, back and top spikes, dark wood haft | netherite axe, 1004 |
| Starforged | a double-bladed axe of deep navy metal: thin cyan edges, lightning inlays, an ice crystal set in gold, an ice shard on top, gold rings | netherite axe, 1005 |

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

The weapon textures are 32x32 pixel art (Minecraft accepts 32x32 item textures; they show
sharper than vanilla's 16x16). Each weapon is drawn by its own file in `art/`, with `art/pix.py`
as the small drawing toolkit (no image libraries needed). `python3 textures.py` prints them as
grids of palette letters, and `python3 textures.py --preview out.png` draws the showcase sheet:
all five enlarged on a dark background (the same picture as `release/Weapons-Showcase.png`).
