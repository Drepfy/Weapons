# Weapons server scripts

`scripts/economy.sk` — scoreboard, persistent economy, kill rewards and `/market`.

**Requires:** Paper/Spigot 1.20+, [Skript](https://github.com/SkriptLang/Skript) 2.7+, [SkBee](https://github.com/ShaneBeee/SkBee) (sidebar).

Install: copy `scripts/economy.sk` to `plugins/Skript/scripts/`, then `/sk reload economy`.

## Configure
- `options:` at the top — server IP, title, currency symbol, starting balance, PvP reward/penalty.
- `on load:` — mob rewards (`{mobreward::<mob>}`) and market items (item, amount, price, slot, enabled).

## Commands
| Command | Description |
|---|---|
| `/market` (`/shop`) | Open the market GUI |
| `/balance [player]` (`/bal`) | Show balance |
| `/pay <player> <amount>` | Send money |
| `/eco give\|take\|set <player> <amount>` | Admin (`economy.admin`) |
| `/marketadmin toggle <id>` / `price <id> <amount>` | Live market edits (`economy.admin`) |
