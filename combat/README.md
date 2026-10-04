# Combat

Combat timer, Ender Pearl cooldown, elytra/riptide rules and safe zones for a competitive SMP
(built for **ᴠᴀɴɪʟʟᴀ sᴍᴘ**). Everything is kept on the server, so there is no way around any of them.

## Ender Pearls

- **15 second cooldown** between two Ender Pearls, per player.
- It belongs to the player, not to the item: switching items, hotbar slots or hands,
  reconnecting, commands and other plugins clearing the item cooldown do not reset it.
- A throw on cooldown is refused before the pearl is used ("You can use an Ender Pearl
  again in 12s"), and a pearl that gets launched anyway (another plugin, a hacked client)
  is removed at launch, so the player keeps it.
- The pearl in the hotbar shows the cooldown (the grey sweep), also after reconnecting.
- The cooldown keeps running while the player is offline, and is kept across restarts.

## Combat timer

- Hitting another player puts **both** players in combat for **60 seconds**. Every hit
  starts the 60 seconds again, and so does **throwing an Ender Pearl** while in combat
  (`pearl-resets-timer: true`): pearling away does not shorten the fight.
- Above the hotbar: `⚔ Combat: 60s`, `⚔ Combat: 45s`, `⚔ Combat: 10s`... updated every
  second. At 0 the bar disappears and the player is told "You are no longer in combat."
- Counts as a hit: melee, bows and crossbows, tridents, harmful splash and lingering potions
  (poison, harming, weakness, slowness...), TNT, end crystals and respawn anchors/beds the
  attacker set off, and the attacker's tamed wolves. Not: mobs, falling, your own arrows or
  pearls, healing potions, or hits another plugin cancelled (for example PvP being off in a
  protected area).
- **No restrictions**: every command works in combat, and shops, menus and other plugin
  interfaces open normally.

### Kills

| You kill a player who is... | Your combat |
|---|---|
| wearing armor (a helmet, chestplate, leggings or boots) | ends: the kill does not keep you in combat |
| completely naked | stays (the killing hit started 60 seconds) |

The armor is checked at the exact moment of death, before anything (drops, grave plugins)
can change the inventory. Only the combat with the killed player ends; if you are also
fighting someone else, that fight's timer goes on. With `armor: any-item` anything in those
four slots counts (by default an elytra, pumpkin or head is not armor). The player who
died is out of combat.

### Elytra and riptide

- **No elytra gliding** while a player you are fighting is within **15 blocks**. Trying to
  open the elytra is refused ("You cannot glide with an elytra in combat."), and a player who
  is already gliding when someone they fight comes within 15 blocks is brought down.
- **No riptide tridents** under the same rule: the trident cannot be charged, so the player
  is not launched. Throwing a trident as a weapon is still allowed.
- Further than 15 blocks from everyone you fight, both work normally, even while in combat.
  Set `radius: 0` to block them for the whole combat instead.

### Safe zones (spawn)

Staff can mark areas, such as spawn, that **players in combat cannot enter**:

| Trying to get in by... | What happens |
|---|---|
| Walking, sprinting, flying, swimming, elytra | Stopped at the edge and pushed back out |
| Riding a boat, horse, minecart, pig... | Taken off outside the zone |
| Ender Pearl, chorus fruit, portals | The teleport is cancelled |
| `/spawn`, `/home`, `/tpa`, `/warp`... | The teleport is cancelled (the command itself still works) |

The player is told "You cannot enter spawn in combat. (42s left)". Players who are already
inside when they get into combat can stay there and leave; they just cannot come back in until
their combat ends. Players in combat who come within 8 blocks of a zone see its edge as a red
wall of particles. Zones go from bedrock to build height and are kept in `zones.yml`.

```text
/combat zone create spawn 50     # 50 blocks out from where you stand, in every direction
/combat zone pos1                # or: stand on one corner...
/combat zone pos2                # ...then on the opposite corner...
/combat zone create spawn        # ...and create it between the two
/combat zone list
/combat zone delete spawn
```

Creating a zone with a name that already exists replaces it.

### No way around it

| Trying to... | What happens |
|---|---|
| Use commands, open shops or menus | Allowed, the timer keeps running |
| Move items, swap hands, change hotbar slots | Nothing changes |
| Teleport or change worlds | The timer keeps running |
| Log out | The timer is **paused** and goes on when they come back ("You are still in combat: 42s left."). Waiting it out offline does not work |
| Server restart | Combat timers and pearl cooldowns are saved and restored |

Optional: `logout: kill` also kills a player who logs out in combat (they drop their items
where they logged out, and their attacker gets the kill). Players who are kicked, and
everyone when the server stops, are never killed.

## Commands

| Command | Permission | Default | |
|---|---|---|---|
| `/combat` | `combat.status` | everyone | Your combat time and pearl cooldown |
| `/combat info <player>` | `combat.admin` | op | A player's combat time, who they are fighting, pearl cooldown |
| `/combat tag <player> [time]` | `combat.admin` | op | Put a player in combat |
| `/combat untag <player>` | `combat.admin` | op | End a player's combat |
| `/combat zone ...` | `combat.admin` | op | Create, list and delete safe zones (see above) |
| `/combat reload` | `combat.admin` | op | Reload `config.yml` |

`/combat` also works as `/ct`. There are no bypass permissions.

## Configuration

```yaml
combat:
  duration: 60s
  armor: armor-pieces   # or any-item
  logout: keep          # or kill
  pearl-resets-timer: true
  elytra:
    blocked: true
    radius: 15          # 0 = blocked for the whole combat
  riptide:
    blocked: true
    radius: 15
ender-pearl:
  cooldown: 15s
  show-on-item: true
safe-zones:
  show-border: true     # red particle wall for players in combat near a zone
  border-distance: 8
messages:
  action-bar: "&c⚔ Combat: &f{seconds}s"
```

Every message can be changed (or set to `""` to turn it off). Wrong values fall back to the
default and the console says what to fix.

Other plugins: `Bukkit.getServicesManager().load(CombatPlugin.class).isInCombat(player)`,
`combatRemaining(player)`, `pearlCooldown(player)`.

## Building and testing

```bash
cd combat
mvn -B package   # runs the tests, writes target/Combat-<version>.jar
```

`mvn test` runs 29 tests on a simulated server: the action bar counting down and
disappearing, every hit restarting the timer, arrows, TNT, end crystals, wolves and potions
counting (and mobs, own arrows, healing potions and cancelled hits not), commands, menus,
item swapping, teleports and world changes not ending combat, logging out pausing the timer,
`logout: kill` (and kicked players spared), the armored/naked kill rule (including one-hit
kills, elytras and pumpkins, and a second fight going on), the pearl cooldown (both hands,
other hotbar slots, launch blocking, reconnecting, cleared item cooldowns), a pearl starting the
60 seconds again, elytra and riptide blocked within 15 blocks of an opponent (and allowed further
away, or for the whole combat with `radius: 0`), safe zones (walking, teleporting and riding in
refused, players inside able to stay and leave, zones made between two corners and kept),
staff commands, restarts and config checking.
