package dev.payauction;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/** Parses and formats DonutSMP-style money: 1500, 1.5k, 2.25M, 1B, 1,000,000, $3m */
public final class Money {
    private Money() {}

    /** @return the amount, or -1 if it can't be parsed */
    public static double parse(String raw) {
        if (raw == null) return -1;
        String s = raw.trim().replace(",", "").replace("$", "").replace("_", "").replace(" ", "").toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return -1;
        double mult = 1;
        char last = s.charAt(s.length() - 1);
        switch (last) {
            case 'k' -> mult = 1e3;
            case 'm' -> mult = 1e6;
            case 'b' -> mult = 1e9;
            case 't' -> mult = 1e12;
            default -> { }
        }
        if (mult != 1) s = s.substring(0, s.length() - 1);
        try {
            double v = Double.parseDouble(s) * mult;
            return (v < 0 || Double.isNaN(v) || Double.isInfinite(v)) ? -1 : v;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Compact display: $950, $12.5K, $17.5M, $1.2B */
    public static String format(double v) {
        if (v < 0) return "?";
        String[] suffix = {"", "K", "M", "B", "T"};
        int i = 0;
        double n = v;
        while (n >= 1000 && i < suffix.length - 1) { n /= 1000; i++; }
        int decimals = i == 0 ? 2 : (n >= 100 ? 0 : n >= 10 ? 1 : 2);
        BigDecimal bd = BigDecimal.valueOf(n).setScale(decimals, RoundingMode.DOWN).stripTrailingZeros();
        return "$" + bd.toPlainString() + suffix[i];
    }

    /** Plain number for /pay, e.g. 1500000 or 1500000.5 */
    public static String plain(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.DOWN).stripTrailingZeros().toPlainString();
    }
}
