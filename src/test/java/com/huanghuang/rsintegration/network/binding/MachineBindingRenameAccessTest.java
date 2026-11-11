package com.huanghuang.rsintegration.network.binding;

import com.huanghuang.rsintegration.network.binding.MachineBindingService.PlayerEndpoint;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

class MachineBindingRenameAccessTest extends BootstrapTest {
    @Test
    void renameOnlyAcceptsTheBookAndEndpointCurrentlyCarriedByThePlayer() {
        ServerPlayer player = mock(ServerPlayer.class);
        MachineBindingSavedData data = new MachineBindingSavedData();
        UUID carriedBook = UUID.randomUUID();
        UUID otherBook = UUID.randomUUID();
        MachineEndpointId carriedEndpoint = MachineEndpointId.create();
        MachineEndpointId otherEndpoint = MachineEndpointId.create();
        BindingStorage.BindingEntry entry = new BindingStorage.BindingEntry(
                ResourceLocation.tryParse("minecraft:overworld"), BlockPos.ZERO,
                "block.minecraft.stone", "minecraft:stone", null);
        data.put(carriedBook, carriedEndpoint, entry);
        data.put(otherBook, otherEndpoint, entry);

        try (var service = mockStatic(MachineBindingService.class, CALLS_REAL_METHODS);
             var savedData = mockStatic(MachineBindingSavedData.class)) {
            service.when(() -> MachineBindingService.collectPlayerEndpoints(player))
                    .thenReturn(List.of(new PlayerEndpoint(carriedBook, null,
                            carriedEndpoint, entry, 1L)));
            savedData.when(() -> MachineBindingSavedData.get(player.server)).thenReturn(data);

            assertFalse(MachineBindingService.rename(player, otherBook, otherEndpoint, "越权名称"));
            assertFalse(MachineBindingService.rename(player, carriedBook, otherEndpoint, "错误端点"));
            assertTrue(MachineBindingService.rename(player, carriedBook, carriedEndpoint, "一楼机器"));
            assertEquals("一楼机器", data.bindings(carriedBook).get(0).customName());
            assertEquals("", data.bindings(otherBook).get(0).customName());
        }
    }
}
