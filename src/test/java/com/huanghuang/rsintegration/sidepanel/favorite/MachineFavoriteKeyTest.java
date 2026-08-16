package com.huanghuang.rsintegration.sidepanel.favorite;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MachineFavoriteKeyTest {
    @Test
    void roundTripsNbtAndMatchesTheSameBindingIdentity() {
        MachineFavoriteKey key = new MachineFavoriteKey(
                new ResourceLocation("minecraft", "overworld"),
                new BlockPos(12, 64, -8), "block.minecraft.furnace");

        assertEquals(key, MachineFavoriteKey.load(key.save()));
        assertTrue(key.matches(key.dimension(), key.pos(), key.blockKey()));
        assertFalse(key.matches(key.dimension(), key.pos(), "block.minecraft.smoker"));
    }

    @Test
    void rejectsMalformedNbt() {
        assertNull(MachineFavoriteKey.load(new net.minecraft.nbt.CompoundTag()));
    }
}
