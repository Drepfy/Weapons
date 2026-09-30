# Vigil

Anti-cheat and moderation for a Spigot/Paper SMP (built for **ᴠᴀɴɪʟʟᴀ sᴍᴘ**).

- **20 server-side checks**: speed, flight (incl. spider and walking on water),
  step, NoFall, timer, NoSlow, anti-knockback, reach, kill aura, no-swing,
  hitting through walls, auto clicker, mace exploits, block reach, scaffold, fast
  place, nuker, chest aura, x-ray mining patterns and inventory macros
  (Item Scroller, Tweakeroo restock, chest stealers, InvMove).
- **Anti x-ray**: switches on Paper's built-in anti-xray for you (fake ores and
  fake caves), and hides diamonds and ancient debris in caves until you are down
  there and can see them.
- **Anti ESP / anti freecam for bases**: chests, barrels, shulker boxes, beds and
  other storage a player can't see are shown to that player as stone until they
  get close or can see them, and hidden again when out of sight.
- **Blocks hacked clients**: kicks clients that announce themselves (Meteor,
  Wurst, LiquidBounce...) and world downloaders. Optionally kicks every modded
  (Fabric/Forge) client.
- **Ban animation**: lightning, an explosion, thunder and a big red **BANNED**
  title on the cheater's screen, then a banner in chat for everyone.
- **Automatic bans**: when a check's violation level reaches its `ban-at`, the
  player is banned ("Cheating (Flying)"): 30 days the first time, permanent the
  second time. They are kicked with the anti-cheat ban screen and everyone is
  told. Staff see every detection live:
  `Steve has been flagged for Kill Aura (VL 3)`.
- **Preset bans with times that go up**: `/ban Steve Cheating` is 7 days the
  first time, 30 days the second time and permanent after that. Every preset
  reason (Cheating, X-Ray, Griefing, Spam...) has its own times per offence.
  `/ban` on its own lists them all.
- **Clear ban screens**: separate screens for temporary, permanent and
  anti-cheat bans, showing the reason, which offence it is, who banned you, the
  date, when it ends, a ban ID and how to appeal.
- **Punish menu**: `/punish Steve` opens a menu with every preset, its times and
  what Steve's next offence would get. Click twice to punish.
- **Warnings add up**: 3 warnings = muted for 1 hour, 5 = banned for 1 day,
  7 = banned for 7 days (configurable). Warnings expire after 30 days.
- **Discord log** (optional): bans, mutes, warnings and kicks posted to a
  Discord channel through a webhook.
- **Few commands**: `/ac` for the anti-cheat, `/punish` for the menu, plus
  `/ban /unban /mute /unmute /warn /kick` with preset reasons.
- **Low false positives**: every check allows for lag, ping, knockback, pistons,
  ice, slime, water, ladders, potions and more. A single odd event never flags
  and violation levels decay, so only repeated cheating reaches a ban.
- No packet libraries or client mods: only the Bukkit API, plus Paper features
  used automatically when present.

## Install

1. Put `Vigil.jar` (from `release/`) in `plugins/` and restart.
2. Ops have every staff permission. For other staff, give `vigil.*` or the
   single permissions below.
3. Optional: edit `plugins/Vigil/config.yml`, then run `/ac reload`.

**Updating from 2.x:** just replace the jar. On start, Vigil adds the new
options to your `config.yml` and saves the old file as
`config-before-<version>.yml`. Anything still set to an old default (ban screen,
preset times, auto-ban length) gets the new default; anything you changed is
kept as it is. Bans, mutes and warnings are kept.

**Updating from 1.x:** your old config is saved as `config-1.x-backup.yml` and
replaced by the new, shorter one. Your reasons, messages, prefix and disabled
worlds are kept, and so are bans and mutes (`data/punishments.yml`).

Requirements: Paper (recommended) or Spigot 1.20–1.21.x, Java 17+ (Java 21 on
1.20.5+). No other plugins needed.

## Commands

| Command | Permission | What it does |
|---|---|---|
| `/ac alerts` | `vigil.alerts` | Turn anti-cheat alerts on/off for yourself |
| `/ac check <player>` | `vigil.check` | Why a player is or isn't checked, violation levels, recent flags, punishment history (offline too) |
| `/ac reset <player> [check]` | `vigil.admin` | Clear violation levels |
| `/ac debug <player> [check]` | `vigil.admin` | Show live check values (for tuning) |
| `/ac reload` | `vigil.admin` | Reload `config.yml` |
| `/ac preview` | `vigil.admin` | Watch the ban animation on yourself (you are not banned) |
| `/punish <player>` | any of `vigil.ban/mute/warn/kick` | Menu with every preset reason, its times and the player's next punishment; also unban/unmute and history |
| `/ban <player> [duration] [reason]` | `vigil.ban` | `/ban Steve Cheating` = preset time (7 days, then 30 days, then permanent), `/ban Steve 3d Griefing` = your own time, `/ban Steve` = permanent, "No Reason". `/ban` alone lists the presets |
| `/unban <player> [reason]` | `vigil.ban` | Lift a ban (also automatic bans) |
| `/mute <player> [duration] [reason]` | `vigil.mute` | Blocks chat and `/msg`, `/r`, `/me`... `/mute` alone lists the presets |
| `/unmute <player> [reason]` | `vigil.mute` | |
| `/warn <player> [reason]` | `vigil.warn` | The player sees the warning and their count; enough warnings mute or ban |
| `/kick <player> [reason]` | `vigil.kick` | Kicked with the kick screen |

`/ac` also works as `/anticheat` and `/vigil`. Durations: `30m`, `12h`, `7d`,
`2w`, `1mo`, `1y`, `perm`. Tab completion shows the preset reasons. If another
plugin also has `/ban`, use `/vigil:ban`.

## Moderation

### Preset reasons and their times

Each preset has a time per offence. The last time repeats:

| Ban reason | 1st | 2nd | 3rd | 4th+ |
|---|---|---|---|---|
| Cheating | 7 days | 30 days | Permanent | |
| Hacked Client, Kill Aura, Mace Exploit, Threats | 14 days | 30 days | Permanent | |
| Fly/Speed Hacks, X-Ray, Reach, Exploiting | 7 days | 14 days | 30 days | Permanent |
| Auto Clicker | 3 days | 7 days | 14 days | 30 days |
| Duping | 30 days | Permanent | | |
| Griefing, Scamming, Harassment, Lag Machine | 3 days | 7 days | 30 days | Permanent |
| Stealing, Inappropriate Build | 1 day | 3 days | 7 days | 30 days |
| Hate Speech | 7 days | 30 days | Permanent | |
| Advertising | 1 day | 7 days | 30 days | Permanent |
| Spam, Inappropriate Skin, Staff Disrespect | 1 day | 3 days | 7 days | |
| Doxxing, Inappropriate Name, Ban Evasion, Alt Account, Chargeback | Permanent | | | |

| Mute reason | 1st | 2nd | 3rd | 4th+ |
|---|---|---|---|---|
| Spam, Chat Flood | 15 min | 1 hour | 6 hours | 1 day |
| Excessive Caps | 10 min | 30 min | 1 hour | 6 hours |
| Swearing, Inappropriate Language | 30 min | 2 hours | 1 day | 7 days |
| Toxicity | 1 hour | 6 hours | 1 day | 7 days |
| Harassment | 6 hours | 1 day | 7 days | 30 days |
| Hate Speech, Threats | 1 day | 7 days | 30 days | Permanent |
| Advertising | 1 hour | 1 day | 7 days | 30 days |
| Arguing With Staff, Begging | 30 min | 2 hours | 1 day | |
| Spoilers | 15 min | 1 hour | 6 hours | |
| Politics Or Religion | 1 hour | 6 hours | 1 day | |
| Impersonation | 1 day | 7 days | 30 days | |

The offence number counts earlier bans (or mutes) of that player for the same
reason, including expired and lifted ones. A punishment lifted as a mistake
(the unban reason contains "false", "mistake" or "appeal", e.g.
`/unban Steve False_Ban`) doesn't count. Typing a time yourself
(`/ban Steve 3d Cheating`) always wins. Change the presets in
`moderation.reasons`:

```yaml
moderation:
  reasons:
    ban:
      Cheating: 7d, 30d, perm   # 1st, 2nd, 3rd+ offence
      Doxxing: perm             # always permanent
```

### Ban screens

A banned player sees one of three screens (all in `messages`, all editable):

```
            ᴠᴀɴɪʟʟᴀ sᴍᴘ
  ────────────────────────────
         YOU ARE BANNED
      for another 6 days 23 hours

  Reason: Cheating (1st offence)
  Banned by: Admin
  Banned on: 30 Sep 2026, 14:05
  Unbanned on: 07 Oct 2026, 14:05
  Ban ID: #42

  Appeal: Ask a staff member on our Discord
  ────────────────────────────
```

- `ban-screen`: temporary bans (with the countdown and the unban date).
- `ban-screen-permanent`: permanent bans.
- `ban-screen-anticheat`: automatic bans ("BANNED BY THE ANTI-CHEAT", what was
  detected).
- `kick-screen`: kicks.

Set your Discord invite or website in `moderation.appeal`, and the date style in
`moderation.date-format`. Placeholders: `{player} {staff} {reason} {duration}
{expires} {expires-date} {date} {id} {offence} {appeal}`.

### Warnings

```yaml
moderation:
  warn-escalation:
    3: mute 1h     # the 3rd warning mutes for 1 hour
    5: ban 1d
    7: ban 7d      # also: "kick", "mute perm", "ban perm"
  warnings-expire-after: 30d   # perm = warnings never expire
```

### Discord

Create a webhook (channel settings → Integrations → Webhooks), then:

```yaml
discord:
  webhook: "https://discord.com/api/webhooks/..."
  send-punishments: true   # bans, mutes, warnings, kicks, unbans
  send-auto-bans: true     # automatic anti-cheat bans
  send-alerts: false       # every anti-cheat alert (a lot)
```

Messages are sent in the background; if Discord is down nothing on the server
is affected. Mentions like `@everyone` in reasons never ping.

## Permissions

| Permission | Default | |
|---|---|---|
| `vigil.*` | op | All of the permissions below except `vigil.protect` and `vigil.bypass` |
| `vigil.alerts` | op | See detections, auto-bans and staff punishments; `/ac alerts` |
| `vigil.check` | op | `/ac check` |
| `vigil.admin` | op | `/ac reload`, `/ac reset`, `/ac debug` |
| `vigil.ban`, `vigil.mute`, `vigil.warn`, `vigil.kick` | op | The moderation commands |
| `vigil.protect` | false | Cannot be punished by staff commands and is never auto-banned (still flagged) |
| `vigil.bypass` | false | Not checked at all (and sees hidden storage), **only** if `anticheat.bypass-permission: true` (off so `*` permissions can't switch the anti-cheat off) |

## Checks

| Check | Catches | Auto-ban at |
|---|---|---|
| `speed` | Speed, bunny-hop and strafe hacks | 15 |
| `flight` | Fly, hover, glide, spider (wall climbing), Jesus (walking on water) | 8 |
| `step` | Step and high jump | 8 |
| `nofall` | Claiming to stand on air, falling without taking fall damage | 10 |
| `timer` | Running the game faster than real time | 10 |
| `noslow` | Full speed while eating, drinking, blocking or using a bow | 12 |
| `velocity` | Anti-knockback (not moving up when hit) | 10 |
| `reach` | Hitting from further than 3 blocks (lag compensated) | 10 |
| `killaura` | Hitting something the crosshair isn't on; hitting two entities in one tick | 10 |
| `noswing` | Attacking without swinging the arm | 10 |
| `wallhit` | Hitting through solid walls | 8 |
| `autoclicker` | Clicking more than 25 times a second | alert only |
| `blockreach` | Breaking/using blocks from too far away | 8 |
| `interact` | Scaffold: placing against a block side you can't see | 8 |
| `fastplace` | Placing more than 20 blocks a second | 12 |
| `nuker` | Breaking more than 30 blocks a second | 8 |
| `chestaura` | Opening containers through walls | 8 |
| `mace` | Mace "one-shot" exploits (MaceKill): faking a huge fall with an impossible jump, or smashing while standing on the ground. The hit is cancelled | 3 |
| `xray` | X-ray, ore ESP and seed-based ore finders: 5 hidden diamond/debris veins in a row found after mining only a few blocks each | alert only |
| `inventory` | Inventory macros: more than 6 clicks in one tick or 30 a second (Item Scroller mass moving, Tweakeroo hand restock, chest stealers). The clicks are refused, so the mod stops working. Also moving while clicking in an open inventory (InvMove) | alert only |

**How a ban happens.** A check first collects evidence: several suspicious
events, a ghost-block resync for flight/NoFall, a second look one tick later for
kill aura. Only then does it *flag*. Each flag adds 1 to the player's violation
level (VL) for that check, and 1 decays every minute. When the VL reaches
`ban-at`, the player is banned. So a cheater is banned within seconds to a
minute, while the rare false flag decays away. Players with `vigil.protect` are
reported to staff instead. Nothing is banned in `advanced.passive-mode`.

Speed, flight and step also pull the player back (setback). Reach, block reach,
scaffold, nuker, chest aura and mace cancel the action once the pattern is clear.

## X-ray, ESP and freecam

These cheats read information the server sends, so the best defence is not
sending it. Vigil does three things:

1. **Paper anti-xray (prevention).** On first start Vigil switches on Paper's own
   anti-xray (`engine-mode: 2`, Paper's recommended block lists, nether lists for
   nether worlds, off in the end). The server then fills solid stone with fake
   ores, so x-ray and ore ESP show ores everywhere and real ones only when they
   touch air. Backups (`*.vigil-backup`) are made and **a restart is needed**. It
   only happens once. If you turn it off again, Vigil leaves it off. Set
   `anticheat.setup-paper-anti-xray: false` to manage it yourself.
   The block lists include `air`, which fills the underground with fake caves,
   so cave finders and x-ray can't see the real caves in the deepslate layer.
   Servers set up by 2.1.0 get this added automatically (restart needed).
2. **Hidden storage and cave ores (prevention, Paper).** Containers, beds,
   enchanting tables, diamonds and ancient debris that the player can't see from
   where they really stand are shown as stone (deepslate/netherrack) to that player
   only. They appear within 8 blocks or once there is a clear line of sight (up to
   48 blocks), and are hidden again once out of sight. Diamonds in caves, which
   Paper's anti-xray can't hide, only show up when you are really down there. This
   beats storage ESP, "stash finders", freecam scouting and cave x-ray. The world
   is never changed. Blocks outside your view direction but in plain sight stay
   shown, so turning around never makes things flicker.
3. **X-ray mining pattern (detection).** Catches players who dig straight to
   hidden diamonds or ancient debris (x-ray, ore ESP, "ore sim"). Staff get an
   alert. It is statistical, so it never bans on its own.

4. **Client check.** Kicks clients whose brand or plugin channels name a hacked
   client (Meteor, Wurst, LiquidBounce, Aristois, Impact, RusherHack...) and world
   downloaders (base stealing). `anticheat.client-check.block-all-mods: true` kicks
   every Fabric/Forge/Quilt client. That blocks Freecam, Tweakeroo, Item Scroller
   and Meteor as mods, but also harmless mods like Sodium, Iris and minimaps.
   Lunar, Badlion and vanilla players are not affected.

What no server plugin can do:
- **Freecam itself** is invisible to the server: the real player just stands
  still. Vigil stops what freecam is used for (hidden storage and ores, anti-xray)
  and catches interactions from the camera position (block reach, chest aura).
  To block the Freecam mod itself, use `block-all-mods`.
- **Naming a player's mods.** Plugins that do this use a "sign translation" trick
  that Minecraft fixed in 26.1 and that cheat clients block, so Vigil doesn't.
- **Not sending the deepslate layer at all** needs a packet-level plugin. Vigil
  gets close: fake caves and fake ores fill it, and the real diamonds, debris and
  chests stay hidden until you are there. For even more, add
  [RayTraceAntiXray](https://github.com/stonar96/RayTraceAntiXray) (Paper).
- **Player ESP / tracers** can't be hidden without also removing players from the
  tab list, so Vigil doesn't do it.
- **Ore sim** (predicting ores from the world seed) is beaten by Paper's
  `feature-seeds: generate-random-seeds-for-all: true`, but only in chunks
  generated afterwards. Use it for a new world; Vigil doesn't change it for you.
  The x-ray pattern check still catches ore-sim users mining.
- The first moment a chunk arrives, the real chest or cave diamond is in it; a
  mod that logs blocks the instant a chunk arrives can still record it once.
  Paper anti-xray has no such gap for buried ores.
- A default Meteor client looks like any Fabric client. Its cheats are caught by
  the checks, and `block-all-mods` keeps it out entirely.

## Configuration

The default [`config.yml`](src/main/resources/config.yml) is short:

```yaml
prefix: "&b&lᴠᴀɴɪʟʟᴀ sᴍᴘ » &r"
anticheat:
  enabled: true
  alerts: true
  auto-ban: {enabled: true, duration: "30d, perm", reason: "Cheating ({reason})", broadcast: true,
             command: "", animation: true}   # 30 days the 1st time, permanent after that
  exempt-creative-and-spectator: true
  disabled-worlds: []
  bypass-permission: false
  setup-paper-anti-xray: true   # switch on Paper's anti-xray once (restart needed)
  hide-storage-from-esp: true   # anti ESP / freecam for bases (Paper)
  hide-ores-from-xray: true     # diamonds/debris in caves hidden until seen (Paper)
  client-check: {enabled: true, block-all-mods: false}
  checks:
    speed: {enabled: true, ban-at: 15}
    # ... one line per check; ban-at: 0 = alerts only
moderation: ...   # appeal, date format, warnings, preset reasons and their times
discord: ...      # optional webhook
messages: ...     # every text players and staff see, including the ban screens
```

- To ban with another plugin (LiteBans, EssentialsX...), set
  `auto-ban.command`, e.g. `"tempban {player} {duration} {reason}"`
  (`{duration}` is empty for permanent bans).
- A check can be switched off with `speed: false`.
- Invalid values fall back to their defaults and are listed in the console and
  after `/ac reload`. The file is only rewritten when a new version adds options
  (with a backup, see "Updating from 2.x").

### Advanced options (optional)

Add them only if you need to. Defaults shown.

```yaml
advanced:
  passive-mode: false          # detect and alert only: no setbacks, cancels or bans
  debug: false                 # print /ac debug output to the console
  alert-console: true
  alert-cooldown-ms: 3000      # per player and check; the next alert says "(+N more)"
  alert-clickable: true        # click an alert to run /ac check
  history-size: 50             # flags kept per player
  log-to-file: true            # logs/flags-YYYY-MM-DD.log
  log-retention-days: 30       # 0 = keep forever
  use-client-tick-events: true # Paper: exact client timing
  platform-entities: [BOAT, RAFT, MINECART, SHULKER, HAPPY_GHAST]
  anti-esp:
    reveal-distance: 8         # hidden storage is always shown this close
    look-distance: 48          # ...and within this distance once in line of sight
    blocks: [CHEST, TRAPPED_CHEST, BARREL, ENDER_CHEST, "*SHULKER_BOX", HOPPER, DROPPER,
             DISPENSER, CRAFTER, FURNACE, BLAST_FURNACE, SMOKER, BREWING_STAND, "*_BED", ENCHANTING_TABLE]
    ores: [DIAMOND_ORE, DEEPSLATE_DIAMOND_ORE, ANCIENT_DEBRIS]
  lag-protection:
    min-tps: 17.0              # no flags below this TPS
    max-ping-ms: 400           # no flags for players above this ping
    lag-spike-threshold-ms: 400
    lag-spike-grace-ms: 3000
    join-grace-ms: 3000
    respawn-grace-ms: 2000
    teleport-grace-ms: 1000
    world-change-grace-ms: 2000
    gamemode-change-grace-ms: 1500
    velocity-grace-ms: 1500
    vehicle-exit-grace-ms: 1000
    elytra-grace-ms: 2500
    riptide-grace-ms: 3000
    disturbance-radius: 8.0    # pistons, explosions, wind charges nearby
    disturbance-grace-ms: 3000
```

Each check also accepts `alert-at` (1), `decay-per-minute` (1), `vl-per-flag`
(1), `buffer-threshold` and `mitigate`, plus its own tuning values, for example:

```yaml
anticheat:
  checks:
    speed: {enabled: true, ban-at: 15, leniency: 1.25, burst-ticks: 20}
    reach: {enabled: true, ban-at: 10, leniency: 0.3}
    autoclicker: {enabled: true, ban-at: 20, max-cps: 25}
```

All options and their allowed ranges are defined in
[`CheckSpec.java`](src/main/java/io/github/drepfy/vigil/config/CheckSpec.java).

## Files

```
plugins/Vigil/
├── config.yml
├── data/punishments.yml      # bans, mutes, warnings, kicks (manual and automatic)
├── data/players/<uuid>.yml   # lifetime flag counts and recent flags
├── data/staff.yml            # who turned alerts off
└── logs/flags-YYYY-MM-DD.log # every flag and automatic ban
```

Corrupt files are moved aside (`*.corrupt-<time>`), never deleted. All disk
writes happen off the main thread. A check that keeps throwing errors switches
itself off until `/ac reload`, and never affects the game. Violation levels
survive a relog for 30 minutes.

## Developer API

```java
VigilApi vigil = Bukkit.getServicesManager().load(VigilApi.class);
vigil.notifyImpulse(player);                        // you launched the player (dash, grapple...)
vigil.exempt(player, CheckCategory.MOVEMENT, 2000); // skip movement checks for 2 s
```

`VigilFlagEvent` (sync, cancellable) fires before every flag; cancelling it
discards the flag (no VL, alert, log or ban). Add `softdepend: [Vigil]`. Moving
players with Bukkit's `setVelocity`/`teleport` needs nothing extra.

## Building

```bash
mvn -B package   # runs the tests, writes target/Vigil-<version>.jar
```

In IntelliJ IDEA: **File → Open** → select the folder containing `pom.xml` →
Maven tab → **Lifecycle → package**. GitHub Actions builds every push.

## Testing

`mvn test` runs 108 tests. 34 of them are end-to-end scenarios on a simulated
server (MockBukkit). They check that legit sprint-jumping, wall jumps, stairs,
bridging, knockback, falls with damage, normal fights, fights against a strafing
target, real mace smashes and normal branch mining are **never** flagged, and that
chests in plain view are never hidden. They also check that flying, speed, walking
on water, reach, kill aura, no-swing, auto clicking, anti-knockback (also
mid-combo), NoFall, scaffold, fake mace falls and x-ray mining **are** flagged; that
chests and cave diamonds behind walls are hidden, reappear up close and are hidden
again when you leave; that inventory macros are refused and flagged while normal
clicking is not; that world-downloader clients are kicked; and that a flyer is
held in place during the ban animation, then auto-banned, kicked and refused at
login. For moderation they check that a repeated preset ban gets the next time
(and that a ban lifted as a mistake doesn't count), that the punish menu bans
with the right time after a confirm click, that 3 warnings mute (without ever
shortening a longer mute) and that a banned player sees the ban screen when
joining. The Paper anti-xray setup is tested on sample Paper config files
(backups, other settings untouched, runs only once), and the config upgrade on
the real 2.2.0 config (new options added, edited values kept). Physics
simulations with random network jitter, lag spikes and frozen clients guard the
speed, timer and flight limits.

On a test server, before going live:

- [ ] Play normally for a while: sprint-jump, ice, slime, ladders, swimming,
      elytra, boats, horses, pearls, knockback, fighting mobs and players. `/ac check <you>` shows no violations.
- [ ] With a hacked client on an alt, try fly, speed, kill aura, reach, NoFall and
      scaffold. You should get alerts, then an automatic ban.
- [ ] `/ban <alt> Cheating` bans for 7 days (1st offence); after `/unban <alt>`,
      the same command bans for 30 days (2nd offence). The banned alt sees the ban
      screen with the reason, dates, ban ID and appeal when rejoining.
- [ ] `/punish <alt>` opens the menu; clicking a reason twice punishes.
- [ ] `/mute`, `/warn` (3 warnings mute for 1 hour) and `/kick` show the right messages.
- [ ] Give a staff member `vigil.protect`: they are flagged but not banned.
- [ ] After the first start, restart once. The console should say
      "Paper anti-xray is on (engine-mode 2)". With an x-ray pack, stone is full of fake ores.
- [ ] With a storage-ESP mod, chests inside a closed base show as stone from outside,
      and appear normally when you walk in.
- [ ] `/ac preview` shows the ban animation and the chat banner.
- [ ] With Item Scroller, mass moving items (scroll or shift-drag) stops working.

## Limitations

- No prediction engine, so subtle cheats that stay within vanilla limits
  (small speed boosts, 3.1-block reach, aim assist, kill aura with legit-looking
  rotations) are not caught. Blatant ones are.
- Freecam, player ESP and tracers can't be detected directly; see
  "X-ray, ESP and freecam".
- Auto clicker detection is alert-only by default because drag-clicking can
  legitimately exceed 25 CPS.
- Plugins that move players through raw packets must call `notifyImpulse`, or
  those players may be flagged.
