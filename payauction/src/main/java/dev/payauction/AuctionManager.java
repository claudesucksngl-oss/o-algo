package dev.payauction;

import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
    private final List<Pattern> patterns = new ArrayList<>();

    /** Test mode (not saved): enables /pauc end and /pauc test fake payments. */
    public boolean testMode = false;

    public Phase phase = Phase.IDLE;
    public ItemStack item = ItemStack.EMPTY;
    public double worth = -1;
    public String worthSource = "";
    public double minBid;
    public double increment;
    public long startedAt, endAt;
    public long resultUntil;

    /** lowercase name -> total paid this auction */
    public final Map<String, Double> totals = new LinkedHashMap<>();
    /** lowercase name -> display name */
    public final Map<String, String> names = new LinkedHashMap<>();
    public String leader;        // lowercase key
    public double leaderAmount;
    public long lastBidAt;

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
        ledger("PAYMENT " + player + " " + Money.plain(amount) + " phase=" + phase + (testMode ? " (test)" : ""));

        if (phase != Phase.LIVE) {
            say(Text.literal(player + " paid you " + Money.format(amount) + " (no auction running, not counted).")
                    .formatted(Formatting.GRAY));
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
            leader = key;
            leaderAmount = total;
            lastBidAt = now;
            say(Text.literal("NEW TOP BID: ").formatted(Formatting.GOLD, Formatting.BOLD)
                    .append(Text.literal(player + " " + Money.format(total)).formatted(Formatting.WHITE)));
            WinEffects.bidDing();
            antiSnipe(now);
            announce(player + " leads with " + Money.format(total) + "! Next bid: " + Money.format(neededToLead()));
        } else {
            say(Text.literal(player + " has " + Money.format(total) + " in — needs " + Money.format(needed)
                    + " to lead.").formatted(Formatting.YELLOW));
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
        if (phase == Phase.LIVE) return "An auction is already running. Use /pauc cancel first.";
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
        ledger("START " + name + " min=" + Money.plain(minBid) + " seconds=" + seconds + (testMode ? " (test)" : ""));
        say(Text.literal((testMode ? "[TEST] " : "") + "Auction started: " + name + " | min bid " + Money.format(minBid)
                + " | worth " + Money.format(worth) + " | " + formatTime(seconds * 1000L)).formatted(Formatting.GOLD));
        if (!testMode) {
            announce("AUCTION: " + name + " (worth " + Money.format(worth) + "). Min bid " + Money.format(minBid)
                    + ". Bid by /pay-ing me! Ends in " + formatTime(seconds * 1000L) + ".");
        }
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
    }

    public void finish(MinecraftClient client) {
        if (phase != Phase.LIVE) return;
        endAt = System.currentTimeMillis();
        resultUntil = endAt + 12_000;

        if (leader != null) {
            phase = Phase.SOLD;
            String winner = names.getOrDefault(leader, leader);
            ledger("SOLD to " + winner + " for " + Money.plain(leaderAmount) + (testMode ? " (test)" : ""));
            say(Text.literal("SOLD! ").formatted(Formatting.GOLD, Formatting.BOLD)
                    .append(Text.literal(item.getCount() + "x " + item.getName().getString() + " to " + winner
                            + " for " + Money.format(leaderAmount) + ". Give them the item!").formatted(Formatting.WHITE)));
            if (!testMode) announce("SOLD to " + winner + " for " + Money.format(leaderAmount) + "! GG");
            WinEffects.play(client, cfg.effects, item, winner, Money.format(leaderAmount));
        } else {
            phase = Phase.UNSOLD;
            ledger("ENDED with no valid bids");
            say(Text.literal("Auction ended with no valid bids.").formatted(Formatting.GRAY));
            if (!testMode) announce("Auction ended with no winner.");
        }
        listOtherPayers();
        totals.clear();
    }

    public void cancel() {
        if (phase != Phase.LIVE) return;
        phase = Phase.CANCELLED;
        endAt = System.currentTimeMillis();
        resultUntil = endAt + 6_000;
        ledger("CANCELLED");
        say(Text.literal("Auction cancelled.").formatted(Formatting.RED));
        if (!testMode) announce("Auction cancelled.");
        listOtherPayers();
        totals.clear();
    }

    /** Just tells you who else paid this auction, so you know who sent money. */
    private void listOtherPayers() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Double> e : totals.entrySet()) {
            if (phase == Phase.SOLD && e.getKey().equals(leader)) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(names.getOrDefault(e.getKey(), e.getKey())).append(' ').append(Money.format(e.getValue()));
        }
        if (sb.length() > 0) say(Text.literal("Also paid this auction: " + sb).formatted(Formatting.GRAY));
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

    /** Every payment and result is written to config/payauction-ledger.log so you have a record. */
    private static void ledger(String line) {
        try {
            Path p = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("payauction-ledger.log");
            String ts = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            Files.writeString(p, ts + " " + line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) { }
    }
}
