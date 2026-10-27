package com.huanghuang.rsintegration.mods.bd;

/**
 * 大盘终端里的数量文本。
 *
 * <p>0.8 之前 BD 用 {@code StringFormat#formatCount(long)}（截断、小写 k），0.8 起改成
 * {@code StringFormat#formatCompact(long)}（小于 10 时保留一位小数、大写 K）。这里按版本各复刻一份，
 * 既不直接链接 BD 的方法（跨版本会 {@link NoSuchMethodError}），又保证和 BD 自己那些格子里的数字写法一致。</p>
 */
public final class BeyondDimensionsAmountFormat {
    private static final String[] LEGACY_UNITS = {"", "k", "M", "G", "T", "P", "E"};
    private static final long[] LEGACY_THRESHOLDS = {
            1_000L, 1_000_000L, 1_000_000_000L, 1_000_000_000_000L,
            1_000_000_000_000_000L, 1_000_000_000_000_000_000L
    };

    private static final String[] MODERN_UNITS = {"K", "M", "G", "T", "P", "E"};
    private static final double[] MODERN_SCALES = {1e3, 1e6, 1e9, 1e12, 1e15, 1e18};

    private BeyondDimensionsAmountFormat() {}

    /** 按当前 BD 版本选择写法；负数交给调用方处理。 */
    public static String format(long count) {
        return BeyondDimensionsVersion.isModernTerminalUi() ? formatCompact(count) : formatCount(count);
    }

    /** 0.8+：1234 -> 1.2K，10000 -> 10K。 */
    static String formatCompact(long count) {
        if (count < 1000L) return Long.toString(count);
        int unit = MODERN_SCALES.length - 1;
        while (count < MODERN_SCALES[unit]) unit--;
        double scaled = count / MODERN_SCALES[unit];
        if (scaled < 10.0d) {
            long tenths = (long) (scaled * 10.0d);
            if (tenths % 10L == 0L) return (tenths / 10L) + MODERN_UNITS[unit];
            return (tenths / 10L) + "." + (tenths % 10L) + MODERN_UNITS[unit];
        }
        return ((long) scaled) + MODERN_UNITS[unit];
    }

    /** 0.7：1234 -> 1k，10000 -> 10k。 */
    static String formatCount(long count) {
        if (count < 1000L) return Long.toString(count);
        int unit = 0;
        while (unit < LEGACY_THRESHOLDS.length - 1 && count >= LEGACY_THRESHOLDS[unit + 1]) unit++;
        return (count / LEGACY_THRESHOLDS[unit]) + LEGACY_UNITS[unit + 1];
    }
}
