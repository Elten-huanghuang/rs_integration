package com.huanghuang.rsintegration.crafting.consumption;

/** 非负 long 的耐久使用次数与工具数量计算。 */
public final class DurabilityMath {
    private DurabilityMath() {}

    /** 剩余耐久还能支持多少次固定伤害的使用。 */
    public static long usesForRemainingDurability(long remainingDurability, long damagePerUse) {
        if (remainingDurability <= 0L) return 0L;
        if (damagePerUse < 1L) {
            throw new IllegalArgumentException("damagePerUse must be positive");
        }
        return ceilDiv(remainingDurability, damagePerUse);
    }

    /** 按总使用量计算需要的工具数量，避免逐次取整造成多预留。 */
    public static long requiredTools(long operations, long damagePerOperation,
                                     long usesPerTool) {
        if (operations < 1L || damagePerOperation < 1L || usesPerTool < 1L) {
            throw new IllegalArgumentException("invalid durability requirement");
        }
        long totalDamage = multiplySaturating(operations, damagePerOperation);
        return ceilDiv(totalDamage, usesPerTool);
    }

    static long multiplySaturating(long left, long right) {
        if (left < 0L || right < 0L) {
            throw new IllegalArgumentException("quantities must be non-negative");
        }
        return left != 0L && right > Long.MAX_VALUE / left
                ? Long.MAX_VALUE : left * right;
    }

    static long ceilDiv(long value, long divisor) {
        if (value < 0L || divisor < 1L) {
            throw new IllegalArgumentException("invalid non-negative division");
        }
        return value / divisor + (value % divisor == 0L ? 0L : 1L);
    }
}
