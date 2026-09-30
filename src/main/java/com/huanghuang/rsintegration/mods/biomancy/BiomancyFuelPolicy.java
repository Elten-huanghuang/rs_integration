package com.huanghuang.rsintegration.mods.biomancy;

final class BiomancyFuelPolicy {
    private BiomancyFuelPolicy() {}

    static int compare(int firstValue, String firstId, int secondValue, String secondId) {
        int valueOrder = Integer.compare(secondValue, firstValue);
        return valueOrder != 0 ? valueOrder : firstId.compareTo(secondId);
    }

    static int requiredItems(int deficit, int value, int limit) {
        if (deficit <= 0 || value <= 0 || limit <= 0) return 0;
        return (int) Math.min(limit, ((long) deficit + value - 1) / value);
    }
}
