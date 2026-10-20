package com.huanghuang.rsintegration.crafting.consumption;

/** 燃料值与物品数量的统一换算。 */
public final class FuelMath {
    private FuelMath() {}

    public static long requiredItems(long requiredUnits, long unitsPerItem) {
        if (requiredUnits <= 0L) return 0L;
        if (unitsPerItem < 1L) {
            throw new IllegalArgumentException("unitsPerItem must be positive");
        }
        return DurabilityMath.ceilDiv(requiredUnits, unitsPerItem);
    }

    public static int requiredItems(long requiredUnits, int unitsPerItem, int limit) {
        if (limit <= 0) return 0;
        long required = requiredItems(requiredUnits, unitsPerItem);
        return (int) Math.min(limit, required);
    }

    /** 一个物品覆盖固定次数操作时的燃料数量。 */
    public static int requiredItemsForOperations(long operations,
                                                 int operationsPerItem,
                                                 int limit) {
        if (operations <= 0L || operationsPerItem <= 0 || limit <= 0) return 0;
        long required = DurabilityMath.ceilDiv(operations, operationsPerItem);
        return (int) Math.min(limit, required);
    }
}
