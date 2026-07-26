package com.huanghuang.rsintegration.resonance.bridge;

/**
 * Client-side cache for resonance disk data synced from the server.
 * Stores the gem count so the Avarice Ring tooltip can include disk gems.
 */
public final class ClientDiskData {

    private static int gemCount;
    private static int lycheeCatalystMask;
    private static int abilityMask;
    private static long revision = -1L;

    private ClientDiskData() {}

    public static int getGemCount() {
        return gemCount;
    }

    public static void setGemCount(int count) {
        gemCount = count;
    }

    public static boolean hasPowderSnowBucket() {
        return hasLycheeCatalyst(
                com.huanghuang.rsintegration.mods.lychee.LycheeVirtualCatalysts.POWDER_SNOW_BUCKET);
    }

    public static boolean hasLycheeCatalyst(int requiredMask) {
        return com.huanghuang.rsintegration.mods.lychee.LycheeVirtualCatalysts
                .hasCatalyst(lycheeCatalystMask, requiredMask);
    }

    public static int lycheeCatalystMask() {
        return lycheeCatalystMask;
    }

    public static boolean hasAbility(int ability) {
        return (abilityMask & ability) == ability;
    }

    public static int abilityMask() {
        return abilityMask;
    }

    public static long revision() {
        return revision;
    }

    public static void apply(int count, int catalystMask, int newAbilityMask,
                             long newRevision) {
        if (newRevision < revision) return;
        gemCount = count;
        lycheeCatalystMask = catalystMask;
        abilityMask = newAbilityMask;
        revision = newRevision;
    }

    public static void clear() {
        gemCount = 0;
        lycheeCatalystMask = 0;
        abilityMask = 0;
        revision = -1L;
    }
}
