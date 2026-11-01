package com.huanghuang.rsintegration.mods.vanilla;

import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CookingMachineRecoveryTest extends BootstrapTest {
    @Test
    void finishedWrapperStillCollectsAndReleasesChildReusableMaterials() throws Exception {
        CookingMachineBatchDelegate wrapper = new CookingMachineBatchDelegate();
        IBatchDelegate child = mock(IBatchDelegate.class);
        ServerPlayer player = mock(ServerPlayer.class);
        Field field = childField();
        field.set(wrapper, child);
        List<ItemStack> actual = List.of(new ItemStack(Items.STICK));
        when(child.collectReusableMaterials(player)).thenReturn(actual);

        wrapper.onBatchFinished(player);
        wrapper.onBatchFinished(player);

        assertSame(actual, wrapper.collectReusableMaterials(player));
        wrapper.releaseReusableMaterials(player);
        verify(child).onBatchFinished(player);
        verify(child).releaseReusableMaterials(player);
        assertNull(field.get(wrapper));
    }

    @Test
    void completedWrapperTransfersOwnershipToThePreparedChild() throws Exception {
        CookingMachineBatchDelegate previous = new CookingMachineBatchDelegate();
        CookingMachineBatchDelegate next = new CookingMachineBatchDelegate();
        IBatchDelegate oldChild = mock(IBatchDelegate.class);
        IBatchDelegate newChild = mock(IBatchDelegate.class);
        childField().set(previous, oldChild);
        childField().set(next, newChild);
        when(oldChild.transferReusableMaterialsTo(newChild)).thenReturn(true);
        previous.onBatchFinished(mock(ServerPlayer.class));

        assertTrue(previous.transferReusableMaterialsTo(next));

        verify(oldChild).transferReusableMaterialsTo(newChild);
    }

    @Test
    void wrapperPreservesChildRecoveryEvidenceBeforeDiscardingDelegate() throws Exception {
        CookingMachineBatchDelegate wrapper = new CookingMachineBatchDelegate();
        IBatchDelegate child = mock(IBatchDelegate.class);
        Field field = CookingMachineBatchDelegate.class.getDeclaredField("delegate");
        field.setAccessible(true);
        field.set(wrapper, child);
        when(child.failureRecoveredInputs()).thenReturn(List.of(new ItemStack(Items.IRON_INGOT, 3)));

        wrapper.clearMachineState(mock(BlockEntity.class), null);

        assertEquals(3, wrapper.failureRecoveredInputs().get(0).getCount());
        assertNull(field.get(wrapper));
        verify(child).onBatchFailed(isNull(), anyString());
    }

    @Test
    void releasingReusableMaterialsBeforeFailureStillAllowsPhysicalInputRecovery() throws Exception {
        CookingMachineBatchDelegate wrapper = new CookingMachineBatchDelegate();
        IBatchDelegate child = mock(IBatchDelegate.class);
        ServerPlayer player = mock(ServerPlayer.class);
        MinecraftServer server = mock(MinecraftServer.class);
        ServerLevel level = mock(ServerLevel.class);
        childField().set(wrapper, child);
        wrapper.setMachineDim(Level.OVERWORLD.location());
        wrapper.setMachineServer(server);
        when(server.getLevel(Level.OVERWORLD)).thenReturn(level);
        when(level.hasChunkAt(BlockPos.ZERO)).thenReturn(true);
        when(level.getBlockEntity(BlockPos.ZERO)).thenReturn(mock(BlockEntity.class));
        when(child.getMachinePos()).thenReturn(BlockPos.ZERO);
        when(child.failureRecoveredInputs()).thenReturn(List.of(new ItemStack(Items.IRON_INGOT, 3)));

        wrapper.releaseReusableMaterials(player);
        assertSame(child, childField().get(wrapper));
        wrapper.onBatchFailed(null, "offline");

        verify(child).releaseReusableMaterials(player);
        verify(child).onBatchFailed(isNull(), anyString());
        assertEquals(3, wrapper.failureRecoveredInputs().get(0).getCount());
        assertTrue(wrapper.physicalFailureCleanupCompleted());
        assertNull(childField().get(wrapper));
    }

    private static Field childField() throws Exception {
        Field field = CookingMachineBatchDelegate.class.getDeclaredField("delegate");
        field.setAccessible(true);
        return field;
    }
}
