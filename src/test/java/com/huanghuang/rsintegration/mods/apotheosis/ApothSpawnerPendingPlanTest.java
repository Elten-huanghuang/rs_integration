package com.huanghuang.rsintegration.mods.apotheosis;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ApothSpawnerPendingPlanTest {
    @Test
    void refreshRetainsSelectedUpgradeAmountsOnlyForTheSameUnexpiredSpawner() {
        ResourceLocation dimension = new ResourceLocation("minecraft", "overworld");
        BlockPos pos = new BlockPos(12, 64, 34);
        Map<ResourceLocation, Integer> selected = Map.of(
                new ResourceLocation("apotheosis", "spawner/min_delay"), 7,
                new ResourceLocation("apotheosis", "spawner/spawn_count"), 3);
        var pending = new ApothSpawnerUpgradeService.PendingPlan(dimension, pos, selected, 100L);

        assertEquals(selected, pending.selectionFor(dimension, pos, 50L));
        assertEquals(selected, pending.selectionFor(dimension, pos, 100L));
        assertNull(pending.selectionFor(dimension, pos, 101L));
        assertNull(pending.selectionFor(dimension, pos.above(), 50L));
        assertNull(pending.selectionFor(new ResourceLocation("minecraft", "the_nether"), pos, 50L));
    }
}
