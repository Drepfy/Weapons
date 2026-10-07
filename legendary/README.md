# Legendary

Four legendary netherite weapons for **ᴠᴀɴɪʟʟᴀ sᴍᴘ**, made for PvP: the **Katana**, the
**Candy Cane**, **Crush** and the **Reaper**. Each has one ability and one passive, and each has
a clear job in a fight. Every weapon is tracked one by one, so it cannot be duplicated, stored
away, or passed between alt accounts. For Paper 1.21 and newer (built and checked against
1.21.11).

**Controls:** **Shift + F** (sneak and press the swap-offhand key) uses the weapon's ability;
the weapon stays in your hand. Plain **F** swaps hands as usual. `controls: right-click` in
`config.yml` switches to sneak + right-click (or `both`), and the lore always shows the right
key. The passive works on its own.

**Timing, not luck.** Draw, Crush and Reap wait for your **next full-strength hit on a player**
(the attack bar at 90% or more), so spam-clicking does not use them up and a missed swing does
not waste them; they are used up only when they land. Heavy's knock-down needs a full-strength
hit too. `full-strength-hits: false` lets any hit count.

**Players only.** The abilities and passives only ever affect other players: mobs, animals and
the holder themselves are never touched.

**Cooldowns are boss bars.** When you use an ability, a boss bar in the weapon's colour shows
how long it waits for your hit (`Draw » 2.4s`, running down) and then how long until it can be
used again (`Draw » 21s`, filling up). When it is ready the bar goes away. **The bars stay when
you switch to another item**, as long as the legendary is in your inventory. Pressing too early
says nothing in chat: the bar flashes white. `display.boss-bars-when-ready: true` also shows the
ready ones (just the name, a full bar), and `display.boss-bars: false` turns them off.

**Extra damage is true damage** (`true-damage: true`): the extra damage of the abilities and
passives goes straight through armour and Protection, so the hearts below are the hearts the
target loses. It never knocks back and never gets in the way of your next sword hit (no extra
invulnerability). Resistance, absorption hearts and totems still work. Nothing kills outright.

**Legendary hits hit harder.** Every sword and axe hit with a legendary does **1.25x** its
normal damage (`melee-damage: 1.25`; `1` = like any netherite weapon). Armour still counts for
these, as for any hit.

![The four tooltips and boss bars](../release/Weapons-Lore.png)

## The weapons

The swords (Katana, Candy Cane, Reaper) have **Sharpness VII, Fire Aspect II, Looting III and
Sweeping Edge III**; Crush (the axe) has **Sharpness VII, Efficiency V and Fortune III**. All
four are unbreakable netherite that always shimmer, with a custom name and short lore: the
passive, the ability with its key and cooldown, and the real attack damage: **15 Attack
Damage** for the swords and **17.5** for the axe (the vanilla tooltip leaves Sharpness out, so
the lore's `{attack}` line shows the real numbers instead).

With the server resource pack each has its own 3D model and its own tooltip frame in its
colours, and every ability has its own glowing 3D effect (shown with display entities, never
saved with the world; no block is ever changed). Sounds are few: one vanilla sound when an
ability is used and one when it lands, nothing on ordinary hits.

Hearts below: 1 heart = 2 health.

### Katana: precision and bleeding

- **Bleed** (passive): every hit on a player makes them bleed for **4 seconds**, losing **half
  a heart every second**. Hitting them again starts the 4 seconds over; it **never stacks**.
- **Draw** (Shift + F, **25s**): for 4 seconds the blade is half drawn (a crimson sigil follows
  you). Your next full-strength hit on a player cuts deep for extra damage based on **the
  health they have left**: 30% of it, at least 1 and at most 3.5 hearts (3 hearts on a player
  at full health, 1.8 on one at 6 hearts). Used up only when it lands.

*Its job:* steady pressure. Keep them bleeding, then open the fight with a Draw while they are
still healthy.

### Candy Cane: control the ground

- **Sticky Sweet** (passive): each hit has a **25%** chance to give **Slowness I for 1.5
  seconds**. The chance is the same on every hit (it does not build up), and the same player
  cannot be slowed by it again for 3 seconds, so it can never keep someone slowed.
- **Sugar Trap** (Shift + F, **25s**): up to **5 sugar traps** pop out round you (2.5 blocks
  away, only where there is ground and no wall in between) and wait for **8 seconds**. An enemy
  who walks over one gets **Poison I for 4 seconds, Nausea for 4 seconds and Slowness I for 1
  second**, and that trap is gone. **Your own traps never catch you.** With no room for any
  trap, nothing is used.

*Its job:* zoning. Drop traps where they must walk (a doorway, a bridge, the spot they run to)
and fight on your ground.

### Crush: catch them in the air

- **Heavy** (passive): axe hits knock players back **30% further**. A full-strength hit on a
  player in the air (half a block or more above the ground) **knocks them down** and does
  **0.75 hearts** more.
- **Crush** (Shift + F, **28s**): for 10 seconds your next full-strength axe hit on a player is
  an impact: they are **slammed into the ground** and take **0.5 hearts plus 0.75 for every
  block they were above it**, at most 4 hearts (2.75 hearts from 3 blocks up). The ground
  cracks where they land (only shown). Used up only when it lands.

*Its job:* timing. Catch players jumping, falling or pearling in and punish them for leaving
the ground.

### Reaper: the finisher

- **Execution** (passive): hits on a player **below 6 hearts** do **0.75 hearts** more. No
  build-up, no stacking.
- **Reap** (Shift + F, **30s**): for 8 seconds your next full-strength hit on a player **below
  8 hearts** reaps them for **3.5 hearts** more (Execution adds its own on top). Hits on
  healthier players **do not use it up**: it waits for a target that is low enough, or until
  it runs out. It is ordinary damage: it never kills outright, and totems still save.

*Its job:* closing out a fight that is already going your way.

**What the weapons never do:** no gambling or luck-based abilities, no combos or stacks that
build up from hitting the same player again and again, no turning off or copying another
weapon's ability, nothing that finds bases or builds, and no generic dash, lightning or
area-blast abilities.

## Fair in PvP

- Ability damage is dealt in the attacker's name and gives kill credit (Lifesteal hearts,
  death messages "killed by Steve using Katana"), and the Combat plugin tags both players. With
  `true-damage: false` armour reduces it like a sword hit.
- **Protected areas are respected.** If a protection plugin would cancel the hit (no-PvP
  regions, claims, spawn), the player is not hurt, slowed, slammed or trapped either. Players
  in creative or spectator mode, vanished staff, and everyone in a world with PvP off are left
  alone. A sugar trap ignores a protected player and waits for the next enemy.
- Bleed and Sticky Sweet never stack, Draw, Crush and Reap are used once per use, and a trap
  catches one player once. Cooldowns belong to the weapon, so passing it to a friend, dropping
  it or reconnecting does not reset them.
- With `controls: right-click`, right-click with food, potions, a bow, pearls and so on in the
  offhand uses that item and not the ability.
- They work with the Vigil anti-cheat: extra damage is not a melee attack to it, and Crush's
  slam and Heavy's knockback are expected movement.

## Cannot be duplicated, stored or moved to alts

| Trying to... | What happens |
|---|---|
| Put it in a chest, ender chest, barrel, shulker box, hopper, dropper, dispenser, crafter, furnace, anvil, crafting grid, horse or minecart inventory, or a plugin menu | Refused: clicking, shift-clicking, number keys, the offhand key and dragging are all blocked |
| Put it in a bundle, item frame, armour stand, allay, decorated pot or shelf | Refused |
| Let a hopper or a mob (zombie, fox, allay...) pick it up | Refused |
| Craft with it (two swords repair into a plain one) | Refused |
| Sell or list it (`/sell`, `/ah sell`, `/ah list`, `/auction list`...) | Refused while it is in your hand; `/sellall` is refused while you carry one at all (`blocked-commands`). Other items can still be sold |
| Pass it to an alt (an account that joined from the same IP) | The alt cannot pick it up. Staff are alerted |
| Duplicate it (a dupe glitch, creative middle-click, an edited item) | Each weapon has a unique id. When two copies can be seen at once, the one the registry does not expect is deleted, and staff are alerted. A copy can never use the ability |
| Stash it somewhere from before the plugin | Opening that container (or joining with it in the ender chest) moves it back to the player |
| Keep it through death | It drops on the ground even with keepInventory, never into grave plugins |
| Lose it in lava, fire, cactus, explosions or the void | Dropped legendaries cannot be destroyed, never despawn, glow, and are moved to spawn if they fall into the void |

Every weapon's location is kept in `data.yml` (held by whom, or where on the ground), and
everything that happens to it is written to `history.log`. When `one-of-each: true`, only one
of each weapon can exist. A weapon that disappears (for example `/clear`) stays registered to
its last holder until staff remove it with `/legendary remove`. With `mark-lost: true` it is
marked lost after 5 seconds instead, and staff are told it can be given out again. Either way,
if an old copy ever turns up after it was replaced, it is deleted.

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

Weapons: `katana`, `candycane`, `crush`, `reaper` (the old names still work and mean the
weapon they became: `kurogane`, `sugarcrash`, `gravebreaker`, `wyrmfang`, `riftblade`). The
command is also `/lw` and `/legendaries`.

Other permissions: `legendary.admin` (all of the above, op by default), `legendary.alerts`
(duplicate, storage and alt alerts, op), and `legendary.bypass.alts` (may take a legendary
from an account on the same IP, for siblings; nobody by default).

## Configuration

Every number above is in `config.yml`, in hearts and seconds: cooldowns, windows, damage,
chances, effect levels and durations, the number of traps and how far out they go, Heavy's
knockback, Crush's slam, and the legendary hit bonus (`melee-damage`). There are also the
names, lore (with `{draw.cooldown}`-style placeholders so it always matches, and `{attack}`
for the attack damage lines), enchantments, the boss bar colour and text colour of each
weapon, `custom-model-data`, `item-model`, `tooltip-style` and `glint`, every sound, and every
message. A wrong value falls back to its default and the console says what to fix.

**Without the resource pack**, the tooltip styles show as a missing texture. Either make the
pack required (`require-resource-pack=true` in `server.properties`) or set `tooltip-style: ""`
for each weapon.

**Updating from 2.0 or older:** on the first start of 3.0:

- **The old weapons become the new ones**: the Kurogane becomes the **Katana**, the Sugarcrash
  the **Candy Cane**, the Gravebreaker **Crush**, and the Wyrmfang (and any Riftblade) the
  **Reaper**. It is the same weapon (same id, same holder) in `data.yml`, with the new ability
  straight away. It takes the new name, lore and 3D model as soon as it is in a player's
  inventory (when its holder joins, or when one lying on the ground is picked up).
- **The Starforged no longer exists.** It is removed from `data.yml`, and a Starforged item
  that turns up is taken from whoever has it, with a message telling them to ask staff for a
  new legendary; staff are alerted.
- The `weapons` section of `config.yml` is replaced by the four 3.0 weapons with their
  defaults and explanations (the old weapons and abilities are gone, so their settings mean
  nothing any more). If you had changed names, lore or enchantments, set them again.
- `hit-mobs` is removed (the weapons only affect players), the sounds and messages of the old
  abilities are removed, the new ones are added, and `full-strength-hits: true` is added. Your
  own `received` sound and your other settings are kept.

Older configs (1.0 to 1.6) are brought up to date the same way. Everything is saved in
`config.yml`.

## Robust on a live server

Each part runs on its own every tick (effects, abilities, bleeding, traps, boss bars, the
duplicate tracking): an error in one is logged once a minute and never stops the others. Extra
damage is dealt a tick after the hit, so a player killed by it (or bleeding out) is let go of
cleanly and nothing is changed while a hit is still being worked out. Heavy's knockback uses
Paper's own knockback event (the old Bukkit one is marked for removal in 1.21.11). Asking
protection plugins whether a hit is allowed never counts as a hit, and if it cannot ask, the
answer is no.

## Building and testing

```bash
cd legendary
mvn -B package   # runs the tests, writes target/Legendary-<version>.jar
```

`mvn test` runs 52 tests on a simulated server:
- **Every ability and passive:** Bleed's damage over time, starting over and never stacking;
  Draw's share of the target's health, its limits, waiting for a full-strength hit and running
  out; Sticky Sweet's slow and its immunity; Sugar Traps catching an enemy once, never their
  owner, needing ground (and nothing spent without room); Heavy's longer knockback and
  knock-down in the air; Crush's slam growing with height and its cap; Execution; Reap waiting
  for a low target, running out and adding to Execution; and every cooldown.
- **The old weapons becoming the new ones** (the item, `data.yml` and the old names in
  commands), the **Starforged being taken away**, a **2.0 config getting the four new
  weapons**, and **1.25x legendary hits** (and `melee-damage: 1`), and `full-strength-hits:
  false`.
- **Protection:** protected players are never hurt, slowed or trapped; creative players, mobs
  and PvP-off are left alone; bleeding to death breaks nothing.
- **Look:** enchantments, the shimmer, item model, tooltip style, the attack damage lines, the
  boss bars (waiting, recharging, ready bars, staying while the weapon is carried), and the 3D
  effects being cleaned up.
- **True damage:** Protection made up for so the damage is exact.
- **Sounds:** every sound is a real vanilla sound.
- **Storage, duplicates and ownership:** every container type, bundles, frames, stands, hoppers
  and mobs; copies (which cannot use the ability), creative middle-click, revoked and lost
  weapons; alt protection, death drops and staff /invsee.
- **Controls:** Shift + F uses the ability and plain F swaps hands, the right-click setting,
  and offhand food.
- **Other:** the registry across restarts, commands, config checking, and updating old texts
  while keeping your own.
