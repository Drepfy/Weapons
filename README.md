# StaffVanish

A [Paper](https://papermc.io) plugin that lets staff vanish, fly and teleport unseen, with a Breeze Rod for picking
players.

- **Server:** Paper 1.21.4 or newer (including 26.x)
- **Java:** 21 to build; the server's own Java version to run

## Building

```sh
./gradlew build
```

The plugin jar is written to `build/libs/`. Put it in your server's `plugins/` folder.

## Vanish

`/vanish` hides staff from regular players:

- **Hidden everywhere players look.** Vanished staff are left out of the tab list, the world, sounds, command
  suggestions, `/list`, and the player count and hover list in the multiplayer server list. Private messages
  (`/msg`, `/tell`, ...) to vanished staff fail as if they were offline.
- **Visible to staff.** Players with `staffvanish.see` still see vanished staff, marked with `[V]` in the
  tab list, and are told when someone vanishes, reappears, or joins or leaves while vanished.
- **Flight.** Vanished staff can fly. Flight survives game mode changes, respawns and world changes. When they
  reappear it goes back to what they had before, and if they were flying, their landing doesn't hurt.
- **No giveaways.** Join, quit, death and advancement messages are hidden, and other players see a fake
  "left the game" or "joined the game" message when someone vanishes or reappears. Vanished staff don't pick up
  items, trigger pressure plates or tripwires, trample crops, alert sculk sensors or wardens, get targeted by mobs,
  stop projectiles, push other entities, count towards sleeping, or spawn mobs. They're also invulnerable and don't
  get hungry, and their public chat is blocked.
- **Reconnects and restarts.** Vanished staff stay vanished when they reconnect or the server restarts. They're
  hidden before other players are told they joined, so nothing flickers. Staff who lose permission to vanish
  reappear, within a couple of seconds if they're online or when they next join.
- **Other plugins.** Vanished players carry the `vanished` metadata that many plugins (EssentialsX, DiscordSRV,
  TAB, ...) check.

### Staff selector

Vanished staff with `staffvanish.teleport` get a **Breeze Rod** staff selector:

| Click                    | Default action                                            |
|--------------------------|-----------------------------------------------------------|
| Right-click              | Open a menu of player heads; click one to teleport to it  |
| Left-click               | Teleport to the next player (alphabetical, wraps around)  |
| Sneak + left-click       | Teleport to the previous player                           |
| Sneak + right-click      | Inspect the player you're looking at                      |
| Right-click a player     | Inspect that player (location, health, game mode, ping)   |

The item, its slot, the menu and what each click does are configurable under `selector` in `config.yml`. The
selector is removed when its owner reappears. It can't be dropped, stored in containers or bundles, crafted
with, or placed, and it never exists as an item in the world.

### Commands

| Command                    | Description                                 | Permission             |
|----------------------------|---------------------------------------------|------------------------|
| `/vanish` (`/v`)           | Toggle your own vanish                      | `staffvanish.use`      |
| `/vanish on\|off`          | Vanish or reappear                          | `staffvanish.use`      |
| `/vanish <player>`         | Toggle another staff member's vanish        | `staffvanish.others`   |
| `/vanish on\|off <player>` | Set another staff member's vanish           | `staffvanish.others`   |
| `/vanish list`             | List vanished staff, online and offline     | `staffvanish.list`     |
| `/vanish tp <player>`      | Teleport to a player while vanished         | `staffvanish.teleport` |
| `/vanish selector`         | Get a new staff selector while vanished     | `staffvanish.teleport` |
| `/vanish reload`           | Reload `config.yml`                         | `staffvanish.reload`   |

### Permissions

| Permission             | Default | Description                                         |
|------------------------|---------|-----------------------------------------------------|
| `staffvanish.*`        | op      | Everything below                                    |
| `staffvanish.use`      | op      | Vanish yourself (includes `staffvanish.see`)        |
| `staffvanish.see`      | op      | See vanished players and get vanish notifications   |
| `staffvanish.others`   | op      | Toggle vanish for other staff                       |
| `staffvanish.list`     | op      | Use `/vanish list`                                  |
| `staffvanish.teleport` | op      | Teleport while vanished and use the staff selector  |
| `staffvanish.reload`   | op      | Use `/vanish reload`                                |

Permission changes made while players are online (for example with LuckPerms) take effect within
`vanish.refresh-interval-ticks` (2 seconds by default).

### Configuration

Every option is described in [`config.yml`](src/main/resources/config.yml). All text uses
[MiniMessage](https://docs.papermc.io/adventure/minimessage/format), and any message can be turned off by setting
it to `""`. Who is vanished is stored in `plugins/StaffVanish/vanish-data.yml`.

### Limitations

- Commands from other plugins that look players up by name have to respect `Player#canSee` (or the `vanished`
  metadata) themselves. Add private-message style commands to `vanish.protection.private-message-commands`.
- Opening chests and other containers while vanished still plays their opening animation and sound.
