package com.huanghuang.rsintegration.mods.malum;

import com.huanghuang.rsintegration.reflection.probes.MalumReflection;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.huanghuang.rsintegration.util.Reflect;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.ItemStackHandler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MalumRunicWorkbenchOutputTest extends BootstrapTest {
    private final MalumRunicWorkbenchBatchDelegate delegate = new MalumRunicWorkbenchBatchDelegate();
    private final ServerLevel level = mock(ServerLevel.class);

    @Test
    void missingSlotOutputDoesNotFallBackToGroundOrArmWorldCapture() {
        when(level.isLoaded(BlockPos.ZERO)).thenReturn(true);
        Reflect.setField(delegate, "myLevel", level);
        Reflect.setField(delegate, "myPos", BlockPos.ZERO);
        Reflect.setField(delegate, "expectedOutput", new ItemStack(Items.INK_SAC));

        assertNull(delegate.getExpectedOutput());
        assertTrue(delegate.outputSource().collectFinal(null).stacks().isEmpty());
        verify(level, never()).getEntitiesOfClass(eq(ItemEntity.class), any(AABB.class), any());
    }

    @Test
    void collectsSlotOutputWithItsActualNbt() {
        BlockEntity workbench = mock(BlockEntity.class);
        ItemStackHandler slots = new ItemStackHandler(1);
        ItemStack actual = new ItemStack(Items.INK_SAC, 2);
        actual.getOrCreateTag().putString("variant", "native");
        slots.setStackInSlot(0, actual);
        when(workbench.getCapability(ForgeCapabilities.ITEM_HANDLER, null))
                .thenReturn(LazyOptional.of(() -> slots));
        when(level.getBlockEntity(BlockPos.ZERO)).thenReturn(workbench);
        Reflect.setField(delegate, "myLevel", level);
        Reflect.setField(delegate, "myPos", BlockPos.ZERO);
        Reflect.setField(delegate, "itemHandler", slots);

        Class<?> previous = MalumReflection.runicWorkbenchBEClass;
        MalumReflection.runicWorkbenchBEClass = BlockEntity.class;
        try {
            var stacks = delegate.outputSource().collectFinal(null).stacks();

            assertEquals(1, stacks.size());
            assertEquals(2, stacks.get(0).getCount());
            assertTrue(ItemStack.isSameItemSameTags(actual, stacks.get(0)));
            assertTrue(slots.getStackInSlot(0).isEmpty());
        } finally {
            MalumReflection.runicWorkbenchBEClass = previous;
        }
    }
}
