# Vigil

Server-side anti-cheat for Spigot/Paper SMPs, built around one rule: **never
punish a legitimate player**. Vigil validates movement and interactions on the
server, suppresses itself whenever the situation is uncertain, and hands
clearly abnormal behaviour to **staff for manual review** instead of banning
automatically.

- 10 checks: speed, flight, vertical (step/high-jump), ground spoof (NoFall),
  timer, reach, hit angle, hit through walls, block reach, container
  interaction through walls.
- Violation levels (VL) with decay, rate-limited clickable staff alerts, a
  persistent per-player history, and a review queue with verdicts.
- Automatic commands are available but **off by default** and heavily guarded.
- No packet libraries, no client mods, no invasive methods: only the Bukkit
  API, plus optional Paper features detected at runtime.
- Fails safe: bad config values fall back to defaults, corrupt data files are
  moved aside (never deleted), and a check that keeps throwing is switched off.

---

## Contents

1. [Requirements](#requirements)
2. [Installation](#installation)
3. [Building from source](#building-from-source)
4. [How detection works](#how-detection-works)
5. [Checks](#checks)
6. [Configuration](#configuration)
7. [Commands](#commands)
8. [Permissions](#permissions)
9. [Staff workflow](#staff-workflow)
10. [Automatic commands (optional)](#automatic-commands-optional)
11. [Developer API](#developer-api)
12. [Files and fail-safe behaviour](#files-and-fail-safe-behaviour)
13. [Performance](#performance)
14. [Testing checklist](#testing-checklist)
15. [Known limitations](#known-limitations)
16. [Project structure](#project-structure)

---

## Requirements

| | |
|---|---|
| Server | Paper (recommended) or Spigot, **1.20 – 1.21.x** |
| Java | 17+ (1.20.5+ servers already require Java 21) |
| Dependencies | none |
| Folia | not supported (the plugin refuses to load there) |

Paper is recommended. On Paper, Vigil uses the client tick event to measure
client time exactly, which improves the timer check and lets the flight check
see stationary hovering. It also uses the per-player "chunk sent" state to
avoid flagging clients that are still waiting for chunks. On Spigot these
features are skipped automatically and every check stays lenient.

Version differences (such as `Attribute` becoming an interface in 1.21.3 and
potion effects being renamed in 1.20.5) are resolved reflectively at startup.
The console prints what was detected:

```
[Vigil] Compatibility: attributes 6/6, potion effects 5/5, chunk-sent API yes, client tick event yes
```

## Installation

1. Stop the server and drop `Vigil-<version>.jar` into `plugins/`.
2. Start the server. `plugins/Vigil/config.yml` is created with safe defaults.
3. Give your staff `vigil.*` (ops have it by default).
4. Run `/vigil status` and check that there are no config warnings.
5. Recommended: run for a week or two before considering any automatic
   commands. Review the cases and tune thresholds if a check is noisy on your
   server.

Updating: replace the jar. Your `config.yml` is never overwritten. Options added
in newer versions use their defaults until you add them.

## Building from source

```bash
mvn -B package          # compiles, runs the tests, writes target/Vigil-<version>.jar
```

The Paper API is fetched from `https://repo.papermc.io/repository/maven-public/`.
The GitHub Actions workflow (`.github/workflows/build.yml`) builds and tests
every push and uploads the jar as an artifact.

## How detection works

Every check follows the same pipeline:

1. **Cheap measurement** on every relevant event (a few arithmetic operations
   and at most a handful of block lookups).
2. **Built-in leniency.** Limits are derived from vanilla physics and then
   multiplied by a configurable leniency factor.
3. **Exemptions.** Many situations legitimately change how a player moves:
   creative/spectator, flying, elytra, riptide, vehicles, knockback, damage,
   explosions, pistons, wind charges, slime/beds, water, ladders, cobwebs, ice,
   potion effects and attribute modifiers, teleports, respawns, world changes,
   joining. They grant grace windows or extra allowance. The expensive checks
   (nearby entities, disturbances, chunk state) only run when a flag is imminent.
4. **Lag protection.** Nothing is flagged while the server TPS is low, shortly
   after a lag spike, while `/tick` has changed the tick rate, or while the
   player's *recent maximum* ping exceeds `max-ping-ms`. Ping is only ever used
   to make checks **more lenient**, never as evidence.
5. **Suspicion buffer.** Most checks need several suspicious events before a
   single flag is recorded.
6. **Flag → VL → alert → review.** A flag raises the check's violation level.
   Staff are alerted from `alert-vl`, a review case opens at `review-vl`, and
   VL decays over time.

Time is measured with a monotonic wall clock, not server ticks. Server lag,
clock corrections and client-side freezes can therefore never make a player
look faster than they are.

## Checks

| Check (id) | Detects | How | Key false-positive safeguards | Default action |
|---|---|---|---|---|
| **Speed** (`speed`) | Moving horizontally faster than possible | Distance travelled vs. a real-time "distance budget" (≈1 s burst) whose rate is vanilla sprint-jump speed × surface × potion/attribute factors × leniency | Surface memory (ice, slime, soul speed, head-hitters); knockback credit; connection-stall credit (only usable by packet bursts); liquids; grace windows | Flag only |
| **Flight** (`flight`) | Hovering, slow gliding, flying upwards | Physics state machine. Once rising stops, the player must descend ≥ `min-descent` per window; without an external push, nobody rises higher than their best jump | Time only counts while the client is ticking (frozen/lagging clients never accumulate airtime); ghost-block resync plus confirmation; platform entities (boats, shulkers, happy ghasts); levitation/slow falling/gravity attribute; chunks not yet sent | Flag only |
| **Vertical** (`vertical`) | Step/high-jump in one move | Rise per move vs. max(jump velocity, step height) + 1/16 + tolerance, using the player's jump_strength/step_height attributes and Jump Boost | Liquids, ladders, cobwebs, slime/beds, launches, levitation, disturbances | Flag only |
| **GroundSpoof** (`ground-spoof`) | Client claims to be on the ground in mid-air (NoFall) | Client ground flag vs. a lenient server-side collision scan | Ghost-block resync before flagging; platform entities; liquids/climbables; unsent chunks | Flag only |
| **Timer** (`timer`) | Client running faster than real time | Client ticks (Paper tick-end events, else movement packets) vs. real time with a capped balance | Connection stalls and client catch-up: unbankable time becomes credit usable only by bursts; tick-rate changes; lag protection | Flag only |
| **Reach** (`reach`) | Hitting from too far away | Eye → closest hitbox point, against the target's lag-compensated path (ping + 250 ms, interpolated) | Current and past positions; several eye heights; mob velocity tolerance; the `entity_interaction_range` attribute | **Cancels** hits beyond range + 2 blocks; flags beyond range + 0.5 |
| **HitAngle** (`hit-angle`) | Hitting targets outside the field of view | Angle between view and hitbox across the whole rotation arc of the attack tick (re-checked next tick) | Close targets ignored; lag-compensated boxes; flicks through the target are legitimate | Flag only |
| **WallHit** (`wall-hit`) | Hitting through solid walls | Voxel traces from every plausible eye to a 3×3×3 grid on every lag-compensated target box | Only full opaque blocks count; eye inside a block aborts; rate-limited | Flag only |
| **BlockReach** (`block-reach`) | Breaking/using blocks too far away | Eye → block box on the packet-driven interact event | Plugin-fired block events (vein miners, tree fellers) are never checked; the `block_interaction_range` attribute | **Denies** clicks beyond range + 1.5; flags beyond range + 1 |
| **WallInteract** (`wall-interact`) | Opening containers without line of sight | Voxel traces to a 3×3 grid on the clicked face | Only full opaque blocks count; configurable block list | Flag only |

Movement checks never cancel anything by default. `mitigate: true` enables a
setback to the last safe position, but it is off because rubber-banding a
legitimate player is worse than a delayed review.

## Configuration

`plugins/Vigil/config.yml` is fully commented. Invalid values, unknown keys and
typos are reported in the console and in `/vigil status`, and fall back to
their defaults. All durations are in milliseconds.

### Global sections

| Key | Default | Meaning |
|---|---|---|
| `general.enabled` | `true` | Master switch for all checks (commands/history/review keep working). |
| `general.passive-mode` | `false` | Never cancel or set back anything, regardless of `mitigate`. |
| `general.exempt-creative-and-spectator` | `true` | Skip creative/spectator players. |
| `general.disabled-worlds` | `[]` | Worlds without checks. |
| `general.use-client-tick-events` | `true` | Use Paper's client tick event when available. |
| `general.platform-entities` | `[BOAT, RAFT, MINECART, SHULKER, HAPPY_GHAST]` | Entity type names (contains) a player may stand on. |
| `general.debug` | `false` | Print check diagnostics to the console (noisy). |
| `lag-protection.min-tps` | `17.0` | No flags below this TPS. |
| `lag-protection.lag-spike-threshold-ms` / `lag-spike-grace-ms` | `400` / `3000` | A tick longer than the threshold suppresses flags for the grace period. |
| `lag-protection.max-ping-ms` | `400` | Players whose recent max ping is higher are not flagged. |
| `lag-protection.*-grace-ms` | 1500–5000 | Grace after join, respawn, teleport, world/gamemode change, velocity/damage, leaving vehicles, elytra, riptide. |
| `lag-protection.disturbance-radius` / `disturbance-grace-ms` | `8` / `3000` | Players near recent pistons, explosions and wind charges are not flagged. |
| `alerts.enabled` / `console` | `true` / `true` | Staff chat alerts / console copy. |
| `alerts.cooldown-ms` | `5000` | Min time between alerts per player+check (suppressed ones are counted). |
| `alerts.format` | see file | Placeholders `{player} {check} {vl} {detail} {ping} {tps} {suppressed}`. |
| `alerts.clickable` | `true` | Hover for evidence, click for `/vigil info`. |
| `violations.history-size` | `50` | Recent flags kept per player. |
| `violations.log-to-file` | `true` | Daily `logs/flags-YYYY-MM-DD.log`. |
| `violations.log-retention-days` | `0` | Delete older logs; `0` keeps them forever. |
| `review.enabled` / `notify` | `true` / `true` | Open cases at `review-vl` / notify `vigil.review` staff. |
| `review.max-evidence` / `max-cases` | `25` / `1000` | Evidence lines per case / cases kept (oldest *resolved* cases are archived, never open ones). |
| `punishments.*` | disabled | See [Automatic commands](#automatic-commands-optional). |
| `messages.*` | see file | Command/staff messages (`&` colour codes). |

### Options every check has

| Key | Meaning |
|---|---|
| `enabled` | Turn the check on/off. |
| `alert-vl` | VL at which staff alerts start. |
| `review-vl` | VL at which a review case opens (`0` = never). |
| `decay-per-minute` | VL removed per minute. |
| `vl-per-flag` | VL added per flag. |
| `buffer-threshold` | Suspicious events needed for one flag. |
| `mitigate` | Allow cancelling/setbacks (ignored in passive mode). |
| `actions` | Optional automatic commands (see below). |

### Check-specific options

| Check | Option | Default | Meaning |
|---|---|---|---|
| speed | `leniency` | 1.25 | Multiplier on the computed limit. |
| | `burst-ticks` / `extra-blocks` | 20 / 2.0 | Budget capacity: ticks of max speed plus constant blocks. |
| | `ice-`, `ceiling-`, `slime-`, `soul-speed-multiplier` | 3.2 / 1.8 / 2.0 / 1.8 | Limit multipliers after touching those surfaces. |
| | `surface-memory-ms` | 2000 | How long a surface keeps raising the limit. |
| | `velocity-credit` | 15.0 | Extra blocks per block/tick of received knockback. |
| flight | `hover-window-ms` / `min-descent` | 1000 / 1.0 | Required descent per window of airtime after rising stops. |
| | `ascend-tolerance` | 1.0 | Blocks allowed above the best possible jump. |
| | `confirm-ms` | 1000 | Suspicion must persist this long after the block resync. |
| | `impulse-max-rise-ms` | 4000 | Longest rise accepted after a push before normal rules apply. |
| vertical | `tolerance` | 0.1 | Extra blocks above the max single-move ascent. |
| ground-spoof | `vertical-tolerance` / `horizontal-tolerance` | 0.5 / 0.3 | Search area for a supporting block. |
| timer | `max-debt-ms` | 600 | Flag once the client is this far ahead of real time. |
| | `max-credit-ms` | 1000 | Idle time a client may bank. |
| | `stall-forgiveness-ms` | 2500 | Stall credit expires after this much normal flow. |
| reach | `leniency` / `cancel-leniency` | 0.5 / 2.0 | Blocks beyond the interaction range to flag / cancel. |
| | `lag-window-extra-ms` / `max-lag-window-ms` | 250 / 1000 | Lag compensation window (ping + extra, capped). |
| | `mob-leniency` | 0.6 | Extra tolerance for non-player targets. |
| hit-angle | `max-angle` / `min-distance` | 70 / 1.2 | Degrees outside the view / ignore closer targets. |
| wall-hit | `max-traces-per-second` | 10 | Performance guard per player. |
| block-reach | `leniency` / `cancel-leniency` | 1.0 / 1.5 | Blocks beyond the block interaction range to flag / deny. |
| wall-interact | `blocks` | containers | Materials checked (`*X` suffix, `X*` prefix, `*X*` contains). |

`cancel-leniency` can never be stricter than `leniency`: Vigil never cancels
anything it would not also flag.

## Commands

Main command `/vigil` (alias `/vgl`). Every sub-command has its own permission.

| Command | Permission | Description |
|---|---|---|
| `/vigil help` | `vigil.command` | List the commands you can use. |
| `/vigil alerts` | `vigil.alerts` | Toggle alerts for yourself (remembered across restarts). |
| `/vigil info <player>` | `vigil.info` | Live VLs, ping, exemptions, recent flags, open case. |
| `/vigil history <player> [page]` | `vigil.history` | Stored history, lifetime counts, notes and cases (works offline). |
| `/vigil review [list [all] [page]]` | `vigil.review` | Review queue (open cases by default). |
| `/vigil review view <id>` | `vigil.review` | Case details and evidence. |
| `/vigil review claim <id>` | `vigil.review` | Mark yourself as the reviewer. |
| `/vigil review resolve <id> <cheating\|legit\|inconclusive> [note]` | `vigil.review` | Close a case with a verdict. |
| `/vigil review open <player> [reason]` | `vigil.review` | Open a case manually (e.g. after a report). |
| `/vigil note <player> <text>` | `vigil.review` | Add a staff note to a player's record. |
| `/vigil exempt <player> <check\|movement\|combat\|interaction\|all> <seconds>` | `vigil.exempt` | Temporary exemption (max 24 h, not persisted). |
| `/vigil unexempt <player>` | `vigil.exempt` | Remove manual exemptions. |
| `/vigil reset <player> [check]` | `vigil.reset` | Reset violation levels. |
| `/vigil status` | `vigil.status` | Health, lag state, check states, IO queue, config warnings. |
| `/vigil reload` | `vigil.reload` | Reload config.yml (keeps the old config if the file is broken). |
| `/vigil debug <player> [check]` | `vigil.debug` | Stream live check measurements to you (toggle). |

## Permissions

| Node | Default | Description |
|---|---|---|
| `vigil.*` | op | All staff permissions below. **Does not include bypass.** |
| `vigil.command` | op | Use `/vigil`. |
| `vigil.alerts` | op | Receive and toggle alerts. |
| `vigil.info`, `vigil.history` | op | Inspect players. |
| `vigil.review` | op | Review queue and staff notes; also receives new-case notifications. |
| `vigil.exempt`, `vigil.reset` | op | Exempt players / reset VL. |
| `vigil.status`, `vigil.reload`, `vigil.debug` | op | Administration. |
| `vigil.bypass` | **false** | Exempt from **all** checks. |
| `vigil.bypass.<check-id>` | **false** | Exempt from one check, e.g. `vigil.bypass.speed`. |

Bypass nodes default to `false`, so operators *are* checked. Careful with
wildcard grants in your permission plugin: `*` includes `vigil.bypass`.
Bypass permissions are cached per player for 5 seconds.

## Staff workflow

1. **Alert** arrives: `[Vigil] Steve flagged Speed (VL 3) over by 4.1 blocks, avg 9.2 m/s, limit 9 m/s`.
   Hover for the location, ping and TPS. Click for `/vigil info Steve`.
2. **Investigate**: `/vigil info` shows all VLs and recent flags. Use
   `/vigil debug Steve speed` to watch the live measurements while you spectate.
3. **Review**: once `review-vl` is reached, a case opens with the recent
   evidence. `/vigil review` lists open cases; `claim`, then `resolve` with
   `cheating`, `legit` or `inconclusive` and a note. Punish with your usual
   tools if needed.
4. **Tune**: if you resolve cases as `legit`, look at the evidence lines to
   see which check misjudged, raise its leniency or threshold, then
   `/vigil reload`. `/vigil reset <player>` clears their VL.

## Automatic commands (optional)

Disabled by default (`punishments.enabled: false`). Even when enabled,
`dry-run: true` only logs what would run. A rule fires only if **all** of these
hold:

- the check's VL reached the rule's `vl`,
- the player was flagged by that check at least `min-flags-in-window` times
  within `window-ms` (default 5 in 10 minutes), so a single detection never
  triggers anything,
- no rule of that check ran for this player within `cooldown-ms` (default 30 min),
- the rule is higher than the last rule executed (the "episode" restarts when
  the VL decays below it),
- the player name contains only characters that are safe in a command.

```yaml
punishments:
  enabled: true
  dry-run: false
checks:
  flight:
    actions:
      - vl: 20
        commands: ["say {player} is being reviewed by staff"]
      - vl: 40
        commands: ["kick {player} Unusual movement detected - staff have been notified"]
```

Placeholders: `{player} {uuid} {check} {vl}`. Every executed (or dry-run)
command is written to the console and the flag log.

## Developer API

Other plugins (for example a custom weapons plugin with dashes, grappling hooks
or launch pads) can exempt players and listen to flags.

```java
RegisteredServiceProvider<VigilApi> rsp = Bukkit.getServicesManager().getRegistration(VigilApi.class);
if (rsp != null) {
    VigilApi vigil = rsp.getProvider();
    vigil.notifyImpulse(player);                            // treat like knockback
    vigil.exempt(player, CheckCategory.MOVEMENT, 2000);     // 2 s movement exemption
    vigil.exempt(player, CheckType.REACH, 500);
    double vl = vigil.getViolationLevel(player, CheckType.SPEED);
}
```

```java
@EventHandler
public void onFlag(VigilFlagEvent event) {        // sync, cancellable
    if (isInMyMinigame(event.getPlayer())) {
        event.setCancelled(true);                 // discard: no VL, alert, log or case
    }
}
```

Moving a player with Bukkit's `setVelocity`/`teleport` is already understood by
Vigil. Only movement applied outside the Bukkit API (raw packets, NMS) needs
`notifyImpulse`. Check ids and the `api` package are stable; add
`softdepend: [Vigil]` to your plugin.yml.

## Files and fail-safe behaviour

```
plugins/Vigil/
├── config.yml                      # never rewritten by the plugin
├── data/
│   ├── players/<uuid>.yml          # lifetime counts, recent evidence, staff notes
│   ├── cases.yml                   # review queue
│   ├── cases-archive.log           # resolved cases archived beyond review.max-cases
│   └── staff.yml                   # who turned alerts off
└── logs/flags-YYYY-MM-DD.log       # every flag, one line each
```

- **Invalid config value**: that value falls back to its default and a warning
  is shown. **Unreadable config.yml**: built-in defaults are used on startup; on
  `/vigil reload` the previous configuration stays active. The file is never
  modified.
- **Corrupt data file**: moved aside as `*.corrupt-<timestamp>`, never deleted.
  A fresh file is started. If it cannot even be moved, the review queue goes
  read-only for the session instead of overwriting it.
- All disk IO runs on a single background thread with a bounded queue. If the
  disk stalls, writes are dropped and counted (`/vigil status`); the server
  thread never blocks. Pending data is flushed on shutdown.
- **Check errors**: an exception inside a check never affects the event. A
  check that fails 20 times within a minute is disabled until `/vigil reload`.
- **Startup failure**: the plugin disables itself cleanly and logs why.
- Violation levels survive a relog for 30 minutes, so reconnecting cannot
  reset them.

## Performance

- Per movement packet: a few arithmetic operations and ≤ 20 block-type
  lookups, never loading chunks, with only small short-lived allocations.
- Per server tick per player: one position sample for lag compensation and one
  support probe for flight (usually a single block lookup; cached while
  standing still).
- Expensive work (entity searches, line-of-sight traces, block resyncs) only
  runs when a flag is imminent and is rate-limited.
- Permissions are cached, file IO is asynchronous, and nothing blocks the main
  thread.

## Testing checklist

Automated: `mvn test` runs 50 unit tests. They include physics simulations of
legitimate movement (sprint-jumping, head-hitters, ice/blue ice, slime, Speed
II, Jump Boost up to level 21, cliff falls, knockback launches, slime bounces,
frozen clients, clients waiting for chunks) under random network jitter,
connection stalls and client catch-up. They assert that none of it flags, and
that speed/timer/flight cheats are detected. A wider one-off sweep during
development (10,000 simulated 10-minute sessions on five harsh network
profiles) produced **zero** false speed/timer flags. It detected 100 % of
1.3–2× speed cheats (in ~2–7 s) and 1.1–1.5× timer cheats (in ~4–24 s).

In-game (recommended before relying on the plugin; use a test server and
`/vigil debug <you>`):

- [ ] Server starts; `/vigil status` shows all checks green and no config warnings.
- [ ] Plugin loads on your exact server version; the compatibility line in the console looks complete.
- [ ] **Normal play produces no flags**: sprint-jump on flat ground, under a 2-block ceiling, on packed/blue ice (with trapdoor head-hitters), on slime and soul sand with Soul Speed, and with Speed II.
- [ ] Jump with Jump Boost; step up slabs/stairs; climb ladders, vines and scaffolding; swim, use bubble columns and dolphin jumps.
- [ ] Take knockback from mobs, players, TNT, wind charges and a mace smash; get launched by slime and beds; ride pistons and slime flying machines.
- [ ] Elytra with rockets; trident riptide; boats on ice; horses; minecarts; ender pearls; chorus fruit; nether/end portals.
- [ ] Stand on boats, minecarts, shulkers and happy ghasts.
- [ ] Walk into a laggy area / use a network-lag simulator (clumsy, `tc netem`) with 300 ms ping and packet bursts: still no flags.
- [ ] Fight players and mobs at normal range, while strafing, and with 200+ ms ping: no reach/angle/wall-hit flags.
- [ ] Use vein miner / tree feller plugins: no block-reach flags.
- [ ] Set `/tick rate 30` (1.20.3+): no timer flags.
- [ ] Break a block under yourself while a ghost block exists (fast mining with high ping): no flight/ground-spoof flags.
- [ ] Alerts: appear for staff with `vigil.alerts`, are rate-limited, `/vigil alerts` toggles and survives a restart.
- [ ] Review: raise a VL past `review-vl` (a test client or temporarily low thresholds), check that a case opens, then view/claim/resolve it.
- [ ] `/vigil history <offline player>` works; `/vigil note` persists.
- [ ] Corrupt `config.yml` (bad YAML), run `/vigil reload`: old config kept, error shown.
- [ ] Corrupt `data/cases.yml`, restart: file moved aside, plugin works.
- [ ] Automatic commands: enable with `dry-run: true`, confirm the log line, and that nothing runs before `min-flags-in-window`.
- [ ] `/reload` or restart with players online: no errors, data saved.

## Known limitations

- Vigil deliberately avoids packet-level analysis, so some subtle cheats are
  out of scope: small reach increases (3.0 → 3.3), aim assistance, killaura
  with silent rotations, autoclickers, no-slow, scaffold, ESP/x-ray. Pair it
  with an ore-obfuscation feature such as Paper's anti-xray for x-ray.
- On Spigot, or for clients older than 1.21.2 connected through ViaVersion,
  the flight check only sees players who move or turn. A client hovering
  perfectly still is indistinguishable from a frozen client without Paper's
  tick event. Vanilla's own "flying is not enabled" kick still applies if
  `allow-flight=false`.
- Glides that sink faster than `min-descent` (1 block/s) are not flagged, on
  purpose. Clients waiting for chunks sink at ~2 blocks/s.
- Plugins that move players via raw packets/NMS without Bukkit events must use
  the API (`notifyImpulse`/`exempt`), or players may be flagged.
- Timings and thresholds were validated with physics simulations and unit
  tests, not yet on a live server population. Watch the review queue during
  the first days and tune if needed.

## Project structure

```
src/main/java/io/github/drepfy/vigil/
├── VigilPlugin.java            # bootstrap, wiring, lifecycle, fail-safe config loading
├── VigilApiImpl.java           # public API implementation
├── api/                        # public API: VigilApi, VigilFlagEvent, CheckType, CheckCategory
├── check/
│   ├── CheckContext.java       # shared gates: activation, lag protection, grace, circuit breaker
│   ├── movement/               # Speed, Flight, Vertical, GroundSpoof, Timer
│   ├── combat/                 # AttackSnapshot (lag compensation), Reach, HitAngle, WallHit
│   └── interaction/            # BlockReach, WallInteract
├── command/VigilCommand.java   # /vigil and tab completion
├── compat/ServerCompat.java    # reflective access to version/platform specific API
├── config/                     # ConfigLoader (validation), Settings, CheckSpec (defaults & ranges)
├── data/                       # PlayerData, PlayerDataManager, FlagRecord
├── env/                        # WorldProbe (collision/fluid scans, voxel traces), BlockTraits, DisturbanceRegistry
├── listener/                   # movement, combat, interaction, lifecycle, world activity, optional hooks
├── model/                      # pure logic, unit tested: SpeedBudget, TimerBalance, FlightTracker, Physics, geometry
├── review/                     # ReviewService, ReviewCase
├── storage/                    # IoExecutor, atomic files, flag log, player records
├── task/                       # TickTask (sampler), TpsMonitor
├── util/                       # Clock (monotonic), Text, Glob
└── violation/                  # ViolationService (flag pipeline), AlertService, PunishmentService, DebugService
```
