package com.huanghuang.rsintegration.feature.resonance.magic;

/** Botania 有符号魔力调用的无状态换算。 */
final class ManaPoolManaMath {
    private ManaPoolManaMath() {}

    static int insertRequest(int mana, long current, long capacity) {
        if (mana <= 0) return 0;
        long space = capacity > Math.max(0, current)
                ? capacity - Math.max(0, current) : 0;
        return (int) Math.min((long) mana, space);
    }

    static int extractRequest(int mana, long current) {
        if (mana >= 0) return 0;
        long requested = -(long) mana;
        return (int) Math.min(Math.min(requested, Integer.MAX_VALUE),
                Math.max(0, current));
    }
}
