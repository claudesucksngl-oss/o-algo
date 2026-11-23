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
        OrderScanner.init(config);

        // Only server/system messages: a normal player typing "X paid you $5M" in chat can't fake a bid.
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) auction.onServerMessage(message.getString());
        });
        // Some servers send their messages through the chat channel without a player attached.
        // Real messages typed by players (sender != null) are ignored so nobody can fake a payment.
        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) -> {
            if (sender == null) auction.onServerMessage(message.getString());
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            auction.tick(client);
            if (config.useOrderPrices) OrderScanner.tick(client);
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
                    if (!auction.testMode) return fail(c, "/pauc end only works in test mode (/pauc testmode on).");
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

                .then(literal("orders").executes(c -> {
                    ItemStack held = c.getSource().getPlayer().getMainHandStack();
                    if (held.isEmpty()) return fail(c, "Hold the item you want to price.");
                    String search = net.minecraft.registry.Registries.ITEM.getId(held.getItem()).getPath().replace('_', ' ');
                    c.getSource().getPlayer().networkHandler.sendChatCommand("orders " + search);
                    return ok(c, "Opened /orders for " + search + ". Sort by most money per item; prices are read automatically.");
                }))
                .then(literal("orderprice").executes(c -> {
                    ItemStack held = c.getSource().getPlayer().getMainHandStack();
                    if (held.isEmpty()) return fail(c, "Hold an item.");
                    OrderScanner.Price op = OrderScanner.get(AuctionManager.itemId(held));
                    if (op == null) return fail(c, "No /orders price saved for this item yet. Try /pauc orders");
                    return ok(c, "Highest order: " + Money.format(op.each) + " each (" + Money.format(op.each * held.getCount())
                            + " for " + held.getCount() + "), seen " + OrderScanner.age(op.at) + " ago");
                }))
                .then(literal("orderdebug").executes(c -> {
                    OrderScanner.debug = !OrderScanner.debug;
                    return ok(c, "Order debug " + (OrderScanner.debug ? "ON: open /orders, then check logs/latest.log" : "OFF"));
                }))
                .then(literal("useorders").then(argument("on|off", StringArgumentType.word()).executes(c -> {
                    config.useOrderPrices = isOn(StringArgumentType.getString(c, "on|off"));
                    config.save();
                    if (auction.phase == AuctionManager.Phase.LIVE) auction.lookupWorth(-1);
                    return ok(c, "/orders prices for worth: " + (config.useOrderPrices ? "ON" : "OFF"));
                })))
                .then(literal("lines").executes(c -> {
                    if (auction.recentLines.isEmpty()) return fail(c, "No server messages seen yet.");
                    c.getSource().sendFeedback(Text.literal("Last server messages (newest first, [BID] = counted as payment):").formatted(Formatting.GOLD));
                    for (String l : auction.recentLines) c.getSource().sendFeedback(Text.literal(" " + l).formatted(
                            l.startsWith("[BID]") ? Formatting.GREEN : Formatting.GRAY));
                    return 1;
                }))
                .then(literal("testmode")
                        .executes(c -> ok(c, "Test mode is " + (auction.testMode ? "ON" : "OFF")))
                        .then(argument("on|off", StringArgumentType.word()).executes(c -> {
                            auction.testMode = isOn(StringArgumentType.getString(c, "on|off"));
                            return ok(c, auction.testMode
                                    ? "Test mode ON: /pauc end and /pauc test <name> <amount> now work. No public announcements."
                                    : "Test mode OFF: real auctions run until the timer ends.");
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
                            if (!auction.testMode) return fail(c, "Turn on test mode first: /pauc testmode on");
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
                    OrderScanner.reload(config);
                    return ok(c, "Config reloaded");
                }))
                .then(literal("status").executes(c -> {
                    ok(c, "min bid " + Money.format(config.minBid) + " | step " + Money.format(config.minIncrement)
                            + " | time " + AuctionManager.formatTime(config.durationSeconds * 1000L)
                            + " | effects " + config.effects + " | test mode " + (auction.testMode ? "on" : "off")
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
                "/pauc cancel - cancel the running auction",
                "/pauc time <2m> - default length (also resets a running timer)",
                "/pauc addtime <30s> - add time to the running auction",
                "/pauc minbid <5M> | increment <100k>",
                "/pauc orders - open /orders for the held item (worth auto-updates)",
                "/pauc orderprice | orderdebug | useorders on|off",
                "/pauc worth [17.5M] - show/override worth for this auction",
                "/pauc setworth <each> - save worth of the held item type",
                "/pauc testmode on|off - test mode: /pauc end + fake bids",
                "/pauc end - end now (test mode only)",
                "/pauc announce on|off | antisnipe <s>",
                "/pauc effects off|mid|max|test",
                "/pauc hud on|off|<x> <y>  |  /pauc test <name> <amount> (test mode)",
                "/pauc lines - last server messages (check payment detection)",
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
        config.antiSnipeSeconds = f.antiSnipeSeconds;
        config.announceInChat = f.announceInChat; config.effects = f.effects;
        config.hudEnabled = f.hudEnabled; config.hudX = f.hudX; config.hudY = f.hudY;
        config.paymentPatterns = f.paymentPatterns; config.readWorthFromLore = f.readWorthFromLore; config.itemWorth = f.itemWorth;
        config.useOrderPrices = f.useOrderPrices; config.orderScreenTitles = f.orderScreenTitles;
        config.orderPricePatterns = f.orderPricePatterns;
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
