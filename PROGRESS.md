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

## 2.2.0: cave anti-xray, re-hiding, hacked clients, inventory macros, ban animation

- Block hider: diamonds and ancient debris (found by scanning a chunk snapshot off the
  main thread, cached until a block changes) are hidden like storage. Anything that
  goes out of sight is hidden again (after two checks without line of sight,
  with a distance margin), and only if the block is still of a hidden type. The
  view direction is not used, so turning around never flickers.
- Paper anti-xray lists now include `air` (fake caves, Paper's recommendation).
  Lists written by 2.1.0 are upgraded only if still unchanged.
- Client check: kicks known hacked-client brands and channels, and world
  downloaders; optionally every modded client. Research: plugins that name
  individual mods use the sign-translation trick, fixed in Minecraft 26.1 and blocked
  by cheat clients, so it is not used.
- Inventory check: click bursts per client tick and per second are refused (1 s
  pause) and flagged; InvMove is flagged. Drops and number-key swaps are not counted
  (key repeat is legit).
- Ban animation: harmless lightning, explosion particles, thunder, a BANNED title and
  a chat banner. The player is frozen and kicked after 3 s (the ban is stored at
  once). `/ac preview` shows it without banning.
- Tests: 95.

## 2.3.0: preset times per offence, new ban screens, punish menu, warnings, Discord

- Preset reasons have a time per offence ("7d, 30d, perm"; the last one repeats).
  The offence number comes from the punishment history (same type, same reason or
  reason + details), so the storage format is unchanged. Punishments lifted as a
  mistake or after an appeal (unban reason with "false", "mistake" or "appeal") don't
  count. `/ban` or `/mute` alone lists the presets with their times.
- Automatic bans use a ladder too (`auto-ban.duration: 30d, perm`), counting earlier
  automatic bans for any reason. The anti-cheat ban screen uses the same count.
- Ban screens: temporary, permanent and anti-cheat versions with reason, offence
  number, staff, date, unban date, ban ID and appeal text (`moderation.appeal`,
  `moderation.date-format`). The countdown rounds up, so a new 7 day ban says 7 days.
- `/punish <player>`: chest menu with ban/mute/warn/kick tabs (only the ones the
  staff member may use), presets with their times and the player's next step, a
  confirm click, unban/unmute and history. It runs the normal commands, so all
  rules, messages and escalation apply.
- Warning escalation (`3: mute 1h`, `5: ban 1d`, `7: ban 7d`; kick also possible) and
  warning expiry (30 days). Escalation never shortens a longer ban or mute.
- Discord webhook (off by default): punishments, auto-bans and optionally alerts, as
  embeds, on a background thread with a small queue; mentions are disabled.
- Config upgrader: 2.x configs get new options with comments; values still at an old
  default (ban screen, single preset times, `auto-ban.duration: 30d`) are replaced;
  edited values are kept. The old file is kept as `config-before-<version>.yml`.
- Tests: 108 (ladders, offence counting, ban screens, Discord JSON, config
  parsing and upgrading, and scenarios for escalation, the preset list, warnings,
  the punish menu and the join-time ban screen).

## 2.4.0: no menus, professional messages, timed warnings, LuckPerms nodes

- Removed `/punish` (the only menu), `/ac reset` and `/ac debug`. `advanced.debug`
  still prints check values to the console.
- Messages rewritten: no emoji, no words in capitals ("Banned", "You are banned from
  this server."). The auto-ban notice is headed "Vigil Anti-Cheat". Unedited 2.2/2.3
  messages are upgraded automatically (the old bundled configs ship in the jar under
  `upgrade/` so the upgrader can recognise them); edited ones are kept.
- `/warn <player> <time> [reason]`: the time is required, 1h to 10d
  (`moderation.warn-time`). A warning counts until its time runs out; pre-2.4
  warnings count for `warnings-expire-after` (30d). `/unwarn` removes the newest
  active warning (history kept).
- Mute, unmute, warn and unwarn show a bold action bar for about 5 seconds (Paper
  Adventure API, Spigot fallback).
- Permissions: every command has its own node (`vigil.unban`, `vigil.unmute`,
  `vigil.unwarn`, `vigil.reload`, `vigil.preview`), grouped in `vigil.staff` and
  `vigil.admin`; per-check `vigil.bypass.<check>` nodes are declared in plugin.yml.
  README has LuckPerms group commands.
- Tests: 113.

## 2.4.1: full review, bug fixes

Every source file was read again. Fixed:
- After a restart, players with an active (timed) warning were loaded as muted.
- Ban screens and chat mute messages are built on login/chat threads and read the
  punishment list while the server thread could change it; a rare error there could
  let a banned player in. The punishment list is now a concurrent map, the date
  formatter cache is thread-safe, and the login is refused before the screen is built.
- "{offence}" counted by the typed reason ("Cheating fly" = 1st offence) while the
  length used the preset ("Cheating", 2nd step). Both now use the preset
  (`ReasonPreset.matches`: the preset, "xray" for X-Ray, or the preset plus details).
- `Durations.parse` overflowed on huge numbers (e.g. 999999999y) instead of capping.
- The action bar called Paper's Adventure API directly, which could stop the class
  from loading on Spigot and break /mute, /warn, /unmute and /unwarn there.
- Mod channels registered off the main thread could not be kicked.
- IO work dropped by a full queue left record loads (joins, /ac check) waiting forever,
  and a dropped punishment save was forgotten; now they complete/retry.
- A quick relog while the player record was loading could attach two records.
- Paper anti-xray setup failed on filesystems without atomic moves.
- /ac check labels bans replaced by a newer ban as "[replaced]" instead of "[lifted]".
- Tests: 118 (each fix has a test that fails without it).

## 2.5.0: real-server fly fix, fast bans, Vɪɢɪʟ branding

A user test on a real server: flying at 20 m/s only produced Speed flags, slowly,
and Flight never flagged. Cause, found by making the tests behave like Paper:
- When a plugin changes the destination of a move (`setTo`, Vigil's setback), Paper
  teleports the player and fires `PlayerTeleportEvent`. MockBukkit does not, so the
  tests never saw it. Vigil treated its own setback as a real teleport: 1 s of
  teleport grace (all movement checks off), flight tracking reset, and the setback
  point wiped. Speed flagged, pulled back, went blind; flight never got 1 s of
  airtime. Now every setback is marked and its teleport event is recognised
  (`LifecycleListener.isOwnSetback`): no grace, no reset.
- The setback point followed a speeder (updated every 250 ms on the ground). It now
  only advances while the speed budget is healthy, no movement flag is recent and
  no flight suspicion is open.
- After a speed flag the full 1.5 s burst budget was given back; now a quarter.
- Severity weights (`ViolationService.flag(..., weight)`, 1-3): speed by how many
  times over the limit, flight repeats within 10 s count 2 (and skip the confirm
  step), reach by the excess distance, kill aura by the missed angle.
- Test harness: `paperMove` reproduces CraftBukkit's move handling. New time-bounded
  hacked-client scenarios (fly fast/hover/up/glide/ground-spoof/after walking, ground
  speed, 4.5-block aura, no-rotation aura) with measured ban times of 2.3-9.6 s.
  Tests that spawned players floating in mid-air were made realistic.
- Branding: `messages.brand` ("Vɪɢɪʟ", gradient), `{brand}` in any message, hex
  colours (`&#RRGGBB`) and `<gradient:#a:#b...>` everywhere (legacy `§x` form, Paper
  action bars parse it). 2.4 configs are upgraded (bundled `upgrade/config-2.4.yml`).
- Tests: 131.

## 2.6.0: Discord bot and tickets

- **Tickets** (`ticket/`): `/ticket`, `/report`, `/tickets` with claim, close and
  teleport; `data/tickets.yml` (atomic writes, corrupt file moved aside, closed
  tickets purged after `tickets.keep-closed`). Observers tell in-game staff, the
  player (also after a relog: unread replies and closes) and the Discord bot about
  every change.
- **Discord bot** (`discord/`), no extra libraries:
  - `DiscordRest`: one background thread, per-route rate limits
    (`X-RateLimit-*`), 429 retry, retries on 502-504 and connection errors,
    multipart upload for transcripts.
  - `DiscordGateway`: Java's WebSocket; hello, identify, heartbeats with dead
    connection detection, resume (op 6 to `resume_gateway_url`) after drops and
    op 7, fresh identify after op 9 / 4007 / 4009, backoff 1-60 s, stops on 4004
    and similar, retries without Message Content after 4014. The connection is
    stored the moment it opens (Discord's first message arrives right away; it
    could otherwise be handled first and the login silently skipped).
  - `DiscordInteractions`: guild slash commands (registered on READY),
    autocomplete (staff only), buttons, ticket forms (also the newer "label"
    layout). Staff = a staff role or Administrator. Punishment commands run the
    real `ModerationCommand` through a proxy `CommandSender` (`RemoteSender`,
    `RemoteStaff` marker: staff name "Bob (Discord)", cannot punish
    `vigil.protect`).
  - `DiscordTickets`: private channels with permission overwrites, intro with
    Claim/Close, messages both ways (`TicketMessage.Origin` prevents echoes),
    channel deleted 10 s after close, transcript to the log channel, channel
    deleted on Discord closes the ticket, catch-up after reconnecting.
  - Punishments and alerts go to the bot's channels when set, else the webhook
    (`DiscordNotifier.BotChannel`). Chat bridge with batching; text from Discord
    is shown as typed (no colour codes) and never pings.
- Settings: `discord.bot` (IDs validated, `Bot ` prefix removed, token never in
  `toString`), `tickets`; 2.5 configs upgraded with comments
  (`upgrade/config-2.5.yml`).
- Tests: 179 (fake Discord HTTP server, fake WebSocket, ticket scenarios).

## Remaining / next steps

- Validate on a live server with a hacked client on an alt (see the README
  checklist) and tune `ban-at` values from real alerts.
- Prediction-based movement (Grim style) would catch subtle speed and strafe
  cheats, but it is a large project.
- Moderation ideas: IP bans and alt detection.
- Discord: linking Discord accounts to Minecraft accounts (so ban appeals can be
  verified), and DMs to people whose ticket was closed.
- Without Paper's client tick events (Spigot, Paper before 1.21.2) a flyer who hovers
  completely still sends no move events, so its airtime is not counted.
- When `auto-ban.command` hands bans to another plugin, Vigil doesn't store them,
  so the auto-ban ladder always uses its first step.
