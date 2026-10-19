package com.huanghuang.rsintegration.crafting.quantity;

import java.util.Locale;

/** 计划界面统一的紧凑数量格式；最大单位覆盖完整 long 范围。 */
public final class CompactQuantityFormat {
    private static final long[] DIVISORS = {
            1_000_000_000_000_000_000L,
            1_000_000_000_000_000L,
            1_000_000_000_000L,
            1_000_000_000L,
            1_000_000L,
            1_000L
    };
    private static final String[] SUFFIXES = {"Qi", "Qa", "T", "B", "M", "K"};

    private CompactQuantityFormat() {}

    public static String format(long amount) {
        long absolute = amount == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(amount);
        if (absolute < 10_000L) return Long.toString(amount);
        for (int i = 0; i < DIVISORS.length; i++) {
            if (absolute < DIVISORS[i]) continue;
            double value = (double) amount / DIVISORS[i];
            String pattern = Math.abs(value) >= 100.0D ? "%.0f%s" : "%.1f%s";
            return String.format(Locale.ROOT, pattern, value, SUFFIXES[i]);
        }
        return Long.toString(amount);
    }
}
