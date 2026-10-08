package com.huanghuang.rsintegration.crafting.fluid;

import com.huanghuang.rsintegration.mixin.refinedstorage.FluidContainerPreflightMixin;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.network.grid.IGrid;
import com.refinedmods.refinedstorage.api.network.grid.INetworkAwareGrid;
import com.refinedmods.refinedstorage.api.network.security.ISecurityManager;
import com.refinedmods.refinedstorage.api.network.security.Permission;
import com.refinedmods.refinedstorage.api.storage.tracker.IStorageTracker;
import com.refinedmods.refinedstorage.api.util.Action;
import com.refinedmods.refinedstorage.container.GridContainerMenu;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidUtil;
import net.minecraftforge.fluids.capability.IFluidHandlerItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 直接验证注入方法的结算行为；注入目标另由字节码契约测试检查。 */
class BucketNativeTerminalTest extends BootstrapTest {
    private MockedStatic<FluidUtil> capabilities;

    @BeforeEach void supplyCapabilities() {
        capabilities = FluidContainerBucketTestFixtures.capabilities();
    }

    @AfterEach void releaseCapabilities() {
        capabilities.close();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 3})
    void missingCapabilityBucketInsertsOneBucketAndKeepsRemainingStack(int count) throws Exception {
        Fixture f = new Fixture(count);
        assertTrue(f.click().isCancelled());
        verify(f.network).insertFluid(argThat(fluid -> fluid.isFluidStackIdentical(f.fluid)),
                eq(1000), eq(Action.PERFORM));
        ArgumentCaptor<ItemStack> carried = ArgumentCaptor.forClass(ItemStack.class);
        verify(f.menu).setCarried(carried.capture());
        if (count == 1) {
            assertTrue(carried.getValue().is(Items.BUCKET));
            assertEquals(1, carried.getValue().getCount());
            verifyNoInteractions(f.inventory);
        } else {
            assertTrue(carried.getValue().is(f.original.getItem()));
            assertEquals(count - 1, carried.getValue().getCount());
            verify(f.inventory).placeItemBackInInventory(argThat(stack -> stack.is(Items.BUCKET)
                    && stack.getCount() == 1));
        }
        assertEquals(count, f.original.getCount());
        verify(f.tracker).changed(eq(f.player), argThat(fluid -> fluid.isFluidStackIdentical(f.fluid)));
    }

    @Test
    void insufficientCapacityLeavesBucketAndDoesNotPerformInsertion() throws Exception {
        Fixture f = new Fixture(3);
        when(f.network.insertFluid(any(), anyInt(), eq(Action.SIMULATE)))
                .thenReturn(new FluidStack(f.fluid, 1));
        assertTrue(f.click().isCancelled());
        verify(f.network, never()).insertFluid(any(), anyInt(), eq(Action.PERFORM));
        verify(f.menu).setCarried(f.original);
        assertEquals(3, f.original.getCount());
        verifyNoInteractions(f.inventory, f.tracker);
    }

    @ParameterizedTest
    @ValueSource(strings = {"permission", "power", "grid", "menu", "network", "closed", "grid_type", "menu_type"})
    void insertionRequiresPermissionAndValidActiveMenu(String invalid) throws Exception {
        Fixture f = new Fixture(1);
        switch (invalid) {
            case "permission" -> when(f.security.hasPermission(Permission.INSERT, f.player)).thenReturn(false);
            case "power" -> when(f.network.canRun()).thenReturn(false);
            case "grid" -> when(f.grid.isGridActive()).thenReturn(false);
            case "menu" -> when(f.menu.stillValid(f.player)).thenReturn(false);
            case "network" -> when(f.grid.getNetwork()).thenReturn(mock(INetwork.class));
            case "closed" -> f.player.containerMenu = null;
            case "grid_type" -> when(f.menu.getGrid()).thenReturn(mock(IGrid.class));
            case "menu_type" -> {
                AbstractContainerMenu other = mock(AbstractContainerMenu.class);
                when(other.getCarried()).thenReturn(f.original);
                f.player.containerMenu = other;
            }
            default -> fail(invalid);
        }
        assertTrue(f.click().isCancelled());
        verify(f.network, never()).insertFluid(any(), anyInt(), any());
        verify(f.menu, never()).setCarried(any());
        verifyNoInteractions(f.inventory, f.tracker);
    }

    @Test
    void customCapabilityAndNonBucketContinueThroughOriginalHandler() throws Exception {
        Fixture f = new Fixture(1);
        IFluidHandlerItem handler = mock(IFluidHandlerItem.class);
        capabilities.when(() -> FluidUtil.getFluidHandler(any(ItemStack.class)))
                .thenReturn(LazyOptional.of(() -> handler));
        assertFalse(f.click().isCancelled());
        when(f.menu.getCarried()).thenReturn(new ItemStack(Items.STONE));
        assertFalse(f.click().isCancelled());
        when(f.menu.getCarried()).thenReturn(ItemStack.EMPTY);
        assertFalse(f.click().isCancelled());
        verify(f.network, never()).insertFluid(any(), anyInt(), any());
        verify(f.menu, never()).setCarried(any());
    }

    private static final class Fixture {
        final INetwork network = mock(INetwork.class);
        final ServerPlayer player = mock(ServerPlayer.class);
        final GridContainerMenu menu = mock(GridContainerMenu.class);
        final INetworkAwareGrid grid = mock(INetworkAwareGrid.class);
        final ISecurityManager security = mock(ISecurityManager.class);
        final Inventory inventory = mock(Inventory.class);
        final IStorageTracker<FluidStack> tracker = mock(IStorageTracker.class);
        final FluidContainerPreflightMixin mixin = new FluidContainerPreflightMixin() {};
        final ItemStack original;
        final FluidStack fluid;

        Fixture(int count) throws Exception {
            var buckets = FluidContainerBucketTestFixtures.subclassBuckets("native_poisonwater");
            original = new ItemStack(buckets.filled(), count);
            fluid = new FluidStack(((BucketItem) buckets.filled()).getFluid(), 1000);
            Field field = FluidContainerPreflightMixin.class.getDeclaredField("network");
            field.setAccessible(true);
            field.set(mixin, network);
            player.containerMenu = menu;
            when(menu.getGrid()).thenReturn(grid);
            when(menu.getCarried()).thenReturn(original);
            when(menu.stillValid(player)).thenReturn(true);
            when(grid.getNetwork()).thenReturn(network);
            when(grid.isGridActive()).thenReturn(true);
            when(network.canRun()).thenReturn(true);
            when(network.getSecurityManager()).thenReturn(security);
            when(security.hasPermission(Permission.INSERT, player)).thenReturn(true);
            when(network.insertFluid(any(), anyInt(), any(Action.class))).thenReturn(FluidStack.EMPTY);
            when(network.getFluidStorageTracker()).thenReturn(tracker);
            when(player.getInventory()).thenReturn(inventory);
        }

        CallbackInfo click() throws Exception {
            Method method = FluidContainerPreflightMixin.class.getDeclaredMethod("rsi$insertBucketWithoutCapability",
                    ServerPlayer.class, CallbackInfo.class);
            method.setAccessible(true);
            CallbackInfo callback = new CallbackInfo("onInsertHeldContainer", true);
            method.invoke(mixin, player, callback);
            return callback;
        }
    }
}
