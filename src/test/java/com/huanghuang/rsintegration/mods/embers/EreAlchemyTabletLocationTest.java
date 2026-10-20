package com.huanghuang.rsintegration.mods.embers;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EreAlchemyTabletLocationTest extends BootstrapTest {

    @Test
    void recognizesTheConcreteAlchemyTabletBlockId() {
        assertTrue(EreAlchemyBatchDelegate.isAlchemyTabletBlockId(
                new ResourceLocation("embers", "alchemy_tablet")));
        assertFalse(EreAlchemyBatchDelegate.isAlchemyTabletBlockId(
                new ResourceLocation("embers", "alchemy_pedestal")));
        assertFalse(EreAlchemyBatchDelegate.isAlchemyTabletBlockId(null));
    }

    @Test
    void pedestalScanNeverReadsUnloadedChunks() {
        Level level = mock(Level.class);
        when(level.isLoaded(any(BlockPos.class))).thenReturn(false);

        assertTrue(EreAlchemyBatchDelegate.scanPedestals(
                level, new BlockPos(32, 64, 32)).isEmpty());
        verify(level, never()).getBlockEntity(any(BlockPos.class));
    }
}
