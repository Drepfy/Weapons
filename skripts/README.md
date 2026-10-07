# Vanilla SMP server scripts

Skript add-ons for the server, one file per feature:

| File | What it does |
|---|---|
| `scripts/settings.sk` | **All settings**: server IP, scoreboard title, currency, PvP rewards, mob rewards, market items, reset timer |
| `scripts/economy.sk` | Persistent balances, `/balance`, `/pay`, `/eco` |
| `scripts/scoreboard.sk` | Small sidebar: money, kills, deaths, playtime, players online, market reset countdown (updates every second) |
| `scripts/killrewards.sk` | Money for mob / player kills, kill & death tracking |
| `scripts/baltop.sk` | `/baltop` GUI with player heads, ranks and pages |
| `scripts/teleport.sk` | `/tpa`, `/tpahere`, `/tpaccept`, `/tpdeny`, `/tpacancel`, `/tptoggle`, `/rtp`, `/spawn`, `/setspawn`. A teleport is cancelled if the player gets into combat during the warmup |
| `scripts/market.sk` | `/market` 6-row GUI shop with stock that resets every hour (countdown on the clock), `/marketadmin` |
| `scripts/ah.sk` | `/ah` auction house: players sell items to each other (`/ah sell 1k` ... up to 10m) |
| `scripts/string.sk` | `/string`: fills every empty inventory slot with string (30 second cooldown) |

`all-in-one/VanillaSMP.sk` is all of the above in one file (made with `python3 build.py`).
`all-in-one/tpa-rtp-spawn.sk` is only the teleports, for servers without the other scripts.

**In combat** (with the Combat plugin) no commands work at all, so `/tpa`, `/rtp`, `/spawn`,
`/ah`, `/market`... wait until the fight is over. The teleport script also cancels a teleport
when the player gets into combat during the warmup, or when the player who asked is in combat
by the time it is accepted.

**Requires:** Paper/Spigot 1.20+, [Skript](https://github.com/SkriptLang/Skript) 2.7+, [SkBee](https://github.com/ShaneBeee/SkBee) (for the sidebar).
On 1.21.11 use the newest Skript and SkBee: every syntax the scripts use was checked against
their current versions (Skript 2.16, SkBee's fastboard).

## Install
1. Copy every file in `scripts/` into `plugins/Skript/scripts/` (or only `all-in-one/VanillaSMP.sk`)
   (delete the old single-file `economy.sk` first if you installed it before).
2. Run `/sk reload scripts` (or restart).

After changing `settings.sk`, run `/sk reload settings`.

## Commands
| Command | Description |
|---|---|
| `/market` (`/shop`) | Open the market GUI |
| `/balance [player]` (`/bal`, `/money`) | Show balance |
| `/pay <player> <amount>` | Give money to an online player (`500`, `2.5k`, `1m` all work) |
| `/baltop [page]` | Richest players GUI |
| `/eco give\|take\|set <player> <amount>` | Admin (`economy.admin`) |
| `/marketadmin list` | Show item ids, prices, stock, enabled state and time to next reset (`economy.admin`) |
| `/marketadmin reset` | Force a market reset now (`economy.admin`) |
| `/marketadmin toggle <id>` / `price <id> <amount>` / `stock <id> <amount>` | Live market edits until next reload (`economy.admin`) |
| `/ah` (`/auction`) | Open the auction house: click an item to buy it (a confirm screen opens first) |
| `/ah sell <price>` | Sell the item in your hand: `500`, `1k`, `10k`, `2.5m`, up to `10m`. Max 5 items at a time, listed for 48 hours |
| `/ah mine` | Only your listings: click one to take it back |
| `/ah collect` | Get back items that did not sell (and items you took back with a full inventory) |
| Sneak-click a listing | Staff (`ah.admin`): take it off the auction house; it goes back to the seller |
| `/string` | Fill every empty slot of your inventory with string (stacks of 64). Once every 30 seconds (`string-cooldown` in `settings.sk`); `smp.string.bypass` has no cooldown |
