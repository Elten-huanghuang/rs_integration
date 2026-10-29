package com.huanghuang.rsintegration.mods.vanilla;

import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CookingMachineRecoveryTest extends BootstrapTest {
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
}
