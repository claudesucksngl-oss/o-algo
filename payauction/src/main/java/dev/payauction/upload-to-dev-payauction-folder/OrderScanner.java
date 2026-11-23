package dev.payauction;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads DonutSMP's /orders menu. Every order in that menu is an item with a "price per item" in its lore.
 * While the menu is open we keep the highest price per item seen (across every page you flip through)
 * and save it, so the auction card can show "worth = highest order price x amount".
 */
public final class OrderScanner {
    private OrderScanner() {}

    public static final class Price {
        public double each;
        public long at;
        Price() {}
        Price(double each, long at) { this.each = each; this.at = at; }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Path file() { return FabricLoader.getInstance().getConfigDir().resolve("payauction-orders.json"); }

    /** item id -> highest order price for ONE item */
    private static final Map<String, Price> prices = new LinkedHashMap<>();
    /** highest prices seen since the orders menu was opened */
    private static final Map<String, Double> sessionMax = new HashMap<>();
    private static final List<Pattern> pricePatterns = new ArrayList<>();
    private static final List<String> titleWords = new ArrayList<>();

    private static Object lastScreen;
    private static int tickCounter;
    private static boolean debugPrinted;
    public static boolean debug = false;

    public static void init(AuctionConfig cfg) {
        reload(cfg);
        load();
    }

    public static void reload(AuctionConfig cfg) {
        pricePatterns.clear();
        for (String p : cfg.orderPricePatterns) {
            try { pricePatterns.add(Pattern.compile(p)); }
            catch (Exception e) { PayAuctionClient.LOGGER.error("Bad order price pattern: {}", p, e); }
        }
        titleWords.clear();
        for (String t : cfg.orderScreenTitles) titleWords.add(t.toLowerCase());
    }

    /** Highest known order price for one of this item, or null. */
    public static Price get(String itemId) { return prices.get(itemId); }

    public static void tick(MinecraftClient client) {
        if (!(client.currentScreen instanceof HandledScreen<?> screen)) {
            if (lastScreen != null) { lastScreen = null; sessionMax.clear(); }
            return;
        }
        String title = Formatting.strip(screen.getTitle().getString());
        if (title == null || !isOrdersTitle(title.toLowerCase())) return;

        if (screen != lastScreen) {        // newly opened orders menu
            lastScreen = screen;
            sessionMax.clear();
            debugPrinted = false;
            tickCounter = 0;
        }
        if (tickCounter++ % 10 != 0) return; // twice a second is plenty (catches page changes)

        boolean changed = false;
        int found = 0;
        for (Slot slot : screen.getScreenHandler().slots) {
            if (slot.inventory instanceof PlayerInventory) continue;
            ItemStack stack = slot.getStack();
            if (stack.isEmpty()) continue;
            double each = readPrice(stack);
            if (debug && !debugPrinted) debugStack(stack, each);
            if (each <= 0) continue;
            found++;
            String id = AuctionManager.itemId(stack);
            Double best = sessionMax.get(id);
            if (best == null || each > best) {
                sessionMax.put(id, each);
                changed = true;
            }
        }
        if (debug && !debugPrinted) {
            debugPrinted = true;
            AuctionManager.say(Text.literal("Order debug: found " + found + " priced orders on this page (lore printed to latest.log)").formatted(Formatting.GRAY));
        }
        if (!changed) return;

        long now = System.currentTimeMillis();
        for (Map.Entry<String, Double> e : sessionMax.entrySet()) {
            Price old = prices.get(e.getKey());
            if (old == null || old.each != e.getValue() || now - old.at > 5_000) {
                prices.put(e.getKey(), new Price(e.getValue(), now));
            }
        }
        save();
        PayAuctionClient.auction.onOrderPricesUpdated();
    }

    private static boolean isOrdersTitle(String t) {
        for (String w : titleWords) if (t.contains(w)) return true;
        return false;
    }

    /** Price for ONE item from the order's lore, or -1. */
    static double readPrice(ItemStack stack) {
        LoreComponent lore = stack.get(DataComponentTypes.LORE);
        if (lore == null) return -1;
        for (Text line : lore.lines()) {
            String s = Formatting.strip(line.getString());
            if (s == null) continue;
            for (Pattern p : pricePatterns) {
                Matcher m = p.matcher(s);
                if (m.find()) {
                    double v = Money.parse(m.group(1));
                    if (v > 0) return v;
                }
            }
        }
        return -1;
    }

    private static void debugStack(ItemStack stack, double each) {
        StringBuilder sb = new StringBuilder("[orders debug] ").append(AuctionManager.itemId(stack))
                .append(" '").append(stack.getName().getString()).append("' price=").append(each);
        LoreComponent lore = stack.get(DataComponentTypes.LORE);
        if (lore != null) for (Text l : lore.lines()) sb.append("\n    | ").append(l.getString());
        PayAuctionClient.LOGGER.info(sb.toString());
    }

    public static String age(long at) {
        long s = (System.currentTimeMillis() - at) / 1000;
        if (s < 60) return s + "s";
        if (s < 3600) return (s / 60) + "m";
        if (s < 86400) return (s / 3600) + "h";
        return (s / 86400) + "d";
    }

    private static void load() {
        Path p = file();
        if (!Files.exists(p)) return;
        try (Reader r = Files.newBufferedReader(p)) {
            Map<String, Price> m = GSON.fromJson(r, new TypeToken<LinkedHashMap<String, Price>>() {}.getType());
            if (m != null) prices.putAll(m);
        } catch (Exception e) {
            PayAuctionClient.LOGGER.error("Couldn't read order prices", e);
        }
    }

    private static void save() {
        try (Writer w = Files.newBufferedWriter(file())) {
            GSON.toJson(prices, w);
        } catch (Exception e) {
            PayAuctionClient.LOGGER.error("Couldn't save order prices", e);
        }
    }
}
