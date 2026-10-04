# Legendary

Five legendary netherite weapons for **ᴠᴀɴɪʟʟᴀ sᴍᴘ**, each with its own playstyle. Every
weapon is tracked one by one, so it cannot be duplicated, stored away, or passed between alt
accounts. For Paper 1.21 and newer.

**Controls:** right-click uses a weapon's first ability and sneak + right-click its second.
While you hold one, its cooldowns show above the hotbar (`Crescent Draw 4.2s | Edge ■■□□`),
with the Combat plugin's timer in front when you are in combat. A soft chime plays when an
ability is ready again.

## The weapons

All five are netherite with **Sharpness VI**, are unbreakable, and have a custom name, lore,
tracking number (`#3F9A2C1E`) and custom model data (1001 to 1005) for resource packs.

### Kurogane (katana): precision and sustained combat

- **Crescent Draw** (right-click, 8s): a crescent slash flies 7 blocks forward, hitting
  everything it passes once (6 damage) and knocking it back. It stops at walls.
- **Unbroken Edge** (passive): fully charged hits on the *same* target build Edge, up to 4
  stacks, and each stack adds +5% melee damage. Spam clicking keeps the chain alive but does
  not build it. Edge fades after 3s without a hit, and switching targets starts again.
  Crescent Draw spends the stacks for +1.5 damage each.

### Sugarcrash (candy cane): mobility and burst tempo

- **Sugar Rush** (right-click): Speed II and Haste II (faster attack recharge) for 6s, with a
  candy trail. The 18s cooldown starts when it wears off.
- **Sweet Shock** (sneak + right-click, 14s): a candy shockwave hits everyone within 5 blocks
  for 2 damage, knocks them back and gives Slowness II for 2.5s.

### Riftblade (void sword): space and positioning

- **Rift Slash** (right-click, 10s): a rift travels 10 blocks along the ground. Whoever it
  passes through takes 6 damage, is thrown back and gets 3s of Nausea (the distortion). It
  moves at a visible speed, so it can be dodged.
- **Rift Recall** (sneak + right-click): marks where you stand. Use it again within 10s to
  return there. Everyone can see the mark, and building over it collapses the rift. The 22s
  cooldown starts after returning (or when the mark fades). It is a normal teleport, so the
  Combat plugin's safe zones and region plugins can refuse it, and the mark then stays.

### Gravebreaker (executioner's axe): ground control and heavy hits

- **Earthsplitter** (right-click, 12s): a shockwave cracks along the ground for 9 blocks.
  Players it hits take 6 damage, are thrown upwards and get Mining Fatigue II for 3s. No block
  is ever changed: the cracks and debris are only shown. It climbs single steps and stops at
  walls and drops.
- **Executioner's Mark** (passive): 3 counted axe hits on the same player (at most 6s apart,
  no spam clicks) mark them for 8s, with a ring of blood over their head that everyone sees,
  and they are told. The next Earthsplitter to hit them throws them 1.6 times harder (capped,
  so no deadly launches) and uses up the mark.

### Starforged (celestial axe): area control and gravity

- **Astral Impact** (right-click, 16s): call a star down where you look (up to 24 blocks).
  A gold warning circle appears and closes in for 1.25s (never less than 0.5s). Then the star
  lands: 7 damage and a launch upwards for everyone within 4 blocks who is not behind cover.
- **Gravity Well** (sneak + right-click, 20s): a 6-block field opens where you look, with its
  edge drawn in particles. Players caught in it take 1 damage, are dragged towards the centre
  for 4s (they can still walk out slowly) and pulled down if they jump, then a burst throws
  everyone out (4 damage). While a star is falling no well can open, and the other way round,
  so nobody can be held under a strike.

## Fair in PvP

- Ability damage is dealt in the attacker's name like a sword hit. Armour reduces it, kills
  give kill credit (Lifesteal hearts, death messages "slain by Steve using Kurogane"), and
  the Combat plugin tags both players.
- **Protected areas are respected.** If a protection plugin cancels the hit (no-PvP regions,
  claims, spawn), the player is not pushed, slowed or pulled either. Players in creative or
  spectator mode, vanished staff, and everyone in a world with PvP off are never hit.
- Each ability hits a target once per use, and cooldowns belong to the weapon, so passing it
  to a friend, dropping it or reconnecting does not reset them.
- Right-click with food, potions, a bow, pearls and so on in the offhand uses that item and
  not the ability. A shield still works with abilities.
- Abilities also hit hostile mobs (`hit-mobs: hostile`, `all` or `none`).
- They work with the Vigil anti-cheat: ability hits are not melee attacks to it, and the
  knockback and launches are expected movement.

## Cannot be duplicated, stored or moved to alts

| Trying to... | What happens |
|---|---|
| Put it in a chest, ender chest, barrel, shulker box, hopper, dropper, dispenser, crafter, furnace, anvil, crafting grid, horse or minecart inventory, or a plugin menu | Refused: clicking, shift-clicking, number keys, the offhand key and dragging are all blocked |
| Put it in a bundle, item frame, armour stand, allay, decorated pot or shelf | Refused |
| Let a hopper or a mob (zombie, fox, allay...) pick it up | Refused |
| Craft with it (two swords repair into a plain one) | Refused |
| Sell or list it (`/sell`, `/ah sell`...) | Refused while carrying one (`blocked-commands`) |
| Pass it to an alt (an account that joined from the same IP) | The alt cannot pick it up. Staff are alerted |
| Duplicate it (a dupe glitch, creative middle-click, an edited item) | Each weapon has a unique id. When two copies can be seen at once, the one the registry does not expect is deleted, and staff are alerted |
| Stash it somewhere from before the plugin | Opening that container (or joining with it in the ender chest) moves it back to the player |
| Keep it through death | It drops on the ground even with keepInventory, never into grave plugins |
| Lose it in lava, fire, cactus, explosions or the void | Dropped legendaries cannot be destroyed, never despawn, glow, and are moved to spawn if they fall into the void |

Every weapon's location is kept in `data.yml` (held by whom, or where on the ground), and
everything that happens to it is written to `history.log`. When `one-of-each: true`, only one
of each weapon can exist. A weapon that disappears for good (for example `/clear`) is marked
lost after 5 seconds, and staff are told it can be given out again. If the old copy ever turns
up, it is deleted.

## Commands

| Command | Permission | |
|---|---|---|
| `/legendary` | everyone | How the weapons work |
| `/legendary give <player> <weapon>` | `legendary.give` | Give a legendary (refused if one already exists) |
| `/legendary remove <player> <weapon\|all>` | `legendary.remove` | Take it away. Works on offline players: it disappears when they join |
| `/legendary remove * <weapon\|all>` | `legendary.remove` | Remove it wherever it is |
| `/legendary list` | `legendary.list` | Every legendary and where it is |
| `/legendary inspect <player>` | `legendary.inspect` | A player's legendaries, cooldowns and previous holder |
| `/legendary reload` | `legendary.reload` | Reload `config.yml`. Weapons already out update their name and lore |

Weapons: `kurogane`, `sugarcrash`, `riftblade`, `gravebreaker`, `starforged`. The command is
also `/lw` and `/legendaries`.

Other permissions: `legendary.admin` (all of the above, op by default), `legendary.alerts`
(duplicate, storage and alt alerts, op), and `legendary.bypass.alts` (may take a legendary
from an account on the same IP, for siblings; nobody by default).

## Configuration

Every number above is in `config.yml`: cooldowns, damage, ranges, widths, speeds, knockback,
effect levels and durations, and warning times. There are also the names, lore (with
`{crescent-draw.cooldown}`-style placeholders so it always matches), enchantments,
`custom-model-data`, `item-model` (1.21.4+ resource packs), every sound (vanilla or resource
pack sounds such as a katana slash), and every message. A wrong value falls back to its
default and the console says what to fix.

## Building and testing

```bash
cd legendary
mvn -B package   # runs the tests, writes target/Legendary-<version>.jar
```

`mvn test` runs 37 tests on a simulated server:
- **Every weapon:** all five abilities, Edge stacking (including spam clicks and switching
  targets), the Executioner's Mark, cooldowns, and the Starforged lockout.
- **Protection:** protected, creative and PvP-off players.
- **Storage:** blocking for every container type, bundles, armour stands, pots, hoppers, mobs
  and crafting.
- **Duplicates:** copies, creative middle-click, revoked and lost weapons.
- **Ownership:** alt protection, death drops (keepInventory, grave plugins, cancelled
  deaths), and staff /invsee.
- **Other:** the registry across restarts, the action bar, commands and config checking.
