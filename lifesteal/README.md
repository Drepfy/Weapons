# Lifesteal

Lifesteal for a competitive SMP (built for **ᴠᴀɴɪʟʟᴀ sᴍᴘ**). Kill a player to steal a
heart; lose one when you are killed.

- **Hearts**: everyone starts with **10**. A kill gives the killer **+1** and takes **1**
  from the victim. Nobody goes below **3** or above **20**.
- **At 20 hearts**: the killer stays at 20, the victim still loses the heart, and it
  drops as a **Heart item** where the victim died.
- **30-minute cooldown** per killer and victim: killing the same player again within 30
  minutes is a normal kill but moves no hearts. Other victims are not affected, and
  the victim can still take the heart straight back (set `both-directions: true` to
  stop that too). Cooldowns survive restarts.
- **Heart items** (red dye named **❤ Heart**, glowing, with a custom texture in the
  resource pack). Right-click to use one, sneak + right-click to use as many from the
  stack as fit below 20. They stack, trade and go in chests, ender chests, barrels,
  shulker boxes, hoppers, bundles... like any item. Red dye renamed in an anvil is
  **not** a Heart, and a Heart can never be used as dye (no crafting, looms, villager
  trades, signs, sheep, wolf or cat collars, crafters).
- **`/withdraw <amount>`**: turn hearts into Heart items, at most 17 at once, always
  keeping 3. `/withdraw all` takes as many as allowed.
- **Crafting**: 6 diamond blocks, 2 netherite ingots and a nether star make one Heart.
- **Alt account protection**: kills between accounts of the same person never move
  hearts (both keep theirs). See "Alt accounts".
- **Feels like a heart changed hands**: the killer sees **+1 ❤** in the middle of the
  screen ("stolen from Alex"), hears a level-up chime and hearts float up round them; the
  victim sees **-1 ❤** ("taken by Steve"). Using a Heart item shows the new total. Titles,
  particles and both sounds can be changed or switched off (`effects`).
- **PlaceholderAPI**: `%lifesteal_hearts%`, the top 10 and the limits for scoreboards, tab
  lists and holograms (see "Placeholders").
- Everything is configurable, every message can be changed, and every heart change is
  logged.

## Install

1. Put `Lifesteal.jar` in `plugins/` and restart. Works on Paper or Spigot 1.20 to 1.21.11
   (Java 17+; built and checked against the 1.21.11 API).
2. Optional: set up the Heart texture (see "Resource pack").
3. Optional: edit `plugins/Lifesteal/config.yml`, then `/lifesteal reload`.

## Rules in detail

| Situation | Killer | Victim |
|---|---|---|
| Normal kill | +1 heart (filled, not empty) | -1 heart |
| Killer has 20 hearts | stays at 20 | -1 heart, it drops as a Heart item where they died |
| Victim has 3 hearts | nothing | nothing (no heart to steal) |
| Same killer and victim within 30 minutes | nothing | nothing |
| Alt accounts (see below) | nothing | nothing |
| Killed by a mob, fall, lava... or by yourself | | nothing (or -1 with `lose-on-natural-death: true`) |
| In a world from `disabled-worlds` | nothing | nothing |

The cooldown only starts when a heart was really stolen. The death and the kill always
count normally in Minecraft (items drop, statistics, death message).

Using Heart items: 10 hearts + 5 Heart items = 15 hearts. Sneak + right-click with a
stack of 10 at 15 hearts uses 5 and keeps 5. At 20 hearts a Heart cannot be used.
Right-clicking a chest, door or button with a Heart in your hand uses the block as normal.

## Commands

| Command | Permission | Default | |
|---|---|---|---|
| `/withdraw <amount>` | `lifesteal.withdraw` | everyone | Hearts into Heart items (1-17, keep 3). `/withdraw all` |
| `/hearts` | `lifesteal.hearts` | everyone | Your hearts |
| `/hearts top` | `lifesteal.hearts` | everyone | The 10 players with the most hearts |
| `/hearts <player>` | `lifesteal.hearts.others` | everyone | Another player's hearts |
| `/lifesteal sethearts <player> <amount>` | `lifesteal.admin.hearts` | op | Set hearts (offline players too) |
| `/lifesteal addhearts <player> <amount>` | `lifesteal.admin.hearts` | op | Add hearts |
| `/lifesteal removehearts <player> <amount>` | `lifesteal.admin.hearts` | op | Take hearts away |
| `/lifesteal giveheart <player> [amount]` | `lifesteal.admin.give` | op | Give Heart items |
| `/lifesteal resetcooldowns <player>` | `lifesteal.admin.cooldowns` | op | Clear a player's cooldowns |
| `/lifesteal info <player>` | `lifesteal.admin.alts` | op | Hearts, cooldowns, linked accounts, accounts on the same IP |
| `/lifesteal alts link\|unlink <player> <player>` | `lifesteal.admin.alts` | op | Treat two accounts as the same person |
| `/lifesteal alts allow\|disallow <player> <player>` | `lifesteal.admin.alts` | op | Treat two accounts on one IP as different people (siblings) |
| `/lifesteal reload` | `lifesteal.admin.reload` | op | Reload `config.yml` |

`/lifesteal` on its own shows a help page with the commands you may use (click one to type
it, hover for what it does). It also works as `/ls`. `lifesteal.admin` gives every staff command, and
`lifesteal.notify` (included) tells staff when a kill is not counted because of alt
protection. Hearts are always kept between 3 and 20, even when staff set them.

## Alt accounts

A kill moves no hearts when any of these is true:

1. **Linked by staff**: `/lifesteal alts link Main Alt`.
2. **Same IP address**: both accounts joined from the same IP address in the last 30
   days. Addresses are only stored as a scrambled code. An address used by more than 5
   accounts (school, public Wi-Fi) is ignored, and two people who really share a
   connection can be allowed with `/lifesteal alts allow Brother Sister`.
3. **New account**: either account has played less than 30 minutes on the server.

Staff with `lifesteal.notify` see `[Lifesteal] Main killed Alt, not counted: same IP
address`. Behind BungeeCord or Velocity, turn on IP forwarding (the console warns if every
player seems to come from the same local address).

More checks can be added by other plugins:

```java
LifestealPlugin lifesteal = Bukkit.getServicesManager().load(LifestealPlugin.class);
lifesteal.alts().register(new AltCheck() {
    public String id() { return "my-check"; }
    public String check(Player killer, Player victim) { return suspicious ? "reason for staff" : null; }
});
```

`HeartStealEvent` (cancellable) is fired before every steal, after the checks above.

## Resource pack

`plugins/Lifesteal/Lifesteal-ResourcePack.zip` gives the Heart its own heart texture
(Minecraft 1.20 to 1.21.x). Without it, a Heart looks like glowing red dye with its name.

1. Upload the zip somewhere that gives a direct download link (for example
   [mc-packs.net](https://mc-packs.net)).
2. Either paste the link in `config.yml` (`resource-pack.url`), and the plugin sends the
   pack to everyone who joins, or put it in `server.properties` (`resource-pack=` and
   `resource-pack-sha1=`) yourself.

On 1.20.3 and newer the plugin's pack is added next to the server's own pack, never in
place of it. If you already use a server resource pack, you can also copy the `assets` folder
from the zip into yours (the ᴠᴀɴɪʟʟᴀ sᴍᴘ server pack already has the Heart). The texture is drawn by `resourcepack/make_heart.py`; `resourcepack/build.py`
rebuilds the zip. If you change `heart-item.custom-model-data` or `heart-item.material`,
change the pack to match.

## Placeholders

With [PlaceholderAPI](https://www.spigotmc.org/resources/6245/) installed (optional), these
work in any plugin that shows placeholders (scoreboards, TAB, holograms, chat):

| Placeholder | Shows |
|---|---|
| `%lifesteal_hearts%` | The player's hearts |
| `%lifesteal_max%`, `%lifesteal_min%`, `%lifesteal_start%` | The limits in `config.yml` |
| `%lifesteal_top_1_name%` ... `%lifesteal_top_10_name%` | Who has the most hearts (`-` if nobody yet) |
| `%lifesteal_top_1_hearts%` ... `%lifesteal_top_10_hearts%` | Their hearts |

The top list is updated every second. Nothing to set up: the console says
"PlaceholderAPI: ... are ready" when they are.

## Configuration

See [`config.yml`](src/main/resources/config.yml); every option is explained there. The
most important ones:

```yaml
hearts: {start: 10, min: 3, max: 20, per-kill: 1, lose-on-natural-death: false}
cooldown: {time: 30m, both-directions: false}
withdraw: {enabled: true, max-per-command: 17}
recipe: {enabled: true, shape: [DND, DSD, DND], ingredients: {D: DIAMOND_BLOCK, N: NETHERITE_INGOT, S: NETHER_STAR}}
alt-protection: {enabled: true, shared-ip: {remember: 30d, max-accounts-per-ip: 5}, min-playtime: 30m}
effects: {titles: true, particles: true, sound-gain: "entity.player.levelup 0.7 1.4", sound-lose: "block.respawn_anchor.deplete 0.8 1.3"}
```

Updating from 1.0: the new `effects` settings (and their messages) are added to your
`config.yml` with their comments the first time; nothing you changed is touched.

A wrong value never breaks the plugin: it falls back to its default and the console
(and `/lifesteal reload`) says what to fix.

## Files

```
plugins/Lifesteal/
├── config.yml
├── data.yml                     # hearts, cooldowns, linked accounts, scrambled IP codes
├── Lifesteal-ResourcePack.zip   # the Heart texture
└── logs/lifesteal.log           # every heart that moved, and why kills did not count
```

`data.yml` is saved within a second of every change, off the main thread, by writing a new
file and swapping it in, so a crash cannot leave it half-written. An unreadable file is
moved aside, never deleted.

## Building and testing

```bash
cd lifesteal
mvn -B package   # runs the tests, writes target/Lifesteal-<version>.jar
```

`mvn test` runs 37 tests on a simulated server (MockBukkit): stealing and the heart limits,
the titles and sounds (and switching them off),
the drop at 20 hearts, the cooldown (per pair, ends after 30 minutes, kept across
restarts), withdrawing (every invalid amount, full inventory), using Hearts (one, a stack,
at the maximum, both hands, chests), Hearts not working as dye or in crafting, the recipe,
every alt check (and that shared networks and local addresses are ignored), staff commands
(also for offline players), the placeholders (also asked from another thread), saving and
loading, config validation, and adding the new settings to an old config.
