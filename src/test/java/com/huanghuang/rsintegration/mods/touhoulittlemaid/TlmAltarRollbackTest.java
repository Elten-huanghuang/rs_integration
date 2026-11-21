package com.huanghuang.rsintegration.mods.touhoulittlemaid;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.huanghuang.rsintegration.util.Reflect;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TlmAltarRollbackTest extends BootstrapTest {
    @Test
    void rollbackWithoutExpectedOutputDoesNotSearchForGroundItems() {
        ServerPlayer player = mock(ServerPlayer.class);
        ServerLevel level = mock(ServerLevel.class);
        when(player.serverLevel()).thenReturn(level);
        TlmAltarBatchDelegate delegate = new TlmAltarBatchDelegate();
        Reflect.setField(delegate, "player", player);
        Reflect.setField(delegate, "myPos", BlockPos.ZERO);

        delegate.clearMachineState(mock(BlockEntity.class), player);

        verify(level, never()).getEntitiesOfClass(eq(ItemEntity.class), any(AABB.class));
    }
}
