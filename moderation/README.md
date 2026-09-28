# StaffModeration

A staff moderation plugin for Paper: **bans, chat mutes, voice-chat mutes, warnings and kicks**, with
a permanent punishment history, configurable messages/durations/permissions, an audit log, and
enforcement designed so punishments can't be slipped past by accident.

- **Server:** Paper 1.21.x or 26.x (Java 21+). Folia is not supported.
- **Storage:** SQLite (default, zero setup) or MySQL/MariaDB. Both drivers ship with Paper.
- **Voice chat:** [Simple Voice Chat](https://modrepo.de/minecraft/voicechat/) (optional). Without
  it, voice mutes are still recorded but can't be enforced, and staff are told so.

## Installing

1. Build with `./gradlew build` from the repository root, or use a released jar.
2. Drop `StaffModeration-<version>.jar` into `plugins/`. Install Simple Voice Chat too if you want voice mutes.
3. Start the server once, then edit `plugins/StaffModeration/config.yml` and `messages.yml`.
4. Apply changes with `/moderation reload` (storage changes need a restart).

HikariCP is downloaded by Paper on first start (it's listed under `libraries` in `plugin.yml`).

## Commands

| Command | Aliases | Permission | Description |
| --- | --- | --- | --- |
| `/ban <player> [duration] [reason] [-s]` | `tempban` | `moderation.ban` | Ban (permanent unless a duration is given) |
| `/unban <player> [reason] [-s]` | | `moderation.unban` | Lift a ban |
| `/mute <player> [duration] [reason] [-s]` | `tempmute` | `moderation.mute` | Block chat, private messages, signs and books |
| `/unmute <player> [reason] [-s]` | | `moderation.unmute` | Lift a mute |
| `/voicemute <player> [duration] [reason] [-s]` | `vmute`, `vcmute` | `moderation.voicemute` | Block voice chat |
| `/unvoicemute <player> [reason] [-s]` | `unvmute`, `vcunmute` | `moderation.unvoicemute` | Lift a voice mute |
| `/warn <player> [duration] [reason] [-s]` | | `moderation.warn` | Warn (duration = how long it counts towards escalation) |
| `/unwarn <id> [reason] [-s]` | `delwarn` | `moderation.unwarn` | Remove one warning |
| `/kick <player> [reason] [-s]` | | `moderation.kick` | Kick an online player (recorded in history) |
| `/history [player] [page]` | `punishments` | `moderation.history` / `.self` | Full punishment history |
| `/warnings [player]` | `warns` | `moderation.history` / `.self` | Active warnings with their IDs |
| `/check <player>` | `modcheck` | `moderation.check` | Current ban/mute/voice-mute status |
| `/moderation <reload\|info>` | `staffmod` | `moderation.admin` | Reload config, show status |

- **Players** are matched by exact name (case-insensitive) or UUID. Partial names are deliberately
  not accepted, so the wrong player can't be punished by a typo. Offline players can be punished
  if they have joined before, or (with `lookup.allow-unknown-players`) by a Mojang name lookup.
- **Durations**: `30s`, `15m`, `12h`, `7d`, `2w`, `3mo`, `1y`, combinations like `1d12h`, or
  `permanent`. Anything that starts with a digit but isn't a valid duration (`10`, `1x`) is
  rejected rather than silently becoming part of the reason.
- **`-s`** issues the punishment silently: no public broadcast, and staff notifications are marked
  `[silent]`. Needs `moderation.silent`.
- **Presets**: `/mute Steve spam` uses the `spam` preset's duration and reason from `config.yml`.
  Extra words are appended to the reason, and a typed duration still wins (`/mute Steve 2h spam`).

## Permissions

| Permission | Default | Grants |
| --- | --- | --- |
| `moderation.*` | op | Everything below except the two exemption nodes |
| `moderation.staff` | – | All moderator commands plus `notify` and `silent` (grant this to your staff group) |
| `moderation.<ban\|mute\|voicemute\|warn\|kick>` | op | Issue that punishment |
| `moderation.un<ban\|mute\|voicemute\|warn>` | op | Lift that punishment |
| `moderation.history`, `moderation.check` | op | Look up other players |
| `moderation.history.self` | everyone | View your own history and warnings |
| `moderation.notify` | op | Receive staff notifications (punishments, banned join attempts, escalations) |
| `moderation.silent` | op | Use `-s` |
| `moderation.admin` | op | `/moderation reload` and `info` |
| `moderation.limits.unlimited` | op | Ignore the duration limits below |
| `moderation.exempt` | **nobody** | Can't be punished by staff (the console still can) |
| `moderation.exempt.override` | **nobody** | May punish exempt players |

### Duration limits

`duration-limits` in `config.yml` caps how long staff may punish for, by permission tier. For example, give
helpers `moderation.limits.helper` to cap them at 1-day bans and 6-hour mutes. Staff get the most
generous tier they belong to. Staff in no tier, the console and `moderation.limits.unlimited` aren't
capped. An unreadable cap value is treated as 1 hour rather than "no cap".

## Enforcement: how bypasses are prevented

| Risk | What the plugin does |
| --- | --- |
| Punishments lost on restart or relog | Everything is stored in the database and reloaded at every login, before the player is allowed in. |
| Database down during login | Login is refused (`security.deny-login-on-database-error`, on by default). Operators can still join to fix it. If the database can't be opened at startup, or `storage` is misconfigured, the plugin stays enabled in this refusing state instead of disabling itself. |
| Another plugin re-allowing a banned login (whitelist plugins, etc.) | The ban is re-applied at `HIGHEST` priority and the attempt is logged. |
| Ban issued while the player is mid-login | Re-checked when they join; they're kicked immediately. |
| Punishments issued on another server sharing the database | Online players are re-synced every `security.sync-interval-seconds` (30s default); newly banned players are kicked. |
| Muted player using `/msg`, `/r`, `/me` … | Blocked commands are matched by their real identity: `/MSG`, `/minecraft:msg`, `/essentials:msg`, aliases such as `/whisper` or `/emsg`, and `/execute … run msg …` are all caught. |
| Chat relays (Discord bridges, older chat plugins) | The legacy `AsyncPlayerChatEvent` is cancelled too, so relays that only listen to it never see the message. |
| Plugins un-cancelling chat/commands/signs/books | Each is re-cancelled at `HIGHEST` priority and the attempt is logged. |
| Signs and books as a chat workaround | Blocked while muted (configurable). |
| Voice audio reaching groups, whispers or other voice addons | The mute handler runs first (highest priority) and drops the packet. Simple Voice Chat stops dispatching as soon as a handler cancels. |
| A shorter punishment accidentally replacing a longer one | Re-banning, re-muting or re-voice-muting is refused until the current one is lifted. Where several overlap, the longest always applies. |
| Race between a login's database read and a new punishment | The in-memory cache never drops a punishment issued after a read started, and a lifted punishment can't be revived by an older read. |
| Staff punishing the wrong or protected player | Exact names only, no self-punishment, `moderation.exempt` protection (remembered for offline players), and duration caps. |
| Staff-typed text injecting formatting or click events | Reasons and names are always inserted into messages as plain text. |

## History and logging

- **History**: nothing is ever deleted. Lifting a punishment marks it inactive and records who
  lifted it, when and why. `/history` shows every entry with its status (active, expired, removed
  by …, instant).
- **Audit log**: `plugins/StaffModeration/logs/moderation-YYYY-MM-DD.log` records every
  punishment, removal, escalation, blocked attempt (chat, commands, signs, books, voice, banned
  logins), bypass attempt by another plugin, database error, reload and restart. Old files are
  deleted after `logging.retention-days`. Entries are mirrored to the console when
  `logging.console` is on; warnings and errors always are.
- **Offline warnings** are shown to the player the next time they join.
- **Escalation**: `warnings.escalation` issues automatic punishments at exact warning counts
  (e.g. 3 → 1h mute, 5 → 1d ban). Only warnings that haven't expired or been removed count.

## Multiple servers

Point every server at the same MySQL database and give each a distinct `server-name`. Punishments
issued anywhere are enforced everywhere at the next login, or within `sync-interval-seconds` for
players already online. On a proxy network, install the plugin on every backend server.

## Limitations

- No IP bans or alt-account detection. IP addresses are not stored.
- `moderation.exempt` for offline players uses the value from their last join.
- Voice mutes support Simple Voice Chat only.

## Development

```sh
./gradlew :moderation:test   # unit tests (SQLite-backed storage tests included)
./gradlew :moderation:build  # plugin jar in moderation/build/libs
```

The code is split into Bukkit-free core logic (`model`, `util`, `cache`, `storage`, `log`,
`CommandMatcher`, argument and policy parsing), which the unit tests cover, and a thin Paper layer
(`enforce`, `command`, `service`, `voice`).
