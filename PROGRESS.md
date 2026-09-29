# Progress

## Completed

- **Build**: Maven project targeting the Paper 1.21.4 API with Java 17 bytecode
  (Spigot/Paper 1.20–1.21.x). Version-specific API is accessed reflectively in
  `compat/ServerCompat`. CI workflow builds and tests every push.
- **Core**: validated immutable configuration with per-key fallback and warnings
  (`config/`); per-player state with 30-minute relog retention (`data/`);
  monotonic clock; environment probe with collision/fluid scans and
  chunk-safe voxel line-of-sight (`env/`); disturbance registry for pistons,
  explosions and wind charges.
- **Violation pipeline**: cancellable `VigilFlagEvent` → VL with decay → flag log
  → rate-limited clickable alerts → review cases → optional guarded automatic
  commands (off, dry-run by default). Debug streaming per player/check.
- **Checks (10)**: speed, flight, vertical, ground-spoof, timer, reach,
  hit-angle, wall-hit, block-reach, wall-interact. Each has suspicion buffers,
  lag protection, grace windows and lazy expensive exemptions, and runs behind
  a per-check circuit breaker.
- **Persistence**: async single-thread IO with a bounded queue; atomic writes;
  corrupt files moved aside, never deleted; player records, review queue,
  staff alert preferences, daily flag logs.
- **Commands/permissions** (1.x): `/vigil` with alerts, info, history, review,
  note, exempt, reset, status, reload, debug. Replaced in 2.0.0, see below.
- **API**: `VigilApi` service (exempt, notifyImpulse, VL lookup) and `VigilFlagEvent`.
- **Tests**: 50 unit tests including vanilla-physics simulations under network
  jitter, stalls and client catch-up. A one-off sweep of 10,000 simulated
  sessions gave zero false speed/timer flags with 100 % cheat detection.
- **Bugs found by the simulations and fixed**: stall credit lost across chained
  connection stalls; timer/speed credit reset at the end of grace windows;
  near-threshold latency jumps ratcheting the balance down; post-burst packet
  "tails" not covered. The stall credit design was reworked so it can only be
  spent by packet bursts, which also closes idle-banking abuse.
- **Self-review fixes**: position history is no longer cleared on teleport (it
  would have broken reach lag compensation); vertical no longer depends on the
  flight tracker when flight is disabled; flight sampler reordered so
  grounded players cost one block lookup; surroundings cache ages out.

## 1.1.0: moderation and branding

- `/ban`, `/tempban`, `/unban`, `/mute`, `/tempmute`, `/unmute`, `/warn`,
  `/kick` and `/punishments`, with preset reasons per command (tab completion),
  preset default durations, "No Reason" when none is given, login/chat/private
  message enforcement, staff broadcasts, and persistent `data/punishments.yml`.
- Requested messages: "You have banned player X for Reason for Time",
  "You have unbanned player X for Reason / No Reason", and anti-cheat alerts as
  "Player has been flagged for Reason" (each check has a readable reason such as
  "Kill Aura").
- Every message uses the `ᴠᴀɴɪʟʟᴀ sᴍᴘ »` prefix (configurable).
- 9 new tests (durations, presets, expiry, persistence across restarts, corrupt
  file handling, config consistency). 59 in total.

## 2.0.0: detection that bans, fewer commands, short config

Diagnosis of "it doesn't detect anything": alerts started at VL 2-3 and cases
only opened after that. Automatic punishment was off. Any wall next to the
player counted as "ground", which hid wall climbing and wall flying. Water
counted up to the full block, which hid walking on water. Every kind of damage
(poison, fire, fall) paused all movement checks for 1.5 s. And an op or `*`
permission silently bypassed everything.

- Support now means the exposed top of a collision box near the feet. Liquid
  surfaces use the real height. Damage no longer grants grace; knockback still
  does, through its velocity event.
- New checks, with ideas from Grim and NoCheatPlus (their code was not copied):
  velocity (anti-knockback), noslow, killaura (look ray vs. lag-compensated
  hitbox one tick later, plus multi-target per client tick), noswing,
  autoclicker, interact (scaffold: placing on a hidden face), fastplace, nuker,
  and NoFall by missing fall damage. Flight catches spider and Jesus.
- Faster: alerts from the first flag, flight window 0.5 s. Setbacks are on for
  speed, flight and step.
- Automatic ban at `ban-at`, 30 days, "Cheating (Flying)", kick with the ban
  screen, broadcast. Optional external ban command. `vigil.protect` exempts
  staff. Bans are enforced at login even with the moderation commands disabled.
- On Paper, attacks come from the packet-level pre-attack event, so plugin area
  damage never looks like reach or kill aura. Spear attacks (1.21.11) are
  skipped.
- Commands reduced to `/ac` (alerts, check, reset, debug, reload) and
  `/ban /unban /mute /unmute /warn /kick`. The review queue, `/tempban`,
  `/tempmute`, `/punishments` and automatic-command rules were removed
  (`/ac check` shows punishment history). Permissions simplified.
- The config was reduced from 405 to 165 lines. Tuning moved to optional
  `advanced:` keys. A 1.x config is migrated automatically, with a backup.
- Tests: 79, including 19 MockBukkit end-to-end scenarios (legit play never
  flagged; each cheat flagged; auto-ban, login denial, commands, migration).
  The stress simulations caught that a stricter speed leniency (1.2) would flag
  legit sprint-jumping, so it stays at 1.25.

## 2.1.0: x-ray, ESP, freecam and mace

- Paper anti-xray setup: on first start, if Paper's anti-xray is off, Vigil switches
  it on (engine-mode 2 with Paper's recommended lists, nether lists per nether
  world, off in the end). Only the relevant lines are edited, backups are made, it
  runs once, and an admin switching it off again is respected. Restart required.
- Hidden storage (anti ESP / anti freecam, Paper): when a chunk is sent, storage
  blocks the player can't see are sent as stone to that player; they are revealed
  within 8 blocks or on line of sight (48 blocks). Revealed blocks are read from the
  world, and traces are budgeted per chunk and per check.
- `mace` check: a smash after a single-move rise of 2+ blocks (fake fall) or while
  standing exactly on a block is cancelled and flagged. Research: the AntiMaceKill
  approach (impossible fall in a single tick + apex tracking).
- `xray` check (alert only): 5 hidden diamond/debris veins in a row found with a
  small average number of blocks mined per vein (hidden = every open side was dug
  by the player). Estimated for legit branch mining, from ore density: about 50-100
  blocks per vein.
- Research notes: Paper's `feature-seeds` only affects new chunks and can cut off
  features at old/new chunk borders, so it is documented, not switched on. Player
  ESP can't be hidden without tab-list side effects. Freecam can't be seen directly.
- Tests: 89 (6 new scenarios, 4 setup tests).

## Remaining / next steps

- Validate on a live server with a hacked client on an alt (see the README
  checklist) and tune `ban-at` values from real alerts.
- Prediction-based movement (Grim style) would catch subtle speed and strafe
  cheats, but it is a large project.
- Moderation ideas: IP bans, warning escalation, a Discord webhook for bans.
