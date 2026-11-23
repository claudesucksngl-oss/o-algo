# Donut Pay Auction (Fabric 1.21.11, client-side)

Run live auctions on DonutSMP where people bid by `/pay`-ing you.

- Hold an item and run `/pauc start` to put it up for auction. A live card shows the item, timer, top bid, item worth and min bid.
- The mod reads DonutSMP's "X paid you $Y" messages. Payments add up per player, so paying $5M and then another $1M counts as a $6M bid.
- When the timer ends the winner gets a fireworks show, a "SOLD!" title and sounds. You then hand them the item yourself.
- Everyone who didn't win is put on a refund list. Click **[Refund all]** to `/pay` them all back, or turn on auto-refund.
- Every payment, refund and result is written to `config/payauction-ledger.log`.

Needs **Fabric Loader 0.18.1+** and **Fabric API** for 1.21.11. The mod only runs on your client.

## Commands

| Command | What it does |
|---|---|
| `/pauc start [time] [minbid]` | Auction the item in your main hand, e.g. `/pauc start 2m 5M` |
| `/pauc end` / `/pauc cancel` | End now (winner gets it) / cancel (everyone owed a refund) |
| `/pauc time 90s` | Set the default length. If an auction is running, its timer resets to this |
| `/pauc addtime 30s` | Add time to the running auction |
| `/pauc minbid 5M` | Set the minimum first bid (also applies to the running auction) |
| `/pauc increment 100k` | How much a new bid must beat the top bid by |
| `/pauc worth [17.5M]` | Show or override the worth shown for this auction |
| `/pauc setworth 1.2M` | Save the worth of ONE of the held item, used for future auctions |
| `/pauc refunds` / `refundall` / `refund <name>` / `forget <name>` | Manage money you owe back |
| `/pauc autorefund on/off` | Auto `/pay` back outbid players and losers (default off) |
| `/pauc antisnipe 10` | Late bids push the timer back up to 10s (0 = off) |
| `/pauc effects off/mid/max/test` | Win effects. mid = fireworks, max = more fireworks + item pop |
| `/pauc announce on/off` | Also post the start and the winner in public chat (default off) |
| `/pauc hud on/off` or `/pauc hud <x> <y>` | Toggle or move the card (negative x = from the right edge) |
| `/pauc test <name> <amount>` | Simulate a payment to try it out. No real money moves |
| `/pauc status` / `reload` | Show settings / reload `config/payauction.json` |

The command is `/pauc` so it doesn't override DonutSMP's own `/auction` and `/ah`.

## Item worth

The card's "worth" comes from, in order:
1. `/pauc worth <amount>` for the current auction
2. A `Worth: $X` (or `Value`/`Sell`) line in the item's lore, if the server adds one
3. Your price table, filled with `/pauc setworth` (stored per single item, multiplied by stack size)

Otherwise it shows `?`.

## If bids aren't detected

DonutSMP's payment message may be worded differently from what the mod expects. Run `/pauc test` to check the rest works. Then open `config/payauction.json` and edit `paymentPatterns` to match the exact message you receive. Keep the named groups `(?<player>...)` and `(?<amount>...)`. Run `/pauc reload` afterwards.

## Building the jar with GitHub (no setup on your PC)

1. Make a free account at github.com if you don't have one.
2. Click **+** (top right) → **New repository**. Name it `donut-pay-auction` and click **Create repository**.
3. On the new repo page, click **uploading an existing file**.
4. Unzip this project. Drag **everything inside** the `payauction` folder into the upload box, including the `.github` folder, `src`, `build.gradle`, `settings.gradle` and `gradle.properties`.
   - The `.github` folder is hidden on Mac/Windows. On Mac press `Cmd+Shift+.` in Finder to show it. On Windows use View → Show → Hidden items.
   - If the `.github` folder won't upload, create it by hand. Click **Add file → Create new file**, type the name `.github/workflows/build.yml` and paste in the contents of that file.
5. Click **Commit changes**.
6. Open the **Actions** tab. A "Build mod" run starts by itself, or you can click **Run workflow**. Wait about 3–5 minutes for the green check.
7. Click the finished run and scroll to **Artifacts**. Download **donut-pay-auction**. It's a zip containing `donut-pay-auction-1.0.0+1.21.11.jar`.
8. Put that jar and Fabric API in `.minecraft/mods` and launch the Fabric 1.21.11 profile.

If the run turns red, open it, copy the error lines and send them to Claude to fix.

## Putting it on Modrinth

1. On modrinth.com, click **Create a project** and choose type **Mod**.
2. Upload the jar. Set Loader: Fabric, Game version: 1.21.11, Environment: client-side only.
3. Add Fabric API as a required dependency.
4. Paste the top of this README as the description.

Before publishing, check DonutSMP's rules about client mods and automated `/pay` (auto-refund).
