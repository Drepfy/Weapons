# Legendary

Five legendary netherite weapons for **ᴠᴀɴɪʟʟᴀ sᴍᴘ**, each with its own playstyle. Every
weapon is tracked one by one, so it cannot be duplicated, stored away, or passed between alt
accounts. For Paper 1.21 and newer.

**Controls:** **F** (the swap-offhand key) uses a weapon's first ability and **Shift + F** its
second; the weapon stays in your hand. `controls: right-click` in `config.yml` switches back to
right-click and sneak + right-click (or `both`), and the lore always shows the right keys.

**Cooldowns are boss bars.** While you hold a legendary, a boss bar for each of its abilities
sits at the top of the screen, in the weapon's colour: just the ability's name when it is
ready (a full bar), `Crimson Flash » 11s` while it recharges (the bar fills up), and the time
left while it lasts or can be pressed again, such as Crimson Flash's second dash or Rift Swap's
echo (the bar runs down). Nothing is said in chat or above
the hotbar when you press too early: the bar flashes white. The action bar is left to the
Combat plugin's timer. `display.boss-bars: false` turns them off.

![The five tooltips and boss bars](../release/Weapons-Lore.png)

## The weapons

The swords (Kurogane, Sugarcrash, Riftblade) have **Sharpness VII, Fire Aspect II, Looting III
and Sweeping Edge III**; the axes (Gravebreaker, Starforged) **Sharpness VII, Efficiency V and
Fortune III**. All five are unbreakable netherite with the enchantment shimmer, a custom name and
short lore: the passive, and each ability with its key and cooldown. The enchantments are listed
under the name like on any enchanted item (a `{enchantments}` line in the lore lists them there
in the weapon's own colours instead).

They look like 1.21.11 items: with the server resource pack each has its own 3D model
(`item-model`) and its own tooltip frame and background in its colours (`tooltip-style`,
1.21.2+). Custom model data (1001 to 1005) is still set for older clients. `glint: false`
turns the shimmer off.

Every ability has its own 3D effect from the pack (crimson slashes, candy rings, a void rift, a
shockwave, gravestones, a rune circle, falling stars, a black hole...), shown with display
entities: the server says where an effect starts and ends, and the players' game animates it
smoothly in between. Effects are never saved with the world. Every ability, passive and hit has
its own sound from the pack (with a quiet vanilla sound under the ability sounds for players
without the pack), each weapon has its own sound when you take it in hand, and its sword or axe
hits have their own hit sound.

Every weapon has a **passive** that works on its own, and two abilities. Several abilities can
be **pressed again** while they are still going: a second dash, a return, a dive. The boss bar
shows how long you have to press again.

### Kurogane (katana): precision, counters and bleeding

- **Crimson Edge** (passive): every 3rd sword hit in a row on the same target cuts deep: +4
  damage and bleeding (1 a second for 3 seconds). Spam clicks do not count.
- **Crimson Flash** (F, 16s): an iaido dash. You vanish in a crimson streak and reappear up to
  8 blocks ahead (stopping before walls, never inside them). A moment later everyone you passed
  through is cut for 6 damage and bleeds. **Two charges:** press F again within 3 seconds to
  dash a second time.
- **Iaido** (Shift + F, 22s): a counter stance for 1.5 seconds. The first attack on you (a
  hit or an arrow) is blocked, and you vanish and reappear behind the attacker with a 9 damage
  cut that makes them bleed and heals you 2 hearts. If nothing comes, the stance is released as
  a crimson crescent in front of you (6 damage).

### Sugarcrash (candy scythe): reach, mobility and burst tempo

- **Sugar High** (passive): each scythe hit adds a sugar stack (up to 5) and speeds you up
  (Speed I, then Speed II). At full stacks the next hit is a **Sugar Crash**: +4 damage, and a
  candy blast hurts (3) and throws everyone round the target.
- **Candy Hook** (F, 14s): throws a candy-cane hook on a candy rope (22 blocks). A player or
  monster it catches takes 3 damage, is yanked to you and stunned for a moment (too slow to
  walk away). A wall or the ground it catches pulls you to it instead, with no fall damage.
- **Candy Cyclone** (Shift + F, 28s): you become a candy-striped tornado for 4 seconds and can
  keep moving (with Speed II). Every 0.4s everyone within 4.5 blocks takes 1.5 damage and is
  dragged in, and arrows bounce off you. Then it bursts: 4 damage and everyone is thrown out.

### Riftblade (void sword): space and positioning

- **Phase Shift** (passive): while you hold the Riftblade, an attack on you has a 20% chance
  to pass straight through, and you slip 3 blocks aside through a small rift (at most once
  every 10 seconds).
- **Void Rend** (F, 20s): tears a rift open 5 blocks ahead (or at a wall). For 1.25 seconds it
  drags everyone within 5.5 blocks towards it, then snaps shut: 7 damage, 3 seconds of
  Darkness, and a second of Levitation (lifted helplessly) for everyone within 3 blocks.
- **Rift Swap** (Shift + F, 28s): swap places with the first player (or monster) you look at
  within 20 blocks: they take 3 damage and Nausea. With nobody in sight, you blink 10 blocks
  forward instead. A **void echo** stays where you were for 4 seconds: press Shift + F again
  to go back to it. It is a normal teleport, so safe zones and region plugins can refuse it;
  then nothing happens and the cooldown is not spent.

### Gravebreaker (battle axe): ground control and finishing blows

- **Last Rites** (passive): axe hits on players below 40% health do 25% more damage, and
  killing a player with the axe in hand makes Executioner's Leap ready again.
- **Executioner's Leap** (F, 18s): leap high and forward, then slam the ground where you land
  (no fall damage from it). Everyone within 5 blocks takes 8 damage at the centre down to 4 at
  the edge, is thrown up and gets Slowness II. **Press F again in the air to dive** at where
  you look (up to 18 blocks): a 30% bigger slam with 3 more damage. Rocks fly out of the crater,
  but no block is ever changed.
- **Grave Rise** (Shift + F, 28s): six gravestones burst out of the ground one after another
  in a 12-block line. Whoever stands on one takes 6 damage, is launched, and gets Slowness III
  and Mining Fatigue II. The last one bursts as a tomb: 5 damage to everyone within 3 blocks.
  The line climbs single steps and stops at walls and drops.

### Starforged (celestial axe): area control and gravity

- **Starstruck** (passive): every 4th axe hit in a row on the same target calls a small star
  down on it half a second later: 4 damage round it and a launch.
- **Starfall** (F, 24s): a rune circle opens where you look (up to 28 blocks), then seven
  stars rain down inside it one after another. **The circle follows your aim** while the stars
  fall, so it can chase whoever runs. Each star hits everyone within 2 blocks for 4 damage and
  launches them; one player is hit by at most 3 stars.
- **Singularity** (Shift + F, 35s): a black hole opens where you look. For 3 seconds it drags
  everyone within 8 blocks towards its heart (the first touch does 1 damage), then collapses
  into a nova: 7 damage, everyone thrown away and slowed.
- While stars are falling no black hole can open and the other way round (plus 1.5s), so
  nobody can be held in place under the stars.

## Fair in PvP

- Ability damage is dealt in the attacker's name like a sword hit. Armour reduces it, kills
  give kill credit (Lifesteal hearts, death messages "slain by Steve using Kurogane"), and
  the Combat plugin tags both players.
- **Protected areas are respected.** If a protection plugin cancels the hit (no-PvP regions,
  claims, spawn), the player is not pushed, slowed, pulled or swapped either. Players in
  creative or spectator mode, vanished staff, and everyone in a world with PvP off are never hit.
- Each ability hits a target a set number of times per use (once for most; the Candy Cyclone
  every half second, at most 3 stars of a Starfall), and cooldowns belong to the weapon, so
  passing it to a friend, dropping it or reconnecting does not reset them.
- The dashes and swaps never put anyone inside a wall: they stop at the last open spot.
- With `controls: right-click`, right-click with food, potions, a bow, pearls and so on in the
  offhand uses that item and not the ability. A shield still works with abilities.
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
| Sell or list it (`/sell`, `/ah sell`...) | Refused while it is in your hand; `/sellall` is refused while you carry one at all (`blocked-commands`). Other items can still be sold |
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
`{crimson-flash.cooldown}`-style placeholders so it always matches, and `{enchantments}` for the
enchantment lines), enchantments, the boss bar colour and text colour of each weapon,
`custom-model-data`, `item-model`, `tooltip-style` and `glint`, every sound, and every message.
A wrong value falls back to its default and the console says what to fix.

**Without the resource pack**, the tooltip styles show as a missing texture. Either make the
pack required (`require-resource-pack=true` in `server.properties`) or set `tooltip-style: ""`
for each weapon.

**Updating from an older version:** nothing to do. On start the settings of abilities that
were replaced (Blood Moon, Sugar Rush, and everything from 1.1 and older), the action bar
settings and messages that no longer exist are removed. Settings, lore and names still at an
older version's default (for example Crimson Flash's 20s cooldown from 1.2, or 1.3.0's lore
and shimmer setting) become the new defaults, and enchantments still at the old Sharpness VI
become the new ones. Anything you changed yourself is kept. Everything is saved in `config.yml`.

## Robust on a live server

Each part runs on its own every tick (effects, abilities, boss bars, the duplicate tracking):
an error in one is logged once a minute and never stops the others. A player killed by an
ability (bleeding out, a slam) is let go of between ticks, so nothing is changed while an
ability is still working through its targets. Asking protection plugins whether a pull or a
swap is allowed uses the newest event Paper has (the older ones are marked for removal), and
if it cannot ask, the answer is no.

## Building and testing

```bash
cd legendary
mvn -B package   # runs the tests, writes target/Legendary-<version>.jar
```

`mvn test` runs 58 tests on a simulated server:
- **Every ability and passive:** what it hits and when (Crimson Flash's two charges and
  bleeding, Iaido's counter and crescent, Crimson Edge's count, the hook's yank, stun and
  grapple, the cyclone deflecting arrows, Sugar High's crash, the rift's pull, snap and lift,
  Rift Swap's echo and blink, Phase Shift, the leap, dive and tomb, Last Rites, the star warning,
  hit cap and the circle following your aim, the black hole and nova, Starstruck), walls,
  cooldowns and the Starforged lockout.
- **Protection:** protected players are never hurt, pulled, yanked or swapped; creative and PvP-off;
  counters and dodges ignore protection checks; ability kills (two players bleeding out on the
  same tick) break nothing.
- **Look:** enchantments listed by the game, the shimmer, item model, tooltip style, the lore's
  own enchantment lines when asked for,
  the boss bars (names, times, progress, flashing, hidden when put away), and the 3D effects
  being cleaned up.
- **Storage:** blocking for every container type, bundles, armour stands, pots, hoppers, mobs
  and crafting.
- **Duplicates:** copies (which cannot use abilities), creative middle-click, revoked and lost
  weapons.
- **Ownership:** alt protection, death drops (keepInventory, grave plugins, cancelled
  deaths), and staff /invsee.
- **Controls:** F and Shift + F (the weapon stays in hand), the right-click and both settings,
  offhand food and shields, and the keys shown in the lore.
- **Other:** the registry across restarts, commands, config checking, upgrading 1.1 and 1.2
  configs, and updating old default texts while keeping your own.
