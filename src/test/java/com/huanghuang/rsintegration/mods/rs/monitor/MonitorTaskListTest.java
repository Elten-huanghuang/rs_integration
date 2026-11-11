package com.huanghuang.rsintegration.mods.rs.monitor;

import com.huanghuang.rsintegration.crafting.progress.CraftOutputProgress;
import com.huanghuang.rsintegration.crafting.progress.CraftProgressSnapshot;
import com.huanghuang.rsintegration.mixin.refinedstorage.MonitorTaskAccessor;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.refinedmods.refinedstorage.api.autocrafting.task.ICraftingRequestInfo;
import com.refinedmods.refinedstorage.api.network.grid.IGridTab;
import com.refinedmods.refinedstorage.api.render.IElementDrawers;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.fluids.FluidStack;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MonitorTaskListTest extends BootstrapTest {
    @Test void nativeItemAndFluidTasksShowTheirOwnNamesQuantitiesAndProgress() {
        var item = nativeTask(new ItemStack(Items.DIAMOND), null, 64, 42);
        assertEquals(new ItemStack(Items.DIAMOND).getHoverName(), MonitorTaskList.title(item));
        assertEquals("×64 · 42%", MonitorTaskList.progress(item).getString());

        var water = mock(FluidStack.class);
        when(water.getDisplayName()).thenReturn(Component.literal("水"));
        var fluid = nativeTask(null, water, 12_000, 7);
        assertEquals("水", MonitorTaskList.title(fluid).getString());
        assertEquals("×12K mB · 7%", MonitorTaskList.progress(fluid).getString());
    }

    @Test void machineTaskUsesLongOutputProgressInsteadOfNativePlaceholderQuantity() {
        var task = mock(RsiMonitorTask.class);
        when(task.title()).thenReturn(Component.literal("目标物品"));
        when(task.snapshot()).thenReturn(new CraftProgressSnapshot(UUID.randomUUID(), 1,
                CraftProgressSnapshot.Result.RUNNING, CraftProgressSnapshot.Reason.NONE,
                2, 5, 1, null, List.of(), new CraftOutputProgress(3_000_000_000L, 9_000_000_000L)));
        assertEquals("目标物品", MonitorTaskList.title(task).getString());
        var text = (TranslatableContents) MonitorTaskList.progress(task).getContents();
        assertEquals("rsi.monitor.task_output", text.getKey());
        assertArrayEquals(new Object[]{"3G", "9G"}, text.getArgs());
    }

    @Test void expandedRowSelectsTheExactTaskWhileScrollbarRetainsItsOwnInput() {
        var first = nativeTask(new ItemStack(Items.DIAMOND), null, 1, 0);
        var second = nativeTask(new ItemStack(Items.DIAMOND), null, 64, 20);
        UUID secondId = ((MonitorTaskAccessor) second).rsi$id();
        var selected = new AtomicReference<UUID>();
        var list = new MonitorTaskList(10, 20, 96, 231, mock(IElementDrawers.class), selected::set, widget -> {});
        list.update(List.of(first, second), ((MonitorTaskAccessor) first).rsi$id());
        assertTrue(list.isMouseOver(80, 75));
        list.onClick(80, 75);
        assertEquals(secondId, selected.get());
        selected.set(null);
        assertFalse(list.mouseClicked(95, 75, 0));
        assertNull(selected.get());
    }

    private static IGridTab nativeTask(ItemStack item, FluidStack fluid, int quantity, int percentage) {
        var task = mock(IGridTab.class, withSettings().extraInterfaces(MonitorTaskAccessor.class));
        var access = (MonitorTaskAccessor) task;
        var requested = mock(ICraftingRequestInfo.class);
        when(requested.getItem()).thenReturn(item);
        when(requested.getFluid()).thenReturn(fluid);
        when(access.rsi$id()).thenReturn(UUID.randomUUID());
        when(access.rsi$requested()).thenReturn(requested);
        when(access.rsi$quantity()).thenReturn(quantity);
        when(access.rsi$completionPercentage()).thenReturn(percentage);
        return task;
    }
}
