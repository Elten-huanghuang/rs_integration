package com.huanghuang.rsintegration.crafting.quantity;

/** 非负 long 的饱和算术，避免规划数量在聚合时回绕。 */
public final class SaturatingLongMath {
    private SaturatingLongMath() {}

    public static long add(long left, long right) {
        if (left <= 0L) return Math.max(0L, right);
        if (right <= 0L) return left;
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    /** 有符号饱和加法，供材料净需求这类可能暂时为负的累计值使用。 */
    public static long signedAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        if (right < 0L && left < Long.MIN_VALUE - right) return Long.MIN_VALUE;
        return left + right;
    }

    public static long multiply(long left, long right) {
        if (left <= 0L || right <= 0L) return 0L;
        return left > Long.MAX_VALUE / right ? Long.MAX_VALUE : left * right;
    }

    public static long ceilDiv(long value, long divisor) {
        if (value <= 0L || divisor <= 0L) return 0L;
        return value / divisor + (value % divisor == 0L ? 0L : 1L);
    }

    public static int clampToInt(long value) {
        if (value <= 0L) return 0;
        return value >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }
}
