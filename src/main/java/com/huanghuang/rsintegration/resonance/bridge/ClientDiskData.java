package com.huanghuang.rsintegration.resonance.bridge;

/**
 * Client-side cache for resonance disk data synced from the server.
 * Stores the gem count so the Avarice Ring tooltip can include disk gems.
 */
public final class ClientDiskData {

    private static int gemCount;

    private ClientDiskData() {}

    public static int getGemCount() {
        return gemCount;
    }

    public static void setGemCount(int count) {
        gemCount = count;
    }
}
