package com.huanghuang.rsintegration.network.gui;

import com.huanghuang.rsintegration.api.machine.gui.MachineGuiOpener;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.huanghuang.rsintegration.util.ForcedChunkTicketManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    @Test
    void registeredOpenerWithoutNewMenuStopsInteractionAndRevokesAuthorization() {
        var fixture = new OpenFixture();
        BlockEntity machine = mock(BlockEntity.class);
        BlockEntityType<?> type = mock(BlockEntityType.class);
        when(fixture.level.getBlockEntity(fixture.pos)).thenReturn(machine);
        doReturn(type).when(machine).getType();
        MachineGuiOpener opener = (currentPlayer, blockEntity, dimension, position) -> {
            assertTrue(RemoteGuiAuth.hasActiveAuthorization(fixture.playerId));
            assertSame(fixture.level, RemoteMenuConstructionContext.targetLevel(currentPlayer));
        };
        assertTrue(BlockGuiRegistry.register(type, opener));

        fixture.assertOpenFailedAndRevokedAuthorization();

        verify(fixture.state, never()).use(any(), any(), any(), any());
    }

    @Test
    void nativeInteractionThatConsumesActionWithoutOpeningMenuStillFails() {
        var fixture = new OpenFixture();
        when(fixture.state.use(eq(fixture.level), eq(fixture.player), eq(InteractionHand.MAIN_HAND), any()))
                .thenReturn(InteractionResult.SUCCESS);

        fixture.assertOpenFailedAndRevokedAuthorization();

        verify(fixture.state).use(eq(fixture.level), eq(fixture.player), eq(InteractionHand.MAIN_HAND), any());
    }

    private static final class OpenFixture {
        private final BlockPos pos = new BlockPos(19, 64, -7);
        private final UUID playerId = UUID.randomUUID();
        private final ServerPlayer player = mock(ServerPlayer.class);
        private final MinecraftServer server = mock(MinecraftServer.class);
        private final ServerLevel level = mock(ServerLevel.class);
        private final BlockState state = mock(BlockState.class);

        private OpenFixture() {
            when(player.getUUID()).thenReturn(playerId);
            when(player.getServer()).thenReturn(server);
            when(player.level()).thenReturn(level);
            when(player.getMainHandItem()).thenReturn(ItemStack.EMPTY);
            when(server.getLevel(Level.OVERWORLD)).thenReturn(level);
            when(level.dimension()).thenReturn(Level.OVERWORLD);
            when(level.hasChunkAt(pos)).thenReturn(true);
            when(level.getBlockState(pos)).thenReturn(state);
            when(state.getBlock()).thenReturn(Blocks.CHEST);
        }

        private void assertOpenFailedAndRevokedAuthorization() {
            try (var tickets = mockStatic(ForcedChunkTicketManager.class);
                 var lifecycle = mockStatic(ServerLifecycleHooks.class)) {
                lifecycle.when(ServerLifecycleHooks::getCurrentServer).thenReturn(server);
                tickets.when(() -> ForcedChunkTicketManager.retain(level, pos)).thenReturn(true);
                try {
                    assertFalse(BlockGuiRegistry.openGui(player, Level.OVERWORLD, pos));

                    assertFalse(RemoteGuiAuth.hasActiveAuthorization(playerId));
                    assertNull(RemoteMenuConstructionContext.targetLevel(player));
                    tickets.verify(() -> ForcedChunkTicketManager.retain(level, pos));
                    tickets.verify(() -> ForcedChunkTicketManager.release(level, pos));
                } finally {
                    RemoteGuiAuth.onPlayerLogout(playerId);
                }
            }
        }
    }
}
