package dev.payauction;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Saved to config/payauction.json. Everything here can also be changed with /pauc commands. */
public class AuctionConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Path path() { return FabricLoader.getInstance().getConfigDir().resolve("payauction.json"); }

    /** Lowest first bid accepted. */
    public double minBid = 1_000_000;
    /** How much a new bid must beat the current top bid by. */
    public double minIncrement = 100_000;
    /** Default auction length in seconds. */
    public int durationSeconds = 60;
    /** A bid in the last N seconds resets the timer to N seconds (0 = off). */
    public int antiSnipeSeconds = 10;

    /** Automatically /pay back outbid players and losers. Off by default: you refund with one click instead. */
    public boolean autoRefund = false;
    /** Command used for refunds. %player% and %amount% are replaced. */
    public String payCommand = "pay %player% %amount%";
    /** Gap between automatic commands, in ticks (20 = 1 second), so you don't get rate-limited. */
    public int commandDelayTicks = 30;
    /** Post auction start / winner in public chat. */
    public boolean announceInChat = false;

    /** Win effects: "off", "mid" or "max". */
    public String effects = "mid";

    public boolean hudEnabled = true;
    /** HUD position. Negative x = distance from the right edge. */
    public int hudX = -6;
    public int hudY = 6;

    /**
     * Regexes matched against incoming server messages. Each needs named groups "player" and "amount".
     * Edit these if DonutSMP changes its payment message, then run /pauc reload.
     */
    public List<String> paymentPatterns = new ArrayList<>(List.of(
            "^(?<player>\\.?[A-Za-z0-9_]{2,17}) paid you \\$(?<amount>[0-9.,]+[KkMmBbTt]?)\\.?!?$",
            "^You received \\$(?<amount>[0-9.,]+[KkMmBbTt]?) from (?<player>\\.?[A-Za-z0-9_]{2,17})\\.?!?$"
    ));

    /** Try to read "Worth: $X" from the item's lore before using the price table. */
    public boolean readWorthFromLore = true;
    /** Your own price table: item id -> worth of ONE item. Fill with /pauc setworth while holding an item. */
    public Map<String, Double> itemWorth = new LinkedHashMap<>();

    public static AuctionConfig load() {
        Path p = path();
        if (Files.exists(p)) {
            try (Reader r = Files.newBufferedReader(p)) {
                AuctionConfig c = GSON.fromJson(r, AuctionConfig.class);
                if (c != null) {
                    if (c.paymentPatterns == null) c.paymentPatterns = new AuctionConfig().paymentPatterns;
                    if (c.itemWorth == null) c.itemWorth = new LinkedHashMap<>();
                    return c;
                }
            } catch (Exception e) {
                PayAuctionClient.LOGGER.error("Couldn't read {}, using defaults", p, e);
            }
        }
        AuctionConfig c = new AuctionConfig();
        c.save();
        return c;
    }

    public void save() {
        try {
            Files.createDirectories(path().getParent());
            try (Writer w = Files.newBufferedWriter(path())) { GSON.toJson(this, w); }
        } catch (Exception e) {
            PayAuctionClient.LOGGER.error("Couldn't save config", e);
        }
    }
}
