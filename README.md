# Weapons server scripts

Skript add-ons for the server, one file per feature:

| File | What it does |
|---|---|
| `scripts/settings.sk` | **All settings**: server IP, title, currency, PvP rewards, mob rewards, market items |
| `scripts/economy.sk` | Persistent balances, `/balance`, `/pay`, `/eco` |
| `scripts/scoreboard.sk` | Sidebar: money, playtime, kills, deaths, server IP |
| `scripts/killrewards.sk` | Money for mob / player kills, kill & death tracking |
| `scripts/market.sk` | `/market` GUI shop, `/marketadmin` |

**Requires:** Paper/Spigot 1.20+, [Skript](https://github.com/SkriptLang/Skript) 2.7+, [SkBee](https://github.com/ShaneBeee/SkBee) (for the sidebar).

## Install
1. Copy every file in `scripts/` into `plugins/Skript/scripts/`
   (delete the old single-file `economy.sk` first if you installed it before).
2. Run `/sk reload scripts` (or restart).

After changing `settings.sk`, run `/sk reload settings`.

## Commands
| Command | Description |
|---|---|
| `/market` (`/shop`) | Open the market GUI |
| `/balance [player]` (`/bal`, `/money`) | Show balance |
| `/pay <player> <amount>` | Send money to an online player |
| `/eco give\|take\|set <player> <amount>` | Admin (`economy.admin`) |
| `/marketadmin list` | Show market item ids, prices, enabled state (`economy.admin`) |
| `/marketadmin toggle <id>` / `price <id> <amount>` | Live market edits until next reload (`economy.admin`) |
