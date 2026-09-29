# Vigil

Anti-cheat and moderation for a Spigot/Paper SMP (built for **ᴠᴀɴɪʟʟᴀ sᴍᴘ**).

- **17 server-side checks**: speed, flight (incl. spider and walking on water),
  step, NoFall, timer, NoSlow, anti-knockback, reach, kill aura, no-swing,
  hitting through walls, auto clicker, block reach, scaffold, fast place, nuker
  and chest aura.
- **Automatic bans**: when a check's violation level reaches its `ban-at`, the
  player is banned for 30 days ("Cheating (Flying)"), kicked with a ban screen,
  and everyone is told. Staff see every detection live:
  `Steve has been flagged for Kill Aura (VL 3)`.
- **Few commands**: `/ac` for the anti-cheat, plus `/ban /unban /mute /unmute
  /warn /kick` with preset reasons.
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
| `/ban <player> [duration] [reason]` | `vigil.ban` | `/ban Steve Cheating` = 30 days (preset), `/ban Steve 3d Griefing`, `/ban Steve` = permanent, "No Reason" |
| `/unban <player> [reason]` | `vigil.ban` | Lift a ban (also automatic bans) |
| `/mute <player> [duration] [reason]` | `vigil.mute` | Blocks chat and `/msg`, `/r`, `/me`... |
| `/unmute <player> [reason]` | `vigil.mute` | |
| `/warn <player> [reason]` | `vigil.warn` | The player sees the warning and their count |
| `/kick <player> [reason]` | `vigil.kick` | |

`/ac` also works as `/anticheat` and `/vigil`. Durations: `30m`, `12h`, `7d`,
`2w`, `1mo`, `1y`, `perm`. Tab completion shows the preset reasons. If another
plugin also has `/ban`, use `/vigil:ban`.

## Permissions

| Permission | Default | |
|---|---|---|
| `vigil.*` | op | All of the permissions below except `vigil.protect` and `vigil.bypass` |
| `vigil.alerts` | op | See detections, auto-bans and staff punishments; `/ac alerts` |
| `vigil.check` | op | `/ac check` |
| `vigil.admin` | op | `/ac reload`, `/ac reset`, `/ac debug` |
| `vigil.ban`, `vigil.mute`, `vigil.warn`, `vigil.kick` | op | The moderation commands |
| `vigil.protect` | false | Cannot be punished by staff commands and is never auto-banned (still flagged) |
| `vigil.bypass` | false | Not checked at all, **only** if `anticheat.bypass-permission: true` (off so `*` permissions can't switch the anti-cheat off) |

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

**How a ban happens.** A check first collects evidence: several suspicious
events, a ghost-block resync for flight/NoFall, a second look one tick later for
kill aura. Only then does it *flag*. Each flag adds 1 to the player's violation
level (VL) for that check, and 1 decays every minute. When the VL reaches
`ban-at`, the player is banned. So a cheater is banned within seconds to a
minute, while the rare false flag decays away. Players with `vigil.protect` are
reported to staff instead. Nothing is banned in `advanced.passive-mode`.

Speed, flight and step also pull the player back (setback). Reach, block reach,
scaffold, nuker and chest aura cancel the action once the pattern is clear.

## Configuration

The default [`config.yml`](src/main/resources/config.yml) is short:

```yaml
prefix: "&b&lᴠᴀɴɪʟʟᴀ sᴍᴘ » &r"
anticheat:
  enabled: true
  alerts: true
  auto-ban: {enabled: true, duration: 30d, reason: "Cheating ({reason})", broadcast: true, command: ""}
  exempt-creative-and-spectator: true
  disabled-worlds: []
  bypass-permission: false
  checks:
    speed: {enabled: true, ban-at: 15}
    # ... one line per check; ban-at: 0 = alerts only
moderation: ...   # broadcast, default durations, muted commands, preset reasons
messages: ...     # every text players and staff see
```

- To ban with another plugin (LiteBans, EssentialsX...), set
  `auto-ban.command`, e.g. `"tempban {player} {duration} {reason}"`
  (`{duration}` is empty for permanent bans).
- A check can be switched off with `speed: false`.
- Invalid values fall back to their defaults and are listed in the console and
  after `/ac reload`. The file is never rewritten (except the one-time 1.x migration).

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

`mvn test` runs 79 tests. 19 of them are end-to-end scenarios on a simulated
server (MockBukkit). They check that legit sprint-jumping, wall jumps, stairs,
bridging, knockback, falls with damage, normal fights and fights against a
strafing target are **never** flagged. They also check that flying, speed,
walking on water, reach, kill aura, no-swing, auto clicking, anti-knockback (also
mid-combo), NoFall and scaffold **are** flagged, and that a flyer is auto-banned,
kicked and refused at login. Physics simulations with random network jitter,
lag spikes and frozen clients guard the speed, timer and flight limits.

On a test server, before going live:

- [ ] Play normally for a while: sprint-jump, ice, slime, ladders, swimming,
      elytra, boats, horses, pearls, knockback, fighting mobs and players. `/ac check <you>` shows no violations.
- [ ] With a hacked client on an alt, try fly, speed, kill aura, reach, NoFall and
      scaffold. You should get alerts, then an automatic ban.
- [ ] `/unban <alt>`, `/ban <alt> Cheating` (30 days), `/mute`, `/warn`, `/kick`
      show the right messages. The banned alt sees the ban screen when rejoining.
- [ ] Give a staff member `vigil.protect`: they are flagged but not banned.

## Limitations

- No prediction engine, so subtle cheats that stay within vanilla limits
  (small speed boosts, 3.1-block reach, aim assist, kill aura with legit-looking
  rotations) are not caught. Blatant ones are. Use Paper's anti-xray for x-ray.
- Auto clicker detection is alert-only by default because drag-clicking can
  legitimately exceed 25 CPS.
- Plugins that move players through raw packets must call `notifyImpulse`, or
  those players may be flagged.
