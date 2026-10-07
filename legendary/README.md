# Legendary

Five legendary netherite weapons for **ᴠᴀɴɪʟʟᴀ sᴍᴘ**, each with its own playstyle. Every
weapon is tracked one by one, so it cannot be duplicated, stored away, or passed between alt
accounts. For Paper 1.21 and newer.

**Controls:** **F** (the swap-offhand key) uses a weapon's first ability and **Shift + F** its
second; the weapon stays in your hand. `controls: right-click` in `config.yml` switches back to
right-click and sneak + right-click (or `both`), and the lore always shows the right keys.

**Cooldowns are boss bars.** When you use an ability, a boss bar for it appears at the top of
the screen in the weapon's colour: `Phantom Step » 16s` while it recharges (the bar fills up),
and the time left while it lasts, such as Crimson Tempest or Iron Bastion (the bar runs down). When the ability is ready again its bar goes away, so
a weapon with everything ready shows no bars. **The bars stay when you switch to another item**,
as long as the legendary is in your inventory, so you can eat, pearl or block and still see
when it is ready. Nothing is said in chat or above the hotbar when you press too early: the bar
flashes white. The action bar is left to the Combat plugin's
timer. `display.boss-bars-when-ready: true` also shows the ready ones of the legendary in your hand
(just their name, a full bar), and `display.boss-bars: false` turns them off.

**Ability damage is true damage** (`true-damage: true`): it goes straight through armour,
Protection and shields, so the hearts below are the hearts the target loses, whatever they
wear. An ability hit takes between 2.5 and 6 hearts. Resistance, absorption hearts and totems
still work.

**Legendary hits hit harder.** Every sword and axe hit with a legendary does **1.25x** its
normal damage (`melee-damage: 1.25`; `1` = like any netherite weapon). Armour still counts for
these, as for any hit.

![The five tooltips and boss bars](../release/Weapons-Lore.png)

## The weapons

The swords (Kurogane, Sugarcrash, Wyrmfang) have **Sharpness VII, Fire Aspect II, Looting III
and Sweeping Edge III**; the axes (Gravebreaker, Starforged) **Sharpness VII, Efficiency V and
Fortune III**. All five are unbreakable netherite that always shimmer (like the Lifesteal Heart),
with a custom name and short lore: the passive, each ability with its key and cooldown, and the
real attack damage. The enchantments are listed under the name like on any enchanted item (a
`{enchantments}` line in the lore lists them there in the weapon's own colours instead).

**The lore shows the real damage.** Sharpness VII adds 4 damage (0.5 per level plus 0.5), but
the vanilla tooltip leaves enchantments out and says "8 Attack Damage" for any netherite sword.
The lore's `{attack}` line shows the real numbers in the same place and style instead, the
legendary bonus included: **15 Attack Damage** for the swords and **17.5** for the axes. The
server's difficulty does not change player-versus-player damage at all; it only changes how
hard mobs hit players.

They look like 1.21.11 items: with the server resource pack each has its own 3D model
(`item-model`) and its own tooltip frame and background in its colours (`tooltip-style`,
1.21.2+). Custom model data (1001 to 1005) is still set for older clients. `glint: false`
turns the shimmer off for good.

Every ability has its own 3D effect from the pack (crimson slashes and a rune circle, the thrown
scythe itself spinning through the air, candy rings, a jade crescent, dragon claw marks and
green dragon fire, rock bursting out of the ground, a shockwave, a ring of stars, a star lance
and its nova...), shown with display entities: the server says where an effect starts and ends,
and the players' game animates it smoothly in between. They are big, glow and stay a moment so
they are easy to see in a fight, and the ones that show an area (the prison's ring, the
bastion's shockwave) match it exactly. Effects are never saved with the world, and no block is
ever changed.

**Sounds are kept few and clean**, the way Altar does it: one vanilla Minecraft sound when an
ability is used and one for its big moment (an ender dragon's wings for Wyrm Lunge, the mace's
ground smash for Earthsplitter, a breeze's wind burst for Sugar Rush...), and nothing on
ordinary hits, bleeding, poison or taking a weapon in hand, where sword and axe hits keep the
game's own sounds. Everyone hears them, pack or not. Every sound can be changed (or silenced
with `[]`) in `config.yml`.

Every weapon has a **passive** that works on its own, and two abilities. **Sugar Rush** and
**Iron Bastion** can be pressed again while they last to end them straight away (the burst or
the shockwave happens then). Hearts below: 2 damage = 1 heart.

### Kurogane (katana): speed, bleeding and a storm of cuts

- **Crimson Hunger** (passive): a sword hit has a 25% chance to make the target bleed (1.25
  damage a second for 3 seconds), and every hit on someone who is already bleeding heals you
  half a heart.
- **Phantom Step** (F, 21s): you vanish and reappear up to 10 blocks ahead (stopping before
  walls, never inside them), with Speed III for 3 seconds. A moment later everyone you passed
  through is cut for 7.5 damage and bleeds (1.25 a second for 3 seconds).
- **Crimson Tempest** (Shift + F, 27s): for 5 seconds (with Speed II) every sword swing also
  sends a crimson crescent 7 blocks forward, at most one every half second: 5 damage to
  everyone in its path.

### Sugarcrash (candy scythe): reach, speed and a scythe that comes back

- **Sugar High** (passive): **Speed I for as long as the scythe is in your hand** (gone as soon
  as you put it away; `speed-level`), and every scythe hit slows the target (Slowness I for 1.5
  seconds).
- **Candy Reaper** (F, 19s): you throw the scythe spinning where you look. It flies up to 18
  blocks (turning back at walls) and comes back to you, cutting everyone it passes for 6.25
  damage on the way out **and again on the way back**, and the return cut drags them towards
  you.
- **Sugar Rush** (Shift + F, 33s): for 3 seconds you charge through the air where you look
  (steer with your aim) as a whirl of candy, and arrows bounce off you. Everyone you ram takes
  6.25 damage once and is thrown aside. It ends in a candy blast: 5 damage to everyone within 4
  blocks, thrown out. No fall damage after.

### Wyrmfang (dragon greatsword): venom and dragon fire

The Wyrmfang took the Riftblade's place in 2.0 (see Updating below).

- **Venom Fang** (passive): every sword hit poisons the target (Poison II for 2 seconds).
- **Wyrm Lunge** (F, 25s): a low leap forward on dragon wings (no fall damage). The first enemy
  you reach in the next 1.25 seconds is **seized**: 10 damage, thrown up and Slowness III for 2
  seconds. Once you land, the lunge is over.
- **Dragon's Breath** (Shift + F, 33s): you breathe green dragon fire where you look for 2
  seconds, 8 blocks long and 35 degrees either side of your aim. Every quarter second everyone
  in it takes 1.25 damage (10 if they stay in it the whole time) and Poison II for 3 seconds.
  It does not go through walls.

### Gravebreaker (battle axe): the ground itself, and an iron will

- **Headsman** (passive): axe hits on players below 40% health do 30% more damage, and killing a
  player with the axe in hand gives you Regeneration II for 4 seconds.
- **Earthsplitter** (F, 23s): the axe splits the ground and a fissure tears 14 blocks forward,
  rock bursting up along it. Everyone within 1.6 blocks of it takes 10 damage (once), is thrown
  up and gets Slowness II for 2 seconds. It climbs single steps and stops at walls and drops.
  The rocks are only shown: no block is ever changed.
- **Iron Bastion** (Shift + F, 33s): brace for 4 seconds: Resistance III and **no knockback**.
  The damage you take while braced is stored (up to 6.25). Then (or when you press again) it is
  released as a shockwave: 6.25 damage plus what you stored (up to 12.5) to everyone within 6
  blocks, all thrown away.

### Starforged (celestial axe): a lance from the sky, and a cage of stars

- **Starlight** (passive): axe hits on an enemy in the air do 30% more damage.
- **Star Lance** (F, 29s): you hurl a star where you look (30 blocks; it bursts on walls). The
  first enemy it reaches takes 11.25 damage, is pinned in place, gets Slowness IV for 2
  seconds and **glows for 5 seconds** (seen through walls).
- **Celestial Prison** (Shift + F, 40s): a ring of stars closes round you. Everyone within 6
  blocks is trapped for 3 seconds: walking out pushes them back in, and Ender Pearls and chorus
  fruit cannot take them out. Then the stars collapse on the middle: 12.5 damage and a launch
  for everyone still inside.

## Fair in PvP

- Ability damage is dealt in the attacker's name. It is true damage (through armour), and
  kills give kill credit (Lifesteal hearts, death messages "killed by Steve using Kurogane"),
  and the Combat plugin tags both players. With `true-damage: false` armour reduces it like a
  sword hit.
- **Protected areas are respected.** If a protection plugin cancels the hit (no-PvP regions,
  claims, spawn), the player is not pushed, slowed, pulled or swapped either. Players in
  creative or spectator mode, vanished staff, and everyone in a world with PvP off are never hit.
  A protected player is not trapped by Celestial Prison either, and Wyrm Lunge and Star Lance
  go past them to the next enemy.
- Each ability hits a target a set number of times per use (once for most; Candy Reaper once
  going out and once coming back, Crimson Tempest once per crescent, Dragon's Breath once per
  gust), and cooldowns belong to the weapon, so passing it to a friend, dropping it or
  reconnecting does not reset them.
- Phantom Step never puts you inside a wall: it stops at the last open spot.
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
| Sell or list it (`/sell`, `/ah sell`, `/ah list`, `/auction list`...) | Refused while it is in your hand; `/sellall` is refused while you carry one at all (`blocked-commands`). Other items can still be sold |
| Pass it to an alt (an account that joined from the same IP) | The alt cannot pick it up. Staff are alerted |
| Duplicate it (a dupe glitch, creative middle-click, an edited item) | Each weapon has a unique id. When two copies can be seen at once, the one the registry does not expect is deleted, and staff are alerted |
| Stash it somewhere from before the plugin | Opening that container (or joining with it in the ender chest) moves it back to the player |
| Keep it through death | It drops on the ground even with keepInventory, never into grave plugins |
| Lose it in lava, fire, cactus, explosions or the void | Dropped legendaries cannot be destroyed, never despawn, glow, and are moved to spawn if they fall into the void |

Every weapon's location is kept in `data.yml` (held by whom, or where on the ground), and
everything that happens to it is written to `history.log`. When `one-of-each: true`, only one
of each weapon can exist. A weapon that disappears (for example `/clear`) stays registered to
its last holder until staff remove it with `/legendary remove`, so there are no "marked as
lost" alerts. With `mark-lost: true` it is marked lost after 5 seconds instead, and staff are
told it can be given out again. Either way, if an old copy ever turns up after it was replaced,
it is deleted.

## Commands

| Command | Permission | |
|---|---|---|
| `/legendary` | everyone | How the weapons work, and the commands you may use (click one to type it) |
| `/legendary give <player> <weapon>` | `legendary.give` | Give a legendary (refused if one already exists) |
| `/legendary remove <player> <weapon\|all>` | `legendary.remove` | Take it away. Works on offline players: it disappears when they join |
| `/legendary remove * <weapon\|all>` | `legendary.remove` | Remove it wherever it is |
| `/legendary list` | `legendary.list` | Every legendary and where it is |
| `/legendary inspect <player>` | `legendary.inspect` | A player's legendaries, cooldowns and previous holder |
| `/legendary reload` | `legendary.reload` | Reload `config.yml`. Weapons already out update their name and lore |

Weapons: `kurogane`, `sugarcrash`, `wyrmfang`, `gravebreaker`, `starforged` (`riftblade`
still works and means the Wyrmfang). The command is also `/lw` and `/legendaries`.

Other permissions: `legendary.admin` (all of the above, op by default), `legendary.alerts`
(duplicate, storage and alt alerts, op), and `legendary.bypass.alts` (may take a legendary
from an account on the same IP, for siblings; nobody by default).

## Configuration

Every number above is in `config.yml`: cooldowns, damage, ranges, widths, speeds, knockback,
effect levels and durations, and the legendary hit bonus (`melee-damage`). There are also the
names, lore (with `{phantom-step.cooldown}`-style placeholders so it always matches, `{enchantments}` for the
enchantment lines and `{attack}` for the attack damage lines), enchantments, the boss bar colour and text colour of each weapon,
`custom-model-data`, `item-model`, `tooltip-style` and `glint`, every sound, and every message.
A wrong value falls back to its default and the console says what to fix.

**Without the resource pack**, the tooltip styles show as a missing texture. Either make the
pack required (`require-resource-pack=true` in `server.properties`) or set `tooltip-style: ""`
for each weapon.

**Updating from an older version:** nothing to do. On the first start of 2.0:

- **Every Riftblade becomes a Wyrmfang**: the same weapon (same id, same holder) in `data.yml`,
  with the Wyrmfang's abilities straight away. It takes the new name, lore and model as soon as
  it is in a player's inventory (when its holder joins, or when one lying on the ground is
  picked up). The `weapons.riftblade` section is removed from `config.yml` and
  `weapons.wyrmfang` is written in.
- Every weapon's `abilities` section is replaced by the 2.0 abilities with their defaults and
  explanations (the old abilities are gone, so their settings mean nothing any more).
- Lore that names an old ability goes back to the new default. Lore you wrote yourself
  without ability placeholders is kept, as are your names, enchantments, colours and models.
- The sounds and messages of the old abilities are removed and the new sounds are added;
  `melee-damage: 1.25` is added. Your own `received` sound (getting a legendary) is kept.

Older configs (1.0 to 1.5) are brought up to date the same way: the action bar settings are
removed, texts still at an old default become the new ones and enchantments still at the old
Sharpness VI become the new ones. Everything is saved in `config.yml`.

## Robust on a live server

Each part runs on its own every tick (effects, abilities, boss bars, the duplicate tracking):
an error in one is logged once a minute and never stops the others. A player killed by an
ability (bleeding out, a shockwave) is let go of between ticks, so nothing is changed while an
ability is still working through its targets. Asking protection plugins whether a pull or a
swap is allowed uses the newest event Paper has (the older ones are marked for removal), and
if it cannot ask, the answer is no.

## Building and testing

```bash
cd legendary
mvn -B package   # runs the tests, writes target/Legendary-<version>.jar
```

`mvn test` runs 68 tests on a simulated server:
- **Every ability and passive:** what it hits, how hard and when: Phantom Step's cut, bleeding,
  Speed and walls; Crimson Tempest's crescents, their half-second gap and walls (and a hit
  another plugin cancels changing nothing later); Crimson Hunger's
  bleeding and healing; Candy Reaper's two cuts and the pull on the way back, and turning back
  at walls; Sugar Rush's ram (once each), bouncing arrows, burst and early burst; Sugar High's
  slow and Speed; Wyrm Lunge's seize and its end on landing; Dragon's Breath's cone, gusts,
  poison and walls; Venom Fang; Earthsplitter's line, launch and walls (no block changed); Iron
  Bastion's stored damage, cap, early release and knockback immunity; Headsman; Star Lance's
  first target, pin, glow, walls and range; Celestial Prison holding players in (walking and
  pearls) and its collapse; Starlight; and every cooldown.
- **The Riftblade becoming the Wyrmfang** (the item, `data.yml` and the old name in commands),
  and **1.25x legendary hits** (and `melee-damage: 1`).
- **Protection:** protected players are never hurt, pulled, rammed or trapped, and the lunge
  and lance go past them; creative and PvP-off; protection checks are not counted as hits;
  ability kills (two players bleeding out on the same tick) break nothing.
- **Look:** enchantments listed by the game, the shimmer, item model, tooltip style, the real
  attack damage lines (15 and 17.5), the lore's own enchantment lines when asked for, the boss
  bars (only while recharging or running, names, times, progress, flashing, staying while the
  weapon is carried), and the 3D effects being cleaned up.
- **True damage:** Protection made up for so the damage is exact.
- **Sounds:** every sound is a real vanilla sound.
- **Storage:** blocking for every container type, bundles, armour stands, pots, hoppers, mobs
  and crafting.
- **Duplicates:** copies (which cannot use abilities), creative middle-click, revoked weapons,
  and lost ones (only with `mark-lost: true`).
- **Ownership:** alt protection, death drops (keepInventory, grave plugins, cancelled
  deaths), and staff /invsee.
- **Controls:** F and Shift + F (the weapon stays in hand), the right-click and both settings,
  offhand food and shields, and the keys shown in the lore.
- **Other:** the registry across restarts, commands, config checking, upgrading configs from
  1.0 to 1.6 (1.6 to 2.0 in full: the Wyrmfang, new abilities, sounds, lore and comments), and
  updating old default texts while keeping your own.
