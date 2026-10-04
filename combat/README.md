# Combat

Combat timer and Ender Pearl cooldown for a competitive SMP (built for **ᴠᴀɴɪʟʟᴀ sᴍᴘ**).
Everything is kept on the server, so there is no way around either of them.

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
  starts the 60 seconds again.
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
| `/combat reload` | `combat.admin` | op | Reload `config.yml` |

`/combat` also works as `/ct`. There are no bypass permissions.

## Configuration

```yaml
combat:
  duration: 60s
  armor: armor-pieces   # or any-item
  logout: keep          # or kill
ender-pearl:
  cooldown: 15s
  show-on-item: true
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

`mvn test` runs 20 tests on a simulated server: the action bar counting down and
disappearing, every hit restarting the timer, arrows, TNT, end crystals, wolves and potions
counting (and mobs, own arrows, healing potions and cancelled hits not), commands, menus,
item swapping, teleports and world changes not ending combat, logging out pausing the timer,
`logout: kill` (and kicked players spared), the armored/naked kill rule (including one-hit
kills, elytras and pumpkins, and a second fight going on), the pearl cooldown (both hands,
other hotbar slots, launch blocking, reconnecting, cleared item cooldowns), staff commands,
restarts and config checking.
