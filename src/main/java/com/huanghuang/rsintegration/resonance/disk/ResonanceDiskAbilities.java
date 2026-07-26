package com.huanghuang.rsintegration.resonance.disk;

import net.minecraft.nbt.CompoundTag;

/** Persistent feature bits owned by a resonance disk's storage UUID. */
public final class ResonanceDiskAbilities {

    public static final int MALUM_VOID_FAVOR = 1;

    private static final String RSI_DATA_TAG = "RSIntegration";
    private static final String ABILITIES_TAG = "Abilities";

    private ResonanceDiskAbilities() {}

    public static int read(CompoundTag diskTag) {
        if (diskTag == null || !diskTag.contains(RSI_DATA_TAG, CompoundTag.TAG_COMPOUND)) {
            return 0;
        }
        return diskTag.getCompound(RSI_DATA_TAG).getInt(ABILITIES_TAG);
    }

    public static void write(CompoundTag diskTag, int abilityMask) {
        if (diskTag == null) return;
        CompoundTag rsi = diskTag.contains(RSI_DATA_TAG, CompoundTag.TAG_COMPOUND)
                ? diskTag.getCompound(RSI_DATA_TAG)
                : new CompoundTag();
        if (abilityMask == 0) {
            rsi.remove(ABILITIES_TAG);
        } else {
            rsi.putInt(ABILITIES_TAG, abilityMask);
        }
        if (rsi.isEmpty()) {
            diskTag.remove(RSI_DATA_TAG);
        } else {
            diskTag.put(RSI_DATA_TAG, rsi);
        }
    }
}
