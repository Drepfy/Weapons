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

- **Hidden everywhere players look.** Vanished staff are left out of the world, sounds, command suggestions,
  `/list`, and the player count and hover list in the multiplayer server list. Private messages (`/msg`, `/tell`,
  ...) to vanished staff fail as if they were offline.
- **Not on TAB.** Vanished staff aren't in anyone's tab list, not even their own or other staff's. Staff with
  `staffvanish.see` still see vanished staff in the world, and are told when someone vanishes, reappears, or joins or
  leaves while vanished. Set `show-in-tab-for-staff: true` to keep them in staff's tab list.
- **Looks like a real leave.** When someone vanishes, everyone sees the normal "IV10nesy left the game" message, and
  "IV10nesy joined the game" when they reappear. Real join, quit, death and advancement messages of vanished staff are
  hidden.
- **Can't die.** Vanished staff can't be hurt at all, not even by `/kill` or the void. Anyone who falls into the void
  is caught and left flying.
- **Flight.** Vanished staff can fly. Flight survives game mode changes, respawns and world changes. When they
  reappear it goes back to what they had before, and if they were flying, their landing doesn't hurt.
- **No giveaways.** Vanished staff don't pick up items, trigger pressure plates or tripwires, trample crops, alert
  sculk sensors or wardens, get targeted by mobs, stop projectiles, push other entities, count towards sleeping, or
  spawn mobs. They don't get hungry, and their public chat is blocked.
- **Reconnects and restarts.** Vanished staff stay vanished when they reconnect or the server restarts. They're
  hidden before other players are told they joined, so nothing flickers. Staff who lose permission to vanish
  reappear, within a couple of seconds if they're online or when they next join.
- **Other plugins.** Vanished players carry the `vanished` metadata that many plugins (EssentialsX, DiscordSRV,
  TAB, ...) check. If another plugin such as EssentialsX also has a `/vanish` command, StaffVanish takes over
  `/vanish` and `/v` once the server has started, and says so in the console.

### Vanish Stick

Vanished staff with `staffvanish.teleport` get a **Breeze Rod** called **ᴠᴀɴɪsʜ sᴛɪᴄᴋ**:

| Click                   | What it does                                              |
|-------------------------|-----------------------------------------------------------|
| Sneak + right-click     | Choose a player's head from a menu to teleport to them    |
| Sneak + left-click      | Teleport to a random player                               |
| Right-click             | Choose a player's head (same menu)                        |
| Left-click              | Teleport to the next player (alphabetical, wraps around)  |
| Right-click a player    | Inspect that player (location, health, game mode, ping)   |

The item, its slot, the menu and what each click does are configurable under `selector` in `config.yml`. The stick
is removed when its owner reappears. It can't be dropped, stored in containers or bundles, crafted with, or placed,
and it never exists as an item in the world.

### Commands

| Command                    | Description                                 | Permission             |
|----------------------------|---------------------------------------------|------------------------|
| `/vanish` (`/v`)           | Toggle your own vanish                      | `staffvanish.use`      |
| `/vanish on\|off`          | Vanish or reappear                          | `staffvanish.use`      |
| `/vanish <player>`         | Toggle another staff member's vanish        | `staffvanish.others`   |
| `/vanish on\|off <player>` | Set another staff member's vanish           | `staffvanish.others`   |
| `/vanish list`             | List vanished staff, online and offline     | `staffvanish.list`     |
| `/vanish tp <player>`      | Teleport to a player while vanished         | `staffvanish.teleport` |
| `/vanish stick`            | Get a new Vanish Stick while vanished       | `staffvanish.teleport` |
| `/vanish reload`           | Reload `config.yml`                         | `staffvanish.reload`   |

### Permissions

| Permission             | Default | Description                                         |
|------------------------|---------|-----------------------------------------------------|
| `staffvanish.*`        | op      | Everything below                                    |
| `staffvanish.use`      | op      | Vanish yourself (includes `staffvanish.see`)        |
| `staffvanish.see`      | op      | See vanished players and get vanish notifications   |
| `staffvanish.others`   | op      | Toggle vanish for other staff                       |
| `staffvanish.list`     | op      | Use `/vanish list`                                  |
| `staffvanish.teleport` | op      | Teleport while vanished and use the Vanish Stick    |
| `staffvanish.reload`   | op      | Use `/vanish reload`                                |

Permission changes made while players are online (for example with LuckPerms) take effect within
`vanish.refresh-interval-ticks` (2 seconds by default).

### Configuration

Every option is described in [`config.yml`](src/main/resources/config.yml). Text can use colour codes such as
`&b` (aqua) and `&l` (bold), or [MiniMessage](https://docs.papermc.io/adventure/minimessage/format), and any message
can be turned off by setting it to `""`. Who is vanished is stored in `plugins/StaffVanish/vanish-data.yml`.

### Troubleshooting

If `/vanish` gives you an invisibility potion effect and nobody sees a "left the game" message, a different plugin's
vanish (usually EssentialsX) is running instead of StaffVanish, which means StaffVanish didn't load:

- `/plugins` should list **StaffVanish** in green. If it's red or missing, the server console shows why.
- The server must run **Paper** (or a Paper fork such as Purpur) **1.21.4 or newer**. Spigot and older versions
  can't load it.
- On startup the console should say `Vanish is ready: /vanish and /v are handled by StaffVanish.`
- Only keep one copy of the plugin in `plugins/`.

### Limitations

- Commands from other plugins that look players up by name have to respect `Player#canSee` (or the `vanished`
  metadata) themselves. Add private-message style commands to `vanish.protection.private-message-commands`.
- Opening chests and other containers while vanished still plays their opening animation and sound.
