package com.huanghuang.rsintegration.crafting.quantity;

/** 计划、终端与共振容器共用的紧凑数量格式；最大单位覆盖完整 long 范围。 */
public final class CompactQuantityFormat {
    private static final long[] DIVISORS = {
            1_000_000_000_000_000_000L,
            1_000_000_000_000_000L,
            1_000_000_000_000L,
            1_000_000_000L,
            1_000_000L,
            1_000L
    };
    private static final String[] SUFFIXES = {"E", "P", "T", "G", "M", "K"};

    private CompactQuantityFormat() {}

    public static String format(long amount) {
        long absolute = amount == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(amount);
        if (absolute < 1_000L) return Long.toString(amount);
        for (int i = 0; i < DIVISORS.length; i++) {
            if (absolute < DIVISORS[i]) continue;
            long divisor = DIVISORS[i];
            long whole = absolute / divisor;
            long tenth = absolute % divisor * 10L / divisor;
            String sign = amount < 0L ? "-" : "";
            if (whole < 10L && tenth > 0L) {
                return sign + whole + "." + tenth + SUFFIXES[i];
            }
            return sign + whole + SUFFIXES[i];
        }
        return Long.toString(amount);
    }
}
