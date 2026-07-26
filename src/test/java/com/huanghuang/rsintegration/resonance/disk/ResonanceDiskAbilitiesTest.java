package com.huanghuang.rsintegration.resonance.disk;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ResonanceDiskAbilitiesTest {

    @Test
    void roundTripsAbilitiesWithoutChangingDelegateData() {
        CompoundTag disk = new CompoundTag();
        disk.putInt("Stored", 37);

        ResonanceDiskAbilities.write(disk, ResonanceDiskAbilities.MALUM_VOID_FAVOR);

        assertEquals(37, disk.getInt("Stored"));
        assertEquals(ResonanceDiskAbilities.MALUM_VOID_FAVOR,
                ResonanceDiskAbilities.read(disk));
    }

    @Test
    void zeroMaskRemovesOnlyRsiMetadata() {
        CompoundTag disk = new CompoundTag();
        disk.putString("Owner", "unchanged");
        ResonanceDiskAbilities.write(disk, ResonanceDiskAbilities.MALUM_VOID_FAVOR);

        ResonanceDiskAbilities.write(disk, 0);

        assertEquals("unchanged", disk.getString("Owner"));
        assertEquals(0, ResonanceDiskAbilities.read(disk));
        assertFalse(disk.contains("RSIntegration"));
    }
}
