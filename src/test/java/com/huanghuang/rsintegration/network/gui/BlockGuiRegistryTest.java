package com.huanghuang.rsintegration.network.gui;

import com.huanghuang.rsintegration.api.machine.gui.MachineGuiOpener;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.level.block.entity.BlockEntityType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class BlockGuiRegistryTest extends BootstrapTest {
    @Test
    void registrationIsSingleProtocolAndIdempotent() {
        BlockEntityType<?> type = mock(BlockEntityType.class);
        MachineGuiOpener opener = (player, machine, dimension, position) -> {};

        assertTrue(BlockGuiRegistry.register(type, opener));
        assertFalse(BlockGuiRegistry.register(type, opener));
        assertTrue(BlockGuiRegistry.hasGui(type));
        assertTrue(BlockGuiRegistry.size() >= 1);
    }
}
