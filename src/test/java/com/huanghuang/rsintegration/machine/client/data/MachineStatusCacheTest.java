package com.huanghuang.rsintegration.machine.client.data;

import com.huanghuang.rsintegration.machine.MachineState;
import com.huanghuang.rsintegration.machine.MachineStatus;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MachineStatusCacheTest {
    private static final ResourceLocation OVERWORLD =
            new ResourceLocation("minecraft", "overworld");
    private static final ResourceLocation NETHER =
            new ResourceLocation("minecraft", "the_nether");

    @AfterEach
    void clearCache() {
        MachineStatusCache.getInstance().clear();
    }

    @Test
    void dimensionAndPositionFormOneStableStructuredKey() {
        BlockPos pos = new BlockPos(1, 64, -3);
        MachineStatus status = new MachineStatus(
                MachineState.HAS_OUTPUT, 40, 80,
                ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY);

        MachineStatusCache.getInstance().put(OVERWORLD, pos, status);

        assertEquals(status, MachineStatusCache.getInstance().get(OVERWORLD, pos));
        assertEquals(MachineStatus.UNKNOWN,
                MachineStatusCache.getInstance().get(OVERWORLD, pos.above()));
    }

    @Test
    void clearingOneDimensionDoesNotTouchAnotherOrSequenceState() {
        MachineStatusCache cache = MachineStatusCache.getInstance();
        cache.put(OVERWORLD, BlockPos.ZERO, MachineStatus.UNKNOWN);
        cache.put(NETHER, BlockPos.ZERO, MachineStatus.UNKNOWN);
        cache.acceptSequence(9L);

        cache.clearDimension(OVERWORLD);

        assertEquals(MachineStatus.UNKNOWN, cache.get(OVERWORLD, BlockPos.ZERO));
        assertEquals(MachineStatus.UNKNOWN, cache.get(NETHER, BlockPos.ZERO));
        assertFalse(cache.acceptSequence(9L));
        assertTrue(cache.acceptSequence(10L));
    }
}
