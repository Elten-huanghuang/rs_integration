package com.huanghuang.rsintegration.network.binding;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BindingGuiChunkAccessTest extends BootstrapTest {
    private static final BlockPos ALTAR = new BlockPos(-4, 129, -35);

    @Test
    void unloadedMachineGuiProbeDoesNotReadOrLoadItsBlock() {
        Level level = mock(Level.class);
        when(level.hasChunkAt(ALTAR)).thenReturn(false);
        when(level.getBlockState(ALTAR)).thenReturn(Blocks.CHEST.defaultBlockState());

        assertFalse(BindingEventHandler.supportsGuiAt(level, ALTAR));

        verify(level, never()).getBlockState(ALTAR);
        verify(level, never()).getBlockEntity(ALTAR);
    }

    @Test
    void loadedMachineRetainsItsRegisteredGuiCapability() {
        Level level = mock(Level.class);
        when(level.hasChunkAt(ALTAR)).thenReturn(true);
        when(level.getBlockState(ALTAR)).thenReturn(Blocks.CHEST.defaultBlockState());
        String blockClass = Blocks.CHEST.getClass().getName();
        var previous = BindingEventHandler.CLASS_TARGET_MAP.get(blockClass);
        try {
            BindingEventHandler.CLASS_TARGET_MAP.put(blockClass,
                    new BindingEventHandler.MachineBindingTarget(
                            "test", ModType.CUSTOM_GUI, null, List.of(blockClass), "test", true));
            assertTrue(BindingEventHandler.supportsGuiAt(level, ALTAR));

            BindingEventHandler.CLASS_TARGET_MAP.put(blockClass,
                    new BindingEventHandler.MachineBindingTarget(
                            "test", ModType.CUSTOM_GUI, null, List.of(blockClass), "test", false));
            assertFalse(BindingEventHandler.supportsGuiAt(level, ALTAR));
        } finally {
            if (previous == null) {
                BindingEventHandler.CLASS_TARGET_MAP.remove(blockClass);
            } else {
                BindingEventHandler.CLASS_TARGET_MAP.put(blockClass, previous);
            }
        }
    }

    @Test
    void missingLevelOrPositionHasNoGui() {
        Level level = mock(Level.class);

        assertFalse(BindingEventHandler.supportsGuiAt(null, ALTAR));
        assertFalse(BindingEventHandler.supportsGuiAt(level, null));
        verify(level, never()).getBlockState(ALTAR);
    }
}
