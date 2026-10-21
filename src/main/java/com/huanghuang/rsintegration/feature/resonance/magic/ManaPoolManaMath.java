package com.huanghuang.rsintegration.feature.resonance.magic;

/** Botania 有符号魔力调用的无状态换算。 */
final class ManaPoolManaMath {
    private ManaPoolManaMath() {}

    static int insertRequest(int mana, int current, int capacity) {
        if (mana <= 0) return 0;
        int space = Math.max(0, capacity - Math.max(0, current));
        return Math.min(mana, space);
    }

    static int extractRequest(int mana, int current) {
        if (mana >= 0) return 0;
        long requested = -(long) mana;
        return (int) Math.min(Math.min(requested, Integer.MAX_VALUE),
                Math.max(0, current));
    }
}
