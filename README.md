# Vigil

Anti-cheat and moderation for a Spigot/Paper SMP (built for **ᴠᴀɴɪʟʟᴀ sᴍᴘ**).

> This repository also has the server's **[Lifesteal plugin](lifesteal/README.md)**
> (`lifesteal/`, released as `release/Lifesteal.jar`) and **[Combat plugin](combat/README.md)**
> (combat timer, Ender Pearl cooldown, elytra/riptide rules and spawn safe zones; `combat/`, released as `release/Combat.jar`)
> and **[Legendary plugin](legendary/README.md)** (five legendary weapons with abilities that cannot be duplicated or
> stored; `legendary/`, released as `release/Legendary.jar`).

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
- **Ban animation**: lightning, an explosion, thunder and a large red "Banned"
  title on the cheater's screen, then a "Vigil Anti-Cheat" notice in chat for
  everyone.
- **Fast bans for blatant cheats**: fly hacks are banned in about 3 seconds,
  speed hacks in about 5 (see "How a ban happens").
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
- **Timed warnings**: every warning needs a time from 1 hour to 10 days
  (`/warn Steve 1d Spam`) and stops counting after it. 3 active warnings = muted
  for 1 hour, 5 = banned for 1 day, 7 = banned for 7 days (configurable).
  `/unwarn` removes one.
- **Hotbar notices**: being muted, unmuted, warned or having a warning removed is
  shown in bold above the hotbar as well as in chat.
- **Discord** (optional): bans (also automatic ones), mutes, warnings and kicks
  posted to a channel through a webhook, or a **bot** that also posts anti-cheat
  alerts, runs tickets, gives staff `/ban`, `/mute`, `/warn`, `/lookup`... in
  Discord and bridges chat (see "Discord").
- **Tickets**: players open a ticket with `/ticket <message>` or report a player
  with `/report`; staff answer with `/tickets`. With the bot, every ticket also
  gets a private Discord channel, and people on Discord can open tickets (support,
  reports, ban appeals) with buttons.
- **Few commands, no menus**: `/ac` for the anti-cheat, plus `/ban /unban /mute
  /unmute /warn /unwarn /kick` with preset reasons, and `/ticket /report /tickets`.
- **LuckPerms ready**: every command has its own permission node, plus
  `vigil.staff` and `vigil.admin` sets (see "Permissions").
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

**Updating from 2.x:** just replace the jar. (2.6.0 adds tickets and the Discord
bot; both work without any setup in game, the bot stays off until you add a token.) (2.4.0 removed `/punish`,
`/ac reset` and `/ac debug`; `/unban`, `/unmute` and `/ac reload` now have their
own permissions, which ops and `vigil.*` already include.) On start, Vigil adds the new
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
| `/ac reload` | `vigil.reload` | Reload `config.yml` |
| `/ac preview` | `vigil.preview` | Watch the ban animation on yourself (you are not banned) |
| `/ban <player> [duration] [reason]` | `vigil.ban` | `/ban Steve Cheating` = preset time (7 days, then 30 days, then permanent), `/ban Steve 3d Griefing` = your own time, `/ban Steve` = permanent, "No Reason". `/ban` alone lists the presets |
| `/unban <player> [reason]` | `vigil.unban` | Lift a ban (also automatic bans) |
| `/mute <player> [duration] [reason]` | `vigil.mute` | Blocks chat and `/msg`, `/r`, `/me`... `/mute` alone lists the presets |
| `/unmute <player> [reason]` | `vigil.unmute` | |
| `/warn <player> <time> [reason]` | `vigil.warn` | The time is required, 1h to 10d: `/warn Steve 1d Spam`. Enough active warnings mute or ban |
| `/unwarn <player> [reason]` | `vigil.unwarn` | Remove the player's newest active warning |
| `/kick <player> [reason]` | `vigil.kick` | Kicked with the kick screen |
| `/ticket <message>` | `vigil.ticket` (everyone) | Open a ticket, or add to your open ticket. Also `/ticket view`, `/ticket close`, `/ticket list`, `/ticket reply <id> <message>` |
| `/report <player> <reason>` | `vigil.report` (everyone) | Report a player; staff see where you were |
| `/tickets` | `vigil.tickets` | Open tickets. Also `/tickets view <id>`, `reply <id> <message>`, `claim <id>`, `close <id> [reason]`, `tp <id>` |

`/ac` also works as `/anticheat` and `/vigil`, `/ticket` as `/support`. Durations: `30m`, `12h`, `7d`,
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
   You are banned from this server.
     Time remaining: 6 days 23 hours

  Reason: Cheating (1st offence)
  Banned by: Admin
  Date: 30 Sep 2026, 14:05
  Expires: 07 Oct 2026, 14:05
  Ban ID: #42

  Appeal: Ask a staff member on our Discord
  ────────────────────────────
```

- `ban-screen`: temporary bans (with the countdown and the unban date).
- `ban-screen-permanent`: permanent bans.
- `ban-screen-anticheat`: automatic bans ("You have been banned by Vɪɢɪʟ", what
  was detected).
- `kick-screen`: kicks.

Set your Discord invite or website in `moderation.appeal`, and the date style in
`moderation.date-format`. Placeholders: `{player} {staff} {reason} {duration}
{expires} {expires-date} {date} {id} {offence} {appeal} {brand}`.

### The Vɪɢɪʟ name and colours

Ban messages show the plugin's name in small capitals with a colour gradient. It
is one setting, used everywhere as `{brand}`:

```yaml
messages:
  brand: "<gradient:#FF3C3C:#FFA53C>&lVɪɢɪʟ</gradient>"
```

Every message accepts the normal `&` codes, hex colours (`&#FF8800`) and gradients
with two or more colours (`<gradient:#FF0000:#FFFF00:#00FF00>text</gradient>`).
Bold/italic codes inside a gradient are kept.

### Warnings

```yaml
moderation:
  warn-escalation:
    3: mute 1h     # the 3rd warning mutes for 1 hour
    5: ban 1d
    7: ban 7d      # also: "kick", "mute perm", "ban perm"
  warn-time:       # every warning needs a time in this range
    min: 1h
    max: 10d
```

`/warn Steve 1d Spam` gives a warning that counts for 1 day. `/warn Steve Spam`
(no time), `30m` or `11d` are refused. Warnings from before 2.4.0 (which had no
time) count for 30 days. `/unwarn Steve False_Warning` removes the newest one.

### Hotbar notices

Muting, unmuting, warning and removing a warning also show a bold line above
the player's hotbar for about 5 seconds (`mute-actionbar`, `unmute-actionbar`,
`warn-actionbar`, `unwarn-actionbar` in `messages`; set one to `""` to turn it
off).

## Tickets

- A player types `/ticket My house was griefed`. Staff online get
  `[Ticket #1] Steve opened a ticket (Support): My house was griefed` (click it
  to read the ticket).
- Staff answer with `/tickets reply 1 On my way` (or click **[Reply]** under
  `/tickets view 1`). The player sees the answer in chat; if they are offline,
  they are told when they next join.
- The player answers with `/ticket <message>` (or `/ticket reply <id> <message>`
  when they have more than one ticket).
- `/tickets claim 1` shows the player who is handling it, `/tickets tp 1` takes
  you to where the ticket was opened, `/tickets close 1 Fixed` closes it (the
  player can also `/ticket close`).
- `/report Cheater flying over my base` opens a report ticket about that player,
  with the reporter's location. One report a minute, at most 3 open tickets per
  player.
- Staff are told how many tickets are open when they join. Tickets are kept in
  `data/tickets.yml`; closed ones are deleted after 30 days (`tickets.keep-closed`).

With the Discord bot set up, every ticket also gets a private channel (see below),
and replies go both ways: staff can answer from Discord or in game.

```yaml
tickets:
  enabled: true
  reports: true        # /report
  keep-closed: 30d     # "perm" keeps closed tickets forever
```

## Discord

There are two ways, and you can use either or both.

### Option 1: webhook (ban log only, 1 minute)

Create a webhook (channel settings → Integrations → Webhooks → New Webhook →
Copy Webhook URL), then:

```yaml
discord:
  webhook: "https://discord.com/api/webhooks/..."
  send-punishments: true   # bans, mutes, warnings, kicks, unbans
  send-auto-bans: true     # automatic anti-cheat bans
  send-alerts: false       # every anti-cheat alert (a lot)
```

### Option 2: the Vigil bot (ban log, alerts, tickets, staff commands, chat)

The bot runs inside the plugin; nothing else needs to be installed or hosted.

1. **Create the bot.** Open <https://discord.com/developers/applications> →
   **New Application** (name it e.g. "Vigil") → **Bot** → **Reset Token** → copy
   the token. On the same page, switch on **Message Content Intent** (needed to
   read messages typed in ticket and chat channels).
2. **Invite it.** **OAuth2 → URL Generator**: tick `bot` and
   `applications.commands`, then the permissions *View Channels, Send Messages,
   Embed Links, Attach Files, Read Message History, Manage Channels, Mention
   Everyone*. Open the link and pick your server. (If you skip this step, the
   console prints a ready-made invite link once the token is set.)
3. **Copy the IDs.** In Discord: Settings → Advanced → **Developer Mode** on.
   Then right-click → **Copy ID** on: your server, your staff role(s), and the
   channels you want to use. Make a category for tickets (right-click it → Copy
   Category ID). Keep the punishments, alerts and ticket-log channels visible to
   staff only.
4. **Fill in `config.yml`:**

   ```yaml
   discord:
     bot:
       enabled: true
       token: "paste the token here"
       server-id: "123456789012345678"
       staff-roles: ["234567890123456789"]      # admins always count as staff
       punishments-channel: "345678901234567890" # bans, mutes, warnings, kicks
       alerts-channel: "456789012345678901"      # every anti-cheat alert
       tickets-category: "567890123456789012"    # ticket channels are made here
       ticket-log-channel: "678901234567890123"  # closed tickets with the conversation
       chat-channel: ""                           # two-way Minecraft <-> Discord chat
       read-messages: true
       ping-staff: true                           # mention staff roles on new tickets
       status: "Watching {online} players"
   ```

   Leave a channel empty to switch that feature off. Then `/ac reload` (or
   restart). The console says `Discord: the bot is online as Vigil.`
5. **Post the ticket buttons:** in the channel where people should open tickets,
   type `/ticketpanel`.

Keep the token secret: anyone who has it controls the bot. If it ever leaks, press
**Reset Token** again and paste the new one.

**What the bot does**

| | |
|---|---|
| Ban log | Every ban (also automatic ones), mute, warning, kick and unban in `punishments-channel` |
| Alerts | Anti-cheat alerts in `alerts-channel` (grouped, up to 10 per message) |
| Tickets | Each ticket gets a private channel (`ticket-12-steve`) only staff roles, the bot and the person who opened it can see. Staff roles are mentioned. Messages go both ways between the channel and the game. **Claim** and **Close** buttons; a closed ticket's channel is deleted after 10 seconds and the whole conversation is posted to `ticket-log-channel` as a file |
| Ticket panel | `/ticketpanel` posts **Support**, **Report a player** and **Ban appeal** buttons. Each opens a short form; ban appeals show the player's active ban to staff |
| Staff commands | `/ban`, `/unban`, `/mute`, `/unmute`, `/warn`, `/unwarn`, `/kick` work exactly like in game (same presets and times, same ban screens), shown as "Bob (Discord)". `/lookup <player>` shows bans, mutes, warnings and recent punishments. `/tickets` lists open tickets. Player names, times and reasons are suggested while typing |
| In ticket channels | `/reply <message>`, `/claim`, `/close [reason]` (the person who opened a ticket may `/reply` and `/close` too) |
| For everyone | `/online` and `/status` (players, TPS, open tickets) |
| Chat bridge | With `chat-channel` set: game chat, joins and leaves appear in Discord; Discord messages appear in game as `Discord \| Bob: hi` |

Only members with a staff role (or Administrator) can use the staff commands and
buttons; the bot checks this itself. To also hide the commands from other members,
use Server Settings → Integrations → Vigil.

**If something does not work**, the console says why (at most every few minutes):

- *the bot token is wrong*: reset the token and paste the new one.
- *"Message Content Intent" is switched off*: switch it on (step 1), or set
  `read-messages: false`; staff then answer tickets with `/reply`.
- *Missing Permissions* / *Missing Access*: give the bot the permissions from
  step 2 in that channel or category.
- *the bot is not in the server*: use the invite link printed in the console.
- Slash commands don't appear: re-invite with `applications.commands` and
  restart Discord (Ctrl+R).

Everything is sent in the background: if Discord is slow or down, nothing on the
server waits or breaks. Mentions like `@everyone` in reasons, chat or tickets never
ping anyone.

## Permissions

Every node is registered with the server, so LuckPerms suggests them all (tab
completion and the web editor).

| Permission | Default | |
|---|---|---|
| `vigil.*` | op | `vigil.staff` + `vigil.admin` (not `vigil.protect` or `vigil.bypass`) |
| `vigil.staff` | op | Moderator set: all of the nodes from `vigil.alerts` to `vigil.tickets` below |
| `vigil.admin` | op | Admin set: `vigil.reload` and `vigil.preview` |
| `vigil.alerts` | op | See detections, auto-bans and staff punishments; `/ac alerts` |
| `vigil.check` | op | `/ac check` |
| `vigil.ban` | op | `/ban` |
| `vigil.unban` | op | `/unban` |
| `vigil.mute` | op | `/mute` |
| `vigil.unmute` | op | `/unmute` |
| `vigil.warn` | op | `/warn` |
| `vigil.unwarn` | op | `/unwarn` |
| `vigil.kick` | op | `/kick` |
| `vigil.tickets` | op | `/tickets`: see, answer, claim and close tickets, and hear about new ones |
| `vigil.reload` | op | `/ac reload` |
| `vigil.preview` | op | `/ac preview` |
| `vigil.ticket` | true | `/ticket` (open tickets) |
| `vigil.report` | true | `/report` |
| `vigil.protect` | false | Cannot be punished by staff commands and is never auto-banned (still flagged) |
| `vigil.bypass` | false | Not checked at all (and sees hidden storage), **only** if `anticheat.bypass-permission: true` (off so `*` permissions can't switch the anti-cheat off) |
| `vigil.bypass.<check>` | false | Skip one check, e.g. `vigil.bypass.flight` (same condition) |

### LuckPerms setup

Example groups (type these in the console, or use `/lp editor`):

```
lp creategroup helper
lp group helper permission set vigil.alerts true
lp group helper permission set vigil.check true
lp group helper permission set vigil.warn true
lp group helper permission set vigil.mute true
lp group helper permission set vigil.kick true
lp group helper permission set vigil.tickets true

lp creategroup mod
lp group mod parent add helper
lp group mod permission set vigil.staff true

lp creategroup admin
lp group admin parent add mod
lp group admin permission set vigil.* true
lp group admin permission set vigil.protect true

lp user Steve parent add mod
```

A helper can warn, mute and kick but not ban or undo punishments; a mod can do
every punishment; an admin can also reload and cannot be punished.

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
`ban-at`, the player is banned. Players with `vigil.protect` are reported to staff
instead. Nothing is banned in `advanced.passive-mode`.

Blatant cheating counts more, so it is banned in seconds while a rare false flag
decays away:
- **Speed**: a flag counts as many times as the player was over the limit (twice
  the allowed speed = 2, at most 3).
- **Flight**: once a flyer is confirmed, flying again within 10 seconds is flagged
  at once and counts 2.
- **Reach**: every half block beyond the (lag compensated) range adds 1.
- **Kill aura**: every 30 degrees the look direction missed the target adds 1.

Measured on the simulated server (with setbacks, like a real Paper server):

| Cheat | Banned after |
|---|---|
| Flying up | 2.5 s |
| Flying 20 m/s, hovering, gliding, or claiming to be on the ground | 2.5-3.5 s |
| Speed 16 m/s on the ground | 5 s |
| Kill aura hitting from 4.5 blocks (sword speed) | 7 s |
| Kill aura hitting without looking at the target | 10 s |

Subtle cheats (a little over the speed limit, 3.4 block reach) take longer, on
purpose: that is where lag lives.

Speed, flight and step also pull the player back (setback) to the last spot
they reached legitimately (the spot stops moving while they are speeding or
flying, so a setback really undoes the cheat). Reach, block reach,
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
discord: ...      # optional webhook and bot (ban log, alerts, tickets, staff commands, chat)
tickets: ...      # /ticket, /report, how long closed tickets are kept
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
  debug: false                 # print live check values to the console
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
├── data/tickets.yml          # tickets and their conversations
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

`mvn test` runs 179 tests. 75 of them are end-to-end scenarios on a simulated
server (MockBukkit). Moves are handled exactly like on a real Paper server: when
Vigil sets a player back, the server teleports them and fires a teleport event.
Hacked clients are modelled like Meteor/Wurst: Flight (fast, hovering, gliding,
climbing, with anti-kick dips, claiming to be on the ground, taking off after
walking), ground Speed and KillAura (4.5 block reach, no rotations). Each must be
**banned within a time limit** (see "How a ban happens"). They also check that legit sprint-jumping, wall jumps, stairs,
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
(and that a ban lifted as a mistake doesn't count), that warnings need a time
from 1 hour to 10 days, that mute/warn notices appear above the hotbar, that
`/unwarn` works, that every command has its own permission, that 3 warnings mute (without ever
shortening a longer mute) and that a banned player sees the ban screen when
joining. The Paper anti-xray setup is tested on sample Paper config files
(backups, other settings untouched, runs only once), and the config upgrade on
the real 2.2.0 config (new options added, edited values kept). Tickets are tested
in game (opening, answering both ways, offline replies shown on join, reports with
location and cooldown, other players' tickets out of reach) and the Discord bot
against a local fake of Discord's API: slash commands registered, `/ban` from
Discord (staff only, protected players refused), `/lookup`, ticket channels with
the right permissions, messages both ways, the ticket form, close and transcript,
the chat bridge and the connection itself (login, heartbeats, resuming after a
drop, a wrong token, a missing Message Content intent). Physics
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
- [ ] `/warn <alt> 1d Spam` works and shows a bold line above the alt's hotbar;
      `/warn <alt> Spam` (no time) is refused. 3 warnings mute for 1 hour.
- [ ] `/mute`, `/unmute`, `/unwarn` and `/kick` show the right messages.
- [ ] Give a staff member `vigil.protect`: they are flagged but not banned.
- [ ] After the first start, restart once. The console should say
      "Paper anti-xray is on (engine-mode 2)". With an x-ray pack, stone is full of fake ores.
- [ ] With a storage-ESP mod, chests inside a closed base show as stone from outside,
      and appear normally when you walk in.
- [ ] `/ac preview` shows the ban animation and the chat banner.
- [ ] With Item Scroller, mass moving items (scroll or shift-drag) stops working.
- [ ] `/ticket test` on the alt: staff see it in game and, with the bot, a new
      ticket channel appears. Answer it from Discord and from the game.
- [ ] With the bot: `/ban <alt> Cheating` in Discord bans the alt and is posted in
      the punishments channel. `/ticketpanel`, then press the buttons on another account.

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
