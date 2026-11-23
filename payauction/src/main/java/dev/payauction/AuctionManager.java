package dev.payauction;

import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * All auction state. Payments add up per player: someone who paid $5M and then $1M has bid $6M.
 */
public class AuctionManager {
    public enum Phase { IDLE, LIVE, SOLD, UNSOLD, CANCELLED }

    private final AuctionConfig cfg;
    private final java.util.List<Pattern> patterns = new java.util.ArrayList<>();

    public Phase phase = Phase.IDLE;
    public ItemStack item = ItemStack.EMPTY;
    public double worth = -1;
    public String worthSource = "";
    public double minBid;
    public double increment;
    public long startedAt, endAt;
    public long resultUntil;

    /** lowercase name -> total paid this auction (not yet refunded) */
    public final Map<String, Double> totals = new LinkedHashMap<>();
    /** lowercase name -> display name */
    public final Map<String, String> names = new LinkedHashMap<>();
    public String leader;        // lowercase key
    public double leaderAmount;
    public long lastBidAt;

    /** money you still owe back, across auctions. lowercase -> amount */
    public final Map<String, Double> pendingRefunds = new LinkedHashMap<>();
    private final Deque<String> commandQueue = new ArrayDeque<>();
    private int commandCooldown;

    public AuctionManager(AuctionConfig cfg) {
        this.cfg = cfg;
        reloadPatterns();
    }

    public void reloadPatterns() {
        patterns.clear();
        for (String p : cfg.paymentPatterns) {
            try { patterns.add(Pattern.compile(p)); }
            catch (Exception e) { PayAuctionClient.LOGGER.error("Bad payment pattern: {}", p, e); }
        }
    }

    // ------------------------------------------------------------------ chat input

    /** Called for every server (non-player-chat) message. */
    public void onServerMessage(String raw) {
        String msg = Formatting.strip(raw);
        if (msg == null) return;
        msg = msg.trim();
        for (Pattern p : patterns) {
            Matcher m = p.matcher(msg);
            if (m.matches()) {
                double amount = Money.parse(m.group("amount"));
                if (amount > 0) onPayment(m.group("player"), amount);
                return;
            }
        }
    }

    public void onPayment(String player, double amount) {
        String key = player.toLowerCase(Locale.ROOT);
        names.put(key, player);
        long now = System.currentTimeMillis();
        ledger("PAYMENT " + player + " " + Money.plain(amount) + " phase=" + phase);

        if (phase != Phase.LIVE) {
            // Late or random payment. If an auction just ended, remember it so you can refund it.
            if (phase != Phase.IDLE && now - endAt < 30_000) {
                pendingRefunds.merge(key, amount, Double::sum);
                say(Text.literal(player + " paid " + Money.format(amount) + " after the auction ended — added to refunds. ")
                        .formatted(Formatting.YELLOW).append(refundButton()));
            } else {
                say(Text.literal(player + " paid you " + Money.format(amount) + " (no auction running, not counted).")
                        .formatted(Formatting.GRAY));
            }
            return;
        }

        double total = totals.merge(key, amount, Double::sum);

        if (key.equals(leader)) {
            leaderAmount = total;
            lastBidAt = now;
            say(Text.literal(player + " raised their bid to " + Money.format(total)).formatted(Formatting.GREEN));
            WinEffects.bidDing();
            antiSnipe(now);
            announce(player + " now leads with " + Money.format(total) + "!");
            return;
        }

        double needed = neededToLead();
        if (total + 1e-6 >= needed) {
            String prev = leader;
            leader = key;
            leaderAmount = total;
            lastBidAt = now;
            say(Text.literal("NEW TOP BID: ").formatted(Formatting.GOLD, Formatting.BOLD)
                    .append(Text.literal(player + " " + Money.format(total)).formatted(Formatting.WHITE)));
            WinEffects.bidDing();
            antiSnipe(now);
            announce(player + " leads with " + Money.format(total) + "! Next bid: " + Money.format(neededToLead()));
            if (prev != null && cfg.autoRefund) refundNow(prev);
        } else {
            say(Text.literal(player + " has " + Money.format(total) + " in — needs " + Money.format(needed)
                    + " to lead (they can pay more, or get refunded at the end).").formatted(Formatting.YELLOW));
        }
    }

    public double neededToLead() {
        return leader == null ? minBid : leaderAmount + increment;
    }

    private void antiSnipe(long now) {
        if (cfg.antiSnipeSeconds <= 0) return;
        long min = now + cfg.antiSnipeSeconds * 1000L;
        if (endAt < min) {
            endAt = min;
            say(Text.literal("Late bid — timer extended to " + cfg.antiSnipeSeconds + "s").formatted(Formatting.AQUA));
        }
    }

    // ------------------------------------------------------------------ lifecycle

    public String start(ItemStack stack, int seconds, double minBidOverride, double worthOverride) {
        if (phase == Phase.LIVE) return "An auction is already running. Use /pauc end or /pauc cancel first.";
        if (stack.isEmpty()) return "Hold the item you want to auction in your main hand.";
        item = stack.copy();
        minBid = minBidOverride > 0 ? minBidOverride : cfg.minBid;
        increment = cfg.minIncrement;
        totals.clear();
        leader = null;
        leaderAmount = 0;
        startedAt = System.currentTimeMillis();
        endAt = startedAt + Math.max(5, seconds) * 1000L;
        lookupWorth(worthOverride);
        phase = Phase.LIVE;

        String name = item.getCount() + "x " + item.getName().getString();
        ledger("START " + name + " min=" + Money.plain(minBid) + " seconds=" + seconds);
        say(Text.literal("Auction started: " + name + " | min bid " + Money.format(minBid)
                + " | worth " + Money.format(worth) + " | " + formatTime(seconds * 1000L)).formatted(Formatting.GOLD));
        if (!pendingRefunds.isEmpty()) {
            say(Text.literal("Heads up: you still owe " + pendingRefunds.size() + " refund(s). ")
                    .formatted(Formatting.RED).append(refundButton()));
        }
        announce("AUCTION: " + name + " (worth " + Money.format(worth) + "). Min bid " + Money.format(minBid)
                + ". Bid by /pay-ing me! Ends in " + formatTime(seconds * 1000L) + ". Losers get refunded.");
        return null;
    }

    public void lookupWorth(double override) {
        worth = -1;
        worthSource = "";
        if (override > 0) { worth = override; worthSource = "manual"; return; }
        if (cfg.readWorthFromLore) {
            LoreComponent lore = item.get(DataComponentTypes.LORE);
            if (lore != null) {
                Pattern p = Pattern.compile("(?i)(worth|value|sell)[^$]*\\$\\s*([0-9.,]+[kmbt]?)");
                for (Text line : lore.lines()) {
                    Matcher m = p.matcher(line.getString());
                    if (m.find()) {
                        double v = Money.parse(m.group(2));
                        if (v > 0) { worth = v; worthSource = "lore"; return; }
                    }
                }
            }
        }
        Double each = cfg.itemWorth.get(itemId(item));
        if (each != null) { worth = each * item.getCount(); worthSource = "price table"; }
    }

    public static String itemId(ItemStack stack) {
        return Registries.ITEM.getId(stack.getItem()).toString();
    }

    public void tick(MinecraftClient client) {
        if (phase == Phase.LIVE && System.currentTimeMillis() >= endAt) finish(client);

        if (commandCooldown > 0) commandCooldown--;
        if (commandCooldown == 0 && !commandQueue.isEmpty() && client.player != null) {
            String cmd = commandQueue.poll();
            client.player.networkHandler.sendChatCommand(cmd);
            ledger("SENT /" + cmd);
            commandCooldown = Math.max(5, cfg.commandDelayTicks);
        }
    }

    public void finish(MinecraftClient client) {
        if (phase != Phase.LIVE) return;
        endAt = System.currentTimeMillis();
        resultUntil = endAt + 12_000;

        // everyone except the winner is owed their money back
        for (Map.Entry<String, Double> e : totals.entrySet()) {
            if (!e.getKey().equals(leader)) pendingRefunds.merge(e.getKey(), e.getValue(), Double::sum);
        }

        if (leader != null) {
            phase = Phase.SOLD;
            String winner = names.getOrDefault(leader, leader);
            ledger("SOLD to " + winner + " for " + Money.plain(leaderAmount));
            say(Text.literal("SOLD! ").formatted(Formatting.GOLD, Formatting.BOLD)
                    .append(Text.literal(item.getCount() + "x " + item.getName().getString() + " to " + winner
                            + " for " + Money.format(leaderAmount) + ". Give them the item!").formatted(Formatting.WHITE)));
            announce("SOLD to " + winner + " for " + Money.format(leaderAmount) + "! GG");
            WinEffects.play(client, cfg.effects, item, winner, Money.format(leaderAmount));
        } else {
            phase = Phase.UNSOLD;
            ledger("ENDED with no valid bids");
            say(Text.literal("Auction ended with no valid bids.").formatted(Formatting.GRAY));
            announce("Auction ended with no winner.");
        }
        afterEnd();
    }

    public void cancel() {
        if (phase != Phase.LIVE) return;
        for (Map.Entry<String, Double> e : totals.entrySet()) pendingRefunds.merge(e.getKey(), e.getValue(), Double::sum);
        phase = Phase.CANCELLED;
        endAt = System.currentTimeMillis();
        resultUntil = endAt + 6_000;
        ledger("CANCELLED");
        say(Text.literal("Auction cancelled. Everyone who paid is owed a refund.").formatted(Formatting.RED));
        announce("Auction cancelled, everyone will be refunded.");
        afterEnd();
    }

    private void afterEnd() {
        totals.clear();
        if (pendingRefunds.isEmpty()) return;
        if (cfg.autoRefund) {
            refundAll();
        } else {
            say(Text.literal("You owe " + pendingRefunds.size() + " refund(s), total "
                    + Money.format(pendingRefunds.values().stream().mapToDouble(d -> d).sum()) + ". ")
                    .formatted(Formatting.YELLOW).append(refundButton()));
        }
    }

    // ------------------------------------------------------------------ refunds

    /** Refund someone mid-auction (used by auto-refund when outbid). */
    private void refundNow(String key) {
        Double amt = totals.remove(key);
        if (amt == null || amt <= 0) return;
        queuePay(key, amt);
    }

    public int refundAll() {
        int n = 0;
        for (Map.Entry<String, Double> e : new LinkedHashMap<>(pendingRefunds).entrySet()) {
            queuePay(e.getKey(), e.getValue());
            n++;
        }
        pendingRefunds.clear();
        return n;
    }

    public boolean refundOne(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        Double amt = pendingRefunds.remove(key);
        if (amt == null) return false;
        queuePay(key, amt);
        return true;
    }

    private void queuePay(String key, double amount) {
        String name = names.getOrDefault(key, key);
        String cmd = cfg.payCommand.replace("%player%", name).replace("%amount%", Money.plain(amount));
        if (cmd.startsWith("/")) cmd = cmd.substring(1);
        commandQueue.add(cmd);
        ledger("REFUND queued " + name + " " + Money.plain(amount));
        say(Text.literal("Refunding " + name + " " + Money.format(amount) + "…").formatted(Formatting.GRAY));
    }

    public static MutableText refundButton() {
        return Text.literal("[Refund all]").styled(s -> s.withColor(Formatting.GREEN).withBold(true)
                .withClickEvent(new ClickEvent.RunCommand("/pauc refundall"))
                .withHoverEvent(new HoverEvent.ShowText(Text.literal("/pay everyone back"))));
    }

    // ------------------------------------------------------------------ helpers

    public long remainingMs() { return Math.max(0, endAt - System.currentTimeMillis()); }

    public static String formatTime(long ms) {
        long s = (ms + 999) / 1000;
        return s >= 3600 ? String.format("%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
                : String.format("%d:%02d", s / 60, s % 60);
    }

    private void announce(String text) {
        if (!cfg.announceInChat) return;
        MinecraftClient c = MinecraftClient.getInstance();
        if (c.player != null) c.player.networkHandler.sendChatMessage(text);
    }

    public static void say(Text text) {
        MinecraftClient c = MinecraftClient.getInstance();
        c.inGameHud.getChatHud().addMessage(Text.literal("[Auction] ").formatted(Formatting.DARK_AQUA).append(text));
    }

    /** Every payment, refund and result is written to config/payauction-ledger.log so you have a record. */
    private static void ledger(String line) {
        try {
            Path p = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("payauction-ledger.log");
            String ts = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            Files.writeString(p, ts + " " + line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) { }
    }

}
