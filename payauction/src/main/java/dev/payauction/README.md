# Donut Pay Auction (Fabric 1.21.11, client-side)

Run live auctions on DonutSMP where people bid by `/pay`-ing you.

- Hold an item and run `/pauc start` to put it up for auction. A live card shows the item, timer, top bid, item worth and min bid.
- The mod reads DonutSMP's "X paid you $Y" messages. Payments add up per player, so paying $5M and then another $1M counts as a $6M bid.
- When the timer ends the winner gets a fireworks show, a "SOLD!" title and sounds. You then hand them the item yourself.
- Every payment and result is written to `config/payauction-ledger.log`.

Needs **Fabric Loader 0.18.1+** and **Fabric API** for 1.21.11. The mod only runs on your client.

## Commands

| Command | What it does |
|---|---|
| `/pauc start [time] [minbid]` | Auction the item in your main hand, e.g. `/pauc start 2m 5M` |
| `/pauc cancel` | Cancel the running auction |
| `/pauc testmode on/off` | Test mode: unlocks `/pauc end` and fake `/pauc test` bids, no public announcements |
| `/pauc end` | End the auction now (test mode only) |
| `/pauc time 90s` | Set the default length. If an auction is running, its timer resets to this |
| `/pauc addtime 30s` | Add time to the running auction |
| `/pauc minbid 5M` | Set the minimum first bid (also applies to the running auction) |
| `/pauc increment 100k` | How much a new bid must beat the top bid by |
| `/pauc orders` | Open /orders for the held item. Prices are read automatically |
| `/pauc orderprice` / `orderdebug` / `useorders on/off` | Show the saved order price / debug the scanner / turn order pricing off |
| `/pauc worth [17.5M]` | Show or override the worth shown for this auction |
| `/pauc setworth 1.2M` | Save the worth of ONE of the held item, used for future auctions |
| `/pauc antisnipe 10` | Late bids push the timer back up to 10s (0 = off) |
| `/pauc effects off/mid/max/test` | Win effects. mid = fireworks, max = more fireworks + item pop |
| `/pauc announce on/off` | Also post the start and the winner in public chat (default off) |
| `/pauc hud on/off` or `/pauc hud <x> <y>` | Toggle or move the card (negative x = from the right edge) |
| `/pauc test <name> <amount>` | Simulate a payment (test mode only). No real money moves |
| `/pauc status` / `reload` | Show settings / reload `config/payauction.json` |

The command is `/pauc` so it doesn't override DonutSMP's own `/auction` and `/ah`.

## Item worth (from /orders)

On DonutSMP, `/orders` is a board of buy orders. Players post how much they'll pay **per item**, so the highest order is what an item sells for right now. The mod reads that board for you:

1. Hold the item and run `/pauc orders`. This opens `/orders` searched for that item. You can also open `/orders` yourself.
2. Sort the board by **most money per item**. The mod reads every order on screen twice a second and keeps the highest price for each item, including on any pages you flip through.
3. Prices are saved in `config/payauction-orders.json`. The card shows **worth = highest order price × amount**, labelled with how old the price is, e.g. `worth · orders 3m`.
4. If you open `/orders` while an auction is running, the worth updates live.

`/pauc orderprice` shows the saved price for the held item.

Worth comes from, in order:
1. `/pauc worth <amount>` (a manual override for this auction)
2. The highest `/orders` price
3. A `Worth: $X` line in the item's own lore
4. Your price table, filled with `/pauc setworth`

If none of these exist it shows `?`.

**If /orders prices aren't picked up:** run `/pauc orderdebug`, open `/orders` and flip a page. Then open `.minecraft/logs/latest.log` and search for `[orders debug]` to see the real lore lines. Adjust `orderPricePatterns` in `config/payauction.json`, or send those lines to Claude. Prices are matched by item type, so all enchanted books share one price.

## If bids aren't detected

The mod recognises these DonutSMP formats: `Steve paid you $2.5M`, `Steve has paid you $ 60K`, `You were paid $ 60K by Steve` and `You received $5M from Steve`. Bedrock names starting with `.` work too.

If a payment still isn't counted, have someone pay you a small amount and run `/pauc lines`. It shows the last server messages, with `[BID]` next to the ones counted as payments. Send the payment line to Claude, or add your own regex to `paymentPatterns` in `config/payauction.json` (needs `(?<player>...)` and `(?<amount>...)` groups), then run `/pauc reload`.

Payments only count as bids while an auction is running.

## Building the jar with GitHub (no setup on your PC)

1. Make a free account at github.com if you don't have one.
2. Click **+** (top right) → **New repository**. Name it `donut-pay-auction` and click **Create repository**.
3. On the new repo page, click **uploading an existing file**.
4. Unzip this project. Drag **everything inside** the `payauction` folder into the upload box, including the `.github` folder, `src`, `build.gradle`, `settings.gradle` and `gradle.properties`.
   - The `.github` folder is hidden on Mac/Windows. On Mac press `Cmd+Shift+.` in Finder to show it. On Windows use View → Show → Hidden items.
   - If the `.github` folder won't upload, create it by hand. Click **Add file → Create new file**, type the name `.github/workflows/build.yml` and paste in the contents of that file.
5. Click **Commit changes**.
6. Open the **Actions** tab. A "Build mod" run starts by itself, or you can click **Run workflow**. Wait about 3–5 minutes for the green check.
7. Click the finished run and scroll to **Artifacts**. Download **donut-pay-auction**. It's a zip containing `donut-pay-auction-1.2.1+1.21.11.jar`.
8. Put that jar and Fabric API in `.minecraft/mods` and launch the Fabric 1.21.11 profile.

If the run turns red, open it, copy the error lines and send them to Claude to fix.

## Putting it on Modrinth

1. On modrinth.com, click **Create a project** and choose type **Mod**.
2. Upload the jar. Set Loader: Fabric, Game version: 1.21.11, Environment: client-side only.
3. Add Fabric API as a required dependency.
4. Paste the top of this README as the description.

Before publishing, check DonutSMP's rules about client mods.
