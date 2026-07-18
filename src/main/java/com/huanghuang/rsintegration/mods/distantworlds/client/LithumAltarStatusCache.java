package com.huanghuang.rsintegration.mods.distantworlds.client;

import com.huanghuang.rsintegration.mods.distantworlds.LithumAltarStatusSnapshot;

public final class LithumAltarStatusCache {
    private static LithumAltarStatusSnapshot snapshot;
    private static long updatedAt;

    private LithumAltarStatusCache() {}

    public static void update(LithumAltarStatusSnapshot value) {
        snapshot = value;
        updatedAt = System.currentTimeMillis();
    }

    public static LithumAltarStatusSnapshot current() {
        if (snapshot != null && System.currentTimeMillis() - updatedAt > 5_000) clear();
        return snapshot;
    }

    public static void clear() {
        snapshot = null;
        updatedAt = 0;
    }
}
