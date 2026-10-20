package com.huanghuang.rsintegration.sidepanel.client;

import com.huanghuang.rsintegration.sidepanel.data.BindingInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class BindingSyncChunkAccumulatorTest {
    @AfterEach
    void clearAccumulator() {
        BindingSyncChunkAccumulator.clear();
    }

    @Test
    void appliesOnlyAfterAllChunksArrive() {
        BindingInfo first = info(1);
        BindingInfo second = info(2);

        assertNull(BindingSyncChunkAccumulator.accept(
                7L, 0, 2, List.of(first)));
        assertEquals(List.of(first, second), BindingSyncChunkAccumulator.accept(
                7L, 1, 2, List.of(second)));
    }

    @Test
    void ignoresDuplicateAndStaleChunks() {
        BindingInfo first = info(1);
        BindingInfo second = info(2);

        assertNull(BindingSyncChunkAccumulator.accept(1L, 0, 2, List.of(first)));
        assertNull(BindingSyncChunkAccumulator.accept(1L, 0, 2, List.of(first)));
        assertNull(BindingSyncChunkAccumulator.accept(2L, 1, 2, List.of(second)));
        assertEquals(List.of(first, second), BindingSyncChunkAccumulator.accept(
                2L, 0, 2, List.of(first)));
    }

    private static BindingInfo info(int x) {
        return new BindingInfo(
                "example:terminal",
                new ResourceLocation("minecraft", "overworld"),
                new BlockPos(x, 64, 0),
                "block.example.machine",
                "Machine",
                "example:machine",
                null);
    }
}
