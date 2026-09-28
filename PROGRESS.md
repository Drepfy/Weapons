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
- **Commands/permissions**: `/vigil` with alerts, info, history (offline), review
  (list/view/claim/resolve/open), note, exempt/unexempt, reset, status, reload,
  debug. Tab completion; per-check bypass nodes default to false.
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

## Remaining / next steps

- Validate on a live test server (see the checklist in README) and tune
  defaults from real review verdicts. Only simulations and unit tests have
  been run so far. The Paper/Spigot repositories were unreachable from the
  build environment, so the jar was compiled against a local build of the
  Paper 1.21.4 API from source.
- Integration tests with MockBukkit (listener wiring, commands).
- Possible further checks with low false-positive potential: nuker (many
  instant breaks per second), boat/vehicle fly, jesus (liquid walking).
- Optional per-world threshold overrides and localisation files.
- Optional SQL storage for networks with several servers.
