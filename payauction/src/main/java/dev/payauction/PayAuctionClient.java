package dev.payauction;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public class PayAuctionClient implements ClientModInitializer {
    public static final String MOD_ID = "payauction";
    public static final Logger LOGGER = LoggerFactory.getLogger("DonutPayAuction");

    public static AuctionConfig config;
    public static AuctionManager auction;

    @Override
    public void onInitializeClient() {
        config = AuctionConfig.load();
        auction = new AuctionManager(config);

        // Only server/system messages: a normal player typing "X paid you $5M" in chat can't fake a bid.
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) auction.onServerMessage(message.getString());
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            auction.tick(client);
            WinEffects.tick(client);
        });

        HudElementRegistry.attachElementAfter(VanillaHudElements.CHAT,
                Identifier.of(MOD_ID, "auction_card"), AuctionHud::render);

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> register(dispatcher));
    }

    // ---------------------------------------------------------------- commands (/pauc ...)

    private static void register(CommandDispatcher<FabricClientCommandSource> d) {
        d.register(literal("pauc")
                .executes(PayAuctionClient::help)
                .then(literal("help").executes(PayAuctionClient::help))

                .then(literal("start")
                        .executes(c -> start(c, null, null))
                        .then(argument("time", StringArgumentType.word())
                                .executes(c -> start(c, StringArgumentType.getString(c, "time"), null))
                                .then(argument("minbid", StringArgumentType.word())
                                        .executes(c -> start(c, StringArgumentType.getString(c, "time"),
                                                StringArgumentType.getString(c, "minbid"))))))

                .then(literal("end").executes(c -> {
                    if (auction.phase != AuctionManager.Phase.LIVE) return fail(c, "No auction running.");
                    auction.finish(c.getSource().getClient());
                    return 1;
                }))
                .then(literal("cancel").executes(c -> {
                    if (auction.phase != AuctionManager.Phase.LIVE) return fail(c, "No auction running.");
                    auction.cancel();
                    return 1;
                }))

                .then(literal("time").then(argument("time", StringArgumentType.word()).executes(c -> {
                    int s = parseDuration(StringArgumentType.getString(c, "time"));
                    if (s <= 0) return fail(c, "Use something like 90, 90s, 2m or 1m30s.");
                    config.durationSeconds = s;
                    config.save();
                    if (auction.phase == AuctionManager.Phase.LIVE) {
                        auction.endAt = System.currentTimeMillis() + s * 1000L;
                        auction.startedAt = System.currentTimeMillis();
                        return ok(c, "Default time set to " + AuctionManager.formatTime(s * 1000L) + " and the running timer reset to it.");
                    }
                    return ok(c, "Default auction time set to " + AuctionManager.formatTime(s * 1000L));
                })))
                .then(literal("addtime").then(argument("time", StringArgumentType.word()).executes(c -> {
                    int s = parseDuration(StringArgumentType.getString(c, "time"));
                    if (s <= 0 || auction.phase != AuctionManager.Phase.LIVE) return fail(c, "Needs a running auction and a time like 30s.");
                    auction.endAt += s * 1000L;
                    return ok(c, "Added " + AuctionManager.formatTime(s * 1000L));
                })))

                .then(literal("minbid").then(argument("amount", StringArgumentType.word()).executes(c -> {
                    double v = Money.parse(StringArgumentType.getString(c, "amount"));
                    if (v <= 0) return fail(c, "Use an amount like 5M, 500k or 2500000.");
                    config.minBid = v;
                    config.save();
                    if (auction.phase == AuctionManager.Phase.LIVE) auction.minBid = v;
                    return ok(c, "Min bid set to " + Money.format(v));
                })))
                .then(literal("increment").then(argument("amount", StringArgumentType.word()).executes(c -> {
                    double v = Money.parse(StringArgumentType.getString(c, "amount"));
                    if (v < 0) return fail(c, "Use an amount like 100k.");
                    config.minIncrement = v;
                    config.save();
                    if (auction.phase == AuctionManager.Phase.LIVE) auction.increment = v;
                    return ok(c, "Bids must now beat the top bid by " + Money.format(v));
                })))

                .then(literal("worth")
                        .executes(c -> {
                            if (auction.phase != AuctionManager.Phase.LIVE) return fail(c, "No auction running.");
                            return ok(c, "Worth: " + Money.format(auction.worth) + (auction.worthSource.isEmpty() ? "" : " (from " + auction.worthSource + ")"));
                        })
                        .then(argument("amount", StringArgumentType.word()).executes(c -> {
                            double v = Money.parse(StringArgumentType.getString(c, "amount"));
                            if (v <= 0) return fail(c, "Use an amount like 17.5M.");
                            if (auction.phase != AuctionManager.Phase.LIVE) return fail(c, "No auction running. Use /pauc setworth to save a price for the held item.");
                            auction.lookupWorth(v);
                            return ok(c, "Shown worth set to " + Money.format(v));
                        })))
                .then(literal("setworth").then(argument("each", StringArgumentType.word()).executes(c -> {
                    double v = Money.parse(StringArgumentType.getString(c, "each"));
                    ItemStack held = c.getSource().getPlayer().getMainHandStack();
                    if (v <= 0 || held.isEmpty()) return fail(c, "Hold an item and give the worth of ONE, e.g. /pauc setworth 1.2M");
                    String id = AuctionManager.itemId(held);
                    config.itemWorth.put(id, v);
                    config.save();
                    return ok(c, "Saved " + id + " = " + Money.format(v) + " each");
                })))

                .then(literal("refunds").executes(c -> {
                    if (auction.pendingRefunds.isEmpty()) return ok(c, "You don't owe anyone.");
                    for (Map.Entry<String, Double> e : auction.pendingRefunds.entrySet()) {
                        c.getSource().sendFeedback(Text.literal(" - " + auction.names.getOrDefault(e.getKey(), e.getKey())
                                + ": " + Money.format(e.getValue())).formatted(Formatting.YELLOW));
                    }
                    c.getSource().sendFeedback(AuctionManager.refundButton());
                    return 1;
                }))
                .then(literal("refundall").executes(c -> {
                    int n = auction.refundAll();
                    return n == 0 ? ok(c, "Nobody to refund.") : ok(c, "Sending " + n + " refund(s), one every "
                            + String.format(Locale.ROOT, "%.1f", config.commandDelayTicks / 20.0) + "s.");
                }))
                .then(literal("refund").then(argument("player", StringArgumentType.word()).executes(c ->
                        auction.refundOne(StringArgumentType.getString(c, "player")) ? 1 : fail(c, "That player isn't owed anything."))))
                .then(literal("forget").then(argument("player", StringArgumentType.word()).executes(c -> {
                    String p = StringArgumentType.getString(c, "player").toLowerCase(Locale.ROOT);
                    return auction.pendingRefunds.remove(p) != null ? ok(c, "Removed from refund list (you paid them manually).") : fail(c, "Not on the list.");
                })))

                .then(literal("autorefund").then(argument("on|off", StringArgumentType.word()).executes(c -> {
                    config.autoRefund = isOn(StringArgumentType.getString(c, "on|off"));
                    config.save();
                    return ok(c, "Auto refund " + (config.autoRefund ? "ON" : "OFF"));
                })))
                .then(literal("announce").then(argument("on|off", StringArgumentType.word()).executes(c -> {
                    config.announceInChat = isOn(StringArgumentType.getString(c, "on|off"));
                    config.save();
                    return ok(c, "Public chat announcements " + (config.announceInChat ? "ON" : "OFF"));
                })))
                .then(literal("antisnipe").then(argument("seconds", IntegerArgumentType.integer(0, 120)).executes(c -> {
                    config.antiSnipeSeconds = IntegerArgumentType.getInteger(c, "seconds");
                    config.save();
                    return ok(c, config.antiSnipeSeconds == 0 ? "Anti-snipe off" : "Late bids extend the timer to " + config.antiSnipeSeconds + "s");
                })))
                .then(literal("effects")
                        .then(literal("off").executes(c -> effects(c, "off")))
                        .then(literal("mid").executes(c -> effects(c, "mid")))
                        .then(literal("max").executes(c -> effects(c, "max")))
                        .then(literal("test").executes(c -> {
                            WinEffects.play(c.getSource().getClient(), config.effects,
                                    c.getSource().getPlayer().getMainHandStack(), "TestWinner", "$1M");
                            return 1;
                        })))

                .then(literal("hud")
                        .then(literal("on").executes(c -> { config.hudEnabled = true; config.save(); return ok(c, "HUD on"); }))
                        .then(literal("off").executes(c -> { config.hudEnabled = false; config.save(); return ok(c, "HUD off"); }))
                        .then(argument("x", IntegerArgumentType.integer(-4000, 4000))
                                .then(argument("y", IntegerArgumentType.integer(0, 4000)).executes(c -> {
                                    config.hudX = IntegerArgumentType.getInteger(c, "x");
                                    config.hudY = IntegerArgumentType.getInteger(c, "y");
                                    config.save();
                                    return ok(c, "HUD moved (negative x = from right edge)");
                                }))))

                .then(literal("test").then(argument("player", StringArgumentType.word())
                        .then(argument("amount", StringArgumentType.word()).executes(c -> {
                            double v = Money.parse(StringArgumentType.getString(c, "amount"));
                            if (v <= 0) return fail(c, "Bad amount");
                            c.getSource().sendFeedback(Text.literal("(simulated payment — no real money)").formatted(Formatting.DARK_GRAY));
                            auction.onPayment(StringArgumentType.getString(c, "player"), v);
                            return 1;
                        }))))
                .then(literal("reload").executes(c -> {
                    AuctionConfig fresh = AuctionConfig.load();
                    copyInto(fresh);
                    auction.reloadPatterns();
                    return ok(c, "Config reloaded");
                }))
                .then(literal("status").executes(c -> {
                    ok(c, "min bid " + Money.format(config.minBid) + " | step " + Money.format(config.minIncrement)
                            + " | time " + AuctionManager.formatTime(config.durationSeconds * 1000L)
                            + " | effects " + config.effects + " | autorefund " + (config.autoRefund ? "on" : "off")
                            + " | anti-snipe " + config.antiSnipeSeconds + "s");
                    return 1;
                }))
        );
    }

    private static int start(CommandContext<FabricClientCommandSource> c, String time, String minBid) {
        int seconds = config.durationSeconds;
        if (time != null) {
            seconds = parseDuration(time);
            if (seconds <= 0) return fail(c, "Bad time. Examples: 60, 90s, 2m, 1m30s");
        }
        double min = -1;
        if (minBid != null) {
            min = Money.parse(minBid);
            if (min <= 0) return fail(c, "Bad min bid. Examples: 5M, 750k");
        }
        String err = auction.start(c.getSource().getPlayer().getMainHandStack(), seconds, min, -1);
        return err == null ? 1 : fail(c, err);
    }

    private static int effects(CommandContext<FabricClientCommandSource> c, String level) {
        config.effects = level;
        config.save();
        return ok(c, "Win effects: " + level + "  (try /pauc effects test)");
    }

    private static int help(CommandContext<FabricClientCommandSource> c) {
        String[] lines = {
                "/pauc start [time] [minbid] - auction the item in your hand",
                "/pauc end | cancel - finish now / cancel (everyone refunded)",
                "/pauc time <2m> - default length (also resets a running timer)",
                "/pauc addtime <30s> - add time to the running auction",
                "/pauc minbid <5M> | increment <100k>",
                "/pauc worth [17.5M] - show/override worth for this auction",
                "/pauc setworth <each> - save worth of the held item type",
                "/pauc refunds | refundall | refund <name> | forget <name>",
                "/pauc autorefund on|off | announce on|off | antisnipe <s>",
                "/pauc effects off|mid|max|test",
                "/pauc hud on|off|<x> <y>  |  /pauc test <name> <amount>",
                "/pauc status | reload"
        };
        c.getSource().sendFeedback(Text.literal("Donut Pay Auction").formatted(Formatting.GOLD, Formatting.BOLD));
        for (String l : lines) c.getSource().sendFeedback(Text.literal(l).formatted(Formatting.GRAY));
        return 1;
    }

    private static final Pattern DURATION = Pattern.compile("(?i)^(?:(\\d+)h)?(?:(\\d+)m)?(?:(\\d+)s?)?$");

    /** "90" / "90s" / "2m" / "1m30s" / "1h" -> seconds, or -1 */
    static int parseDuration(String s) {
        Matcher m = DURATION.matcher(s.trim());
        if (!m.matches() || s.isBlank()) return -1;
        long total = 0;
        if (m.group(1) != null) total += Long.parseLong(m.group(1)) * 3600;
        if (m.group(2) != null) total += Long.parseLong(m.group(2)) * 60;
        if (m.group(3) != null) total += Long.parseLong(m.group(3));
        return total > 0 && total <= 24 * 3600 ? (int) total : -1;
    }

    private static boolean isOn(String s) {
        return s.equalsIgnoreCase("on") || s.equalsIgnoreCase("true") || s.equalsIgnoreCase("yes");
    }

    private static void copyInto(AuctionConfig f) {
        config.minBid = f.minBid; config.minIncrement = f.minIncrement; config.durationSeconds = f.durationSeconds;
        config.antiSnipeSeconds = f.antiSnipeSeconds; config.autoRefund = f.autoRefund; config.payCommand = f.payCommand;
        config.commandDelayTicks = f.commandDelayTicks; config.announceInChat = f.announceInChat; config.effects = f.effects;
        config.hudEnabled = f.hudEnabled; config.hudX = f.hudX; config.hudY = f.hudY;
        config.paymentPatterns = f.paymentPatterns; config.readWorthFromLore = f.readWorthFromLore; config.itemWorth = f.itemWorth;
    }

    private static int ok(CommandContext<FabricClientCommandSource> c, String msg) {
        c.getSource().sendFeedback(Text.literal(msg).formatted(Formatting.GREEN));
        return 1;
    }

    private static int fail(CommandContext<FabricClientCommandSource> c, String msg) {
        c.getSource().sendError(Text.literal(msg));
        return 0;
    }
}
