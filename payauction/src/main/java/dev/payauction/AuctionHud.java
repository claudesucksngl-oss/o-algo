package dev.payauction;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;

/** The on-screen auction card. Original design: dark panel, teal accent, gold when sold. */
public final class AuctionHud {
    private AuctionHud() {}

    private static final int W = 196;
    private static final int H = 92;

    private static final int BG = 0xE612161C;
    private static final int BG_INNER = 0xFF1C232C;
    private static final int ACCENT = 0xFF2FD3B5;
    private static final int GOLD = 0xFFFFC94A;
    private static final int RED = 0xFFFF5C5C;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int GREY = 0xFF9AA4B1;
    private static final int DIM = 0xFF5B6673;

    public static void render(DrawContext ctx, RenderTickCounter tick) {
        AuctionManager a = PayAuctionClient.auction;
        AuctionConfig cfg = PayAuctionClient.config;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!cfg.hudEnabled || mc.options.hudHidden || a == null) return;

        long now = System.currentTimeMillis();
        boolean live = a.phase == AuctionManager.Phase.LIVE;
        boolean showingResult = !live && a.phase != AuctionManager.Phase.IDLE && now < a.resultUntil;
        if (!live && !showingResult) return;

        TextRenderer tr = mc.textRenderer;
        int x = cfg.hudX < 0 ? ctx.getScaledWindowWidth() - W + cfg.hudX : cfg.hudX;
        int y = cfg.hudY;

        // flash the border for a second after a new bid
        boolean flash = live && now - a.lastBidAt < 1000 && (now / 150) % 2 == 0;
        int border = a.phase == AuctionManager.Phase.SOLD ? GOLD
                : a.phase == AuctionManager.Phase.LIVE ? (flash ? GOLD : ACCENT) : DIM;

        ctx.fill(x, y, x + W, y + H, BG);
        outline(ctx, x, y, W, H, border);
        ctx.fill(x, y, x + 3, y + H, border); // accent strip

        // item + title
        ctx.drawItem(a.item, x + 8, y + 6);
        ctx.drawStackOverlay(tr, a.item, x + 8, y + 6);
        String title = "AUCTION • " + a.item.getCount() + "x " + a.item.getName().getString();

        String badge;
        int badgeColor;
        switch (a.phase) {
            case LIVE -> {
                long rem = a.remainingMs();
                badge = (a.testMode ? "TEST " : "LIVE ") + AuctionManager.formatTime(rem);
                badgeColor = rem < 10_000 && (now / 400) % 2 == 0 ? RED : ACCENT;
            }
            case SOLD -> { badge = "SOLD"; badgeColor = GOLD; }
            case CANCELLED -> { badge = "CANCELLED"; badgeColor = RED; }
            default -> { badge = "ENDED"; badgeColor = GREY; }
        }
        int bw = tr.getWidth(badge) + 6;
        int bx = x + W - bw - 6;
        outline(ctx, bx, y + 5, bw, 12, badgeColor);
        ctx.drawText(tr, badge, bx + 3, y + 7, badgeColor, false);

        int titleMax = bx - (x + 28) - 4;
        ctx.drawText(tr, tr.trimToWidth(title, titleMax), x + 28, y + 6, WHITE, true);

        String status;
        if (live) status = a.leader == null ? "pay me to bid" : "next bid " + Money.format(a.neededToLead());
        else if (a.phase == AuctionManager.Phase.SOLD) status = "deliver the item!";
        else status = "no winner";
        ctx.drawText(tr, tr.trimToWidth(status, W - 34), x + 28, y + 16, GREY, false);

        // progress bar
        int barY = y + 28;
        ctx.fill(x + 8, barY, x + W - 6, barY + 3, BG_INNER);
        if (live) {
            double total = Math.max(1, a.endAt - a.startedAt);
            double frac = Math.min(1, a.remainingMs() / total);
            ctx.fill(x + 8, barY, x + 8 + (int) ((W - 14) * frac), barY + 3, badgeColor);
        } else {
            ctx.fill(x + 8, barY, x + W - 6, barY + 3, border);
        }

        // center box: top bid
        int boxY = y + 35;
        ctx.fill(x + 8, boxY, x + W - 6, boxY + 24, BG_INNER);
        outline(ctx, x + 8, boxY, W - 14, 24, 0xFF2A333E);
        if (a.leader == null) {
            String t = a.phase == AuctionManager.Phase.LIVE ? "no bids yet" : "unsold";
            ctx.drawCenteredTextWithShadow(tr, t, x + 1 + W / 2, boxY + 8, GREY);
        } else {
            String who = a.names.getOrDefault(a.leader, a.leader);
            String amt = Money.format(a.leaderAmount);
            ctx.drawText(tr, a.phase == AuctionManager.Phase.SOLD ? "winner" : "top bid", x + 13, boxY + 3, DIM, false);
            ctx.drawText(tr, tr.trimToWidth(who, W - 30 - tr.getWidth(amt)), x + 13, boxY + 13, WHITE, true);
            ctx.drawText(tr, amt, x + W - 10 - tr.getWidth(amt), boxY + 9, GOLD, true);
        }

        // footer
        int fy = y + 64;
        row(ctx, tr, x, fy, "worth", Money.format(a.worth), ACCENT);
        row(ctx, tr, x, fy + 11, "min bid", Money.format(a.minBid), WHITE);
    }

    private static void row(DrawContext ctx, TextRenderer tr, int x, int y, String label, String value, int valueColor) {
        ctx.fill(x + 10, y + 3, x + 12, y + 5, DIM);
        ctx.drawText(tr, label, x + 15, y, GREY, false);
        ctx.drawText(tr, value, x + W - 8 - tr.getWidth(value), y, valueColor, true);
    }

    private static void outline(DrawContext ctx, int x, int y, int w, int h, int color) {
        ctx.fill(x, y, x + w, y + 1, color);
        ctx.fill(x, y + h - 1, x + w, y + h, color);
        ctx.fill(x, y, x + 1, y + h, color);
        ctx.fill(x + w - 1, y, x + w, y + h, color);
    }
}
