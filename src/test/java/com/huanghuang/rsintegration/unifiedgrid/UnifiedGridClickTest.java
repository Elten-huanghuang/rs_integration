package com.huanghuang.rsintegration.unifiedgrid;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.huanghuang.rsintegration.crafting.fluid.FluidContainerBucketTestFixtures;
import com.huanghuang.rsintegration.unifiedgrid.client.UnifiedGridClient;
import com.huanghuang.rsintegration.unifiedgrid.client.UnifiedGridView;
import com.refinedmods.refinedstorage.api.network.grid.IGrid;
import com.refinedmods.refinedstorage.container.GridContainerMenu;
import com.refinedmods.refinedstorage.screen.grid.GridScreen;
import com.refinedmods.refinedstorage.screen.grid.stack.IGridStack;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidUtil;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandlerItem;
import net.minecraftforge.common.util.LazyOptional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 从客户端实际点击入口验证请求，避免只检查服务器自己生成的标志。 */
class UnifiedGridClickTest extends BootstrapTest {
    @ParameterizedTest
    @CsvSource({"0,false,0", "1,false,1", "0,true,4", "1,true,5"})
    void itemClickKeepsNativeCursorHalfAndShiftFlags(int button, boolean shift, int flags) {
        try (MockedStatic<Screen> keys = mockStatic(Screen.class)) {
            keys.when(Screen::hasShiftDown).thenReturn(shift);
            Fixture f = new Fixture(GridResourceKind.ITEM);
            assertTrue(UnifiedGridClient.click(f.screen, 10, 10, button));
            verify(f.view).request(GridResourceKind.ITEM, 17, UnifiedGridActionPacket.Action.EXTRACT, flags);
        }
    }

    @ParameterizedTest
    @CsvSource({"0,false,0", "0,true,4"})
    void fluidLeftClickFillsContainerAndShiftSelectsInventory(int button, boolean shift, int flags) {
        try (MockedStatic<Screen> keys = mockStatic(Screen.class)) {
            keys.when(Screen::hasShiftDown).thenReturn(shift);
            Fixture f = new Fixture(GridResourceKind.FLUID);
            assertTrue(UnifiedGridClient.click(f.screen, 10, 10, button));
            verify(f.view).request(GridResourceKind.FLUID, 17, UnifiedGridActionPacket.Action.FILL_FLUID, flags);
        }
    }

    @Test void shiftWithHeldOrdinaryItemStillInsertsItemAndFluidTargetDoesNotNeedShift() {
        try (MockedStatic<Screen> keys = mockStatic(Screen.class)) {
            keys.when(Screen::hasShiftDown).thenReturn(true);
            Fixture item = new Fixture(GridResourceKind.ITEM);
            when(item.menu.getCarried()).thenReturn(new ItemStack(Items.DIAMOND));
            UnifiedGridClient.click(item.screen, 10, 10, 0);
            verify(item.view).request(GridResourceKind.ITEM, 0, UnifiedGridActionPacket.Action.INSERT_ITEM, 0);
            keys.when(Screen::hasShiftDown).thenReturn(false);
            Fixture fluid = new Fixture(GridResourceKind.FLUID);
            when(fluid.menu.getCarried()).thenReturn(new ItemStack(Items.WATER_BUCKET));
            UnifiedGridClient.click(fluid.screen, 10, 10, 0);
            verify(fluid.view).request(GridResourceKind.FLUID, 17, UnifiedGridActionPacket.Action.FILL_FLUID, 0);
        }
    }

    @Test void filledBucketRightClickOnBlankSpaceDrainsWithoutChangingItsContentsOnClient() {
        try (MockedStatic<Screen> keys = mockStatic(Screen.class);
             MockedStatic<FluidUtil> fluids = mockStatic(FluidUtil.class, CALLS_REAL_METHODS)) {
            Fixture f = new Fixture(GridResourceKind.ITEM);
            ItemStack bucket = new ItemStack(Items.WATER_BUCKET);
            // 普通单测未运行 Forge capability transformer，在适配边界模拟容器读取。
            fluids.when(() -> FluidUtil.getFluidHandler(any(ItemStack.class))).thenAnswer(invocation -> {
                ItemStack copy = invocation.getArgument(0);
                assertNotSame(bucket, copy);
                IFluidHandlerItem handler = mock(IFluidHandlerItem.class);
                when(handler.drain(1000, IFluidHandler.FluidAction.SIMULATE)).thenAnswer(call -> {
                    copy.setCount(0);
                    return new FluidStack(Fluids.WATER, 1000);
                });
                return LazyOptional.of(() -> handler);
            });
            when(f.menu.getCarried()).thenReturn(bucket);
            when(f.view.getStacks()).thenReturn(List.of());
            when(f.view.row(null)).thenReturn(null);
            UnifiedGridClient.click(f.screen, 10, 10, 1);
            verify(f.view).request(GridResourceKind.FLUID, 0, UnifiedGridActionPacket.Action.INSERT_FLUID, 0);
            assertTrue(bucket.is(Items.WATER_BUCKET));
            assertEquals(1, bucket.getCount());
        }
    }

    @Test void missingCapabilityBucketRightClickSendsFluidInsertionRatherThanItemInsertion() {
        try (MockedStatic<Screen> keys = mockStatic(Screen.class);
             MockedStatic<FluidUtil> fluids = FluidContainerBucketTestFixtures.capabilities()) {
            var buckets = FluidContainerBucketTestFixtures.subclassBuckets("click_poisonwater");
            Fixture f = new Fixture(GridResourceKind.ITEM);
            ItemStack cursor = new ItemStack(buckets.filled(), 3);
            when(f.menu.getCarried()).thenReturn(cursor);
            when(f.view.getStacks()).thenReturn(List.of());
            when(f.view.row(null)).thenReturn(null);
            assertTrue(UnifiedGridClient.click(f.screen, 10, 10, 1));
            verify(f.view).request(GridResourceKind.FLUID, 0, UnifiedGridActionPacket.Action.INSERT_FLUID, 0);
            verify(f.view, never()).request(eq(GridResourceKind.ITEM), anyInt(), any(), anyInt());
            assertEquals(3, cursor.getCount());
            assertTrue(cursor.is(buckets.filled()));
            assertFalse(cursor.hasTag());
        }
    }

    @Test void filledBucketLeftClickOnBlankSpaceStoresBucketItemAndEmptyRightClickNeverFills() {
        try (MockedStatic<Screen> keys = mockStatic(Screen.class)) {
            Fixture f = new Fixture(GridResourceKind.ITEM);
            when(f.menu.getCarried()).thenReturn(new ItemStack(Items.WATER_BUCKET));
            when(f.view.getStacks()).thenReturn(List.of());
            when(f.view.row(null)).thenReturn(null);
            UnifiedGridClient.click(f.screen, 10, 10, 0);
            verify(f.view).request(GridResourceKind.ITEM, 0, UnifiedGridActionPacket.Action.INSERT_ITEM, 0);
            Fixture fluid = new Fixture(GridResourceKind.FLUID);
            UnifiedGridClient.click(fluid.screen, 10, 10, 1);
            verify(fluid.view, never()).request(any(), anyInt(), any(), anyInt());
        }
    }

    private static final class Fixture {
        private final GridScreen screen = mock(GridScreen.class);
        private final GridContainerMenu menu = mock(GridContainerMenu.class);
        private final UnifiedGridView view = mock(UnifiedGridView.class);
        private Fixture(GridResourceKind kind) {
            IGrid grid = mock(IGrid.class);
            IGridStack stack = mock(IGridStack.class);
            when(screen.getGrid()).thenReturn(grid);
            when(grid.isGridActive()).thenReturn(true);
            when(screen.getMenu()).thenReturn(menu);
            when(menu.getCarried()).thenReturn(ItemStack.EMPTY);
            when(screen.getView()).thenReturn(view);
            when(screen.isOverSlotArea(anyDouble(), anyDouble())).thenReturn(true);
            when(view.getStacks()).thenReturn(List.of(stack));
            when(view.row(stack)).thenReturn(new UnifiedGridView.Row(kind, 17, stack, null));
            when(view.canEmptyCarried(any())).thenCallRealMethod();
        }
    }
}
