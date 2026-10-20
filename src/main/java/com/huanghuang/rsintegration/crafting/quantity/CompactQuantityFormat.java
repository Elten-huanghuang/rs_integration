package com.huanghuang.rsintegration.crafting.quantity;

import java.math.BigInteger;

/** 计划、终端与共振容器共用的紧凑数量格式；最大单位覆盖完整 long 范围。 */
public final class CompactQuantityFormat {
    private static final BigInteger THOUSAND = BigInteger.valueOf(1_000L);
    private static final BigInteger TEN = BigInteger.TEN;
    private static final long[] DIVISORS = {
            1_000_000_000_000_000_000L,
            1_000_000_000_000_000L,
            1_000_000_000_000L,
            1_000_000_000L,
            1_000_000L,
            1_000L
    };
    private static final String[] SUFFIXES = {
            "Q", "R", "Y", "Z", "E", "P", "T", "G", "M", "K"
    };

    private CompactQuantityFormat() {}

    public static String format(long amount) {
        long absolute = amount == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(amount);
        if (absolute < 1_000L) return Long.toString(amount);
        int firstLongSuffix = SUFFIXES.length - DIVISORS.length;
        for (int i = 0; i < DIVISORS.length; i++) {
            if (absolute < DIVISORS[i]) continue;
            long divisor = DIVISORS[i];
            long whole = absolute / divisor;
            long tenth = absolute % divisor / (divisor / 10L);
            String sign = amount < 0L ? "-" : "";
            if (whole < 10L && tenth > 0L) {
                return sign + whole + "." + tenth + SUFFIXES[firstLongSuffix + i];
            }
            return sign + whole + SUFFIXES[firstLongSuffix + i];
        }
        return Long.toString(amount);
    }

    public static String format(BigInteger amount) {
        if (amount == null || amount.signum() == 0) return "0";
        BigInteger absolute = amount.abs();
        if (absolute.compareTo(THOUSAND) < 0) return amount.toString();
        int group = (absolute.toString().length() - 1) / 3;
        int cappedGroup = Math.min(group, SUFFIXES.length);
        int suffixIndex = SUFFIXES.length - cappedGroup;
        if (suffixIndex < 0) suffixIndex = 0;
        BigInteger divisor = THOUSAND.pow(cappedGroup);
        BigInteger whole = absolute.divide(divisor);
        BigInteger tenth = absolute.remainder(divisor).multiply(TEN).divide(divisor);
        String sign = amount.signum() < 0 ? "-" : "";
        String suffix = SUFFIXES[suffixIndex];
        if (whole.compareTo(TEN) < 0 && tenth.signum() > 0) {
            return sign + whole + "." + tenth + suffix;
        }
        return sign + whole + suffix;
    }
}
