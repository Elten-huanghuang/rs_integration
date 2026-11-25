package com.huanghuang.rsintegration.feature.craftingstation;

import com.huanghuang.rsintegration.crafting.DeepPlanningFixture;
import com.huanghuang.rsintegration.crafting.TaskOutputReception;
import com.huanghuang.rsintegration.storage.rs.RefinedStorageOutputReception;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.network.grid.IGrid;
import com.refinedmods.refinedstorage.api.network.grid.INetworkAwareGrid;
import com.refinedmods.refinedstorage.api.storage.tracker.IStorageTracker;
import com.refinedmods.refinedstorage.api.util.Action;
import com.refinedmods.refinedstorage.apiimpl.storage.tracker.ItemStorageTracker;
import com.refinedmods.refinedstorage.apiimpl.util.ItemStackList;
import com.refinedmods.refinedstorage.container.GridContainerMenu;
import com.refinedmods.refinedstorage.util.StackUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/** 工作站退回网络的物品必须刷新「最后修改时间」，否则只能靠搜索找到。 */
class CraftingStationReturnTrackerTest extends BootstrapTest {
    private ServerPlayer player;
    private Inventory inventory;
    private INetwork network;
    private IStorageTracker<ItemStack> tracker;
    private GridContainerMenu menu;

    @BeforeAll
    static void initializeMod() {
        DeepPlanningFixture.initializeMod();
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws ReflectiveOperationException {
        MinecraftServer server = mock(MinecraftServer.class);
        when(server.isSameThread()).thenReturn(true);
        player = mock(ServerPlayer.class);
        Field serverField = ServerPlayer.class.getField("server");
        serverField.setAccessible(true);
        serverField.set(player, server);
        player.containerMenu = mock(AbstractContainerMenu.class);
        player.connection = mock(ServerGamePacketListenerImpl.class);
        inventory = new Inventory(player);
        when(player.getInventory()).thenReturn(inventory);
        when(player.getName()).thenReturn(Component.literal("station-player"));

        network = mock(INetwork.class, RETURNS_DEEP_STUBS);
        ServerLevel level = mock(ServerLevel.class);
        when(level.dimension()).thenReturn(Level.OVERWORLD);
        when(network.getLevel()).thenReturn(level);
        when(network.getPosition()).thenReturn(BlockPos.ZERO);
        when(network.canRun()).thenReturn(true);
        when(network.getItemStorageCache().getList()).thenReturn(new ItemStackList());
        when(network.getSecurityManager()).thenReturn(null);
        tracker = mock(IStorageTracker.class);
        when(network.getItemStorageTracker()).thenReturn(tracker);
        IGrid grid = mock(IGrid.class,
                withSettings().extraInterfaces(INetworkAwareGrid.class));
        when(((INetworkAwareGrid) grid).getNetwork()).thenReturn(network);
        menu = mock(GridContainerMenu.class);
        when(menu.getGrid()).thenReturn(grid);
    }

    @Test
    void storesReturnedItemsAndUpdatesModificationTimeWithoutFeedingWaitingTask() {
        Runnable trackerListener = mock(Runnable.class);
        ItemStorageTracker realTracker = new ItemStorageTracker(trackerListener);
        when(network.getItemStorageTracker()).thenReturn(realTracker);
        ItemStack returned = new ItemStack(Items.DIAMOND, 3);
        long beforeReturn = System.currentTimeMillis();
        AtomicInteger stored = new AtomicInteger();
        when(network.insertItem(any(), anyInt(), any())).thenAnswer(call -> {
            Action action = call.getArgument(2);
            return RefinedStorageOutputReception.insertItem(network, call.getArgument(0),
                    call.getArgument(1), action, (stack, count) -> {
                        if (action == Action.PERFORM) stored.addAndGet(count);
                        return ItemStack.EMPTY;
                    });
        });

        var key = TaskOutputReception.resource(returned);
        try (var task = TaskOutputReception.open()) {
            task.bindNetwork(network, () -> true);
            task.prepare(List.of(new TaskOutputReception.Demand("out", key, 5, 5))).activate();
            CraftingStationInventorySupport.returnToNetworkOrPlayer(menu, player, returned);

            assertEquals(3, stored.get());
            assertEquals(0, task.received(key));
            assertTrue(realTracker.get(returned).getTime() >= beforeReturn);
            assertEquals("station-player", realTracker.get(returned).getName());
            verify(trackerListener).run();

            assertTrue(network.insertItem(returned, 3, Action.PERFORM).isEmpty());
            assertEquals(3, task.received(key));
            assertEquals(3, stored.get());
        }
    }

    @Test
    void recordsOnlyTheAmountThatReachedTheNetwork() {
        when(network.insertItem(any(), anyInt(), any()))
                .thenReturn(new ItemStack(Items.DIAMOND, 2));

        CraftingStationInventorySupport.returnToNetworkOrPlayer(
                menu, player, new ItemStack(Items.DIAMOND, 5));

        verify(tracker).changed(eq(player),
                argThat(stack -> stack.getCount() == 3));
        assertEquals(2, countInPlayer(Items.DIAMOND));
    }

    @Test
    void leavesTheTrackerAloneWhenNothingReachedTheNetwork() {
        when(network.insertItem(any(), anyInt(), any()))
                .thenReturn(new ItemStack(Items.DIAMOND, 4));

        CraftingStationInventorySupport.returnToNetworkOrPlayer(
                menu, player, new ItemStack(Items.DIAMOND, 4));

        verifyNoInteractions(tracker);
        assertEquals(4, countInPlayer(Items.DIAMOND));
    }

    @Test
    void doesNotReturnAnUncertainWriteToThePlayer() {
        when(network.insertItem(any(), anyInt(), eq(Action.SIMULATE))).thenReturn(ItemStack.EMPTY);
        when(network.insertItem(any(), anyInt(), eq(Action.PERFORM)))
                .thenThrow(new IllegalStateException("写入结果未知"));

        CraftingStationInventorySupport.returnToNetworkOrPlayer(
                menu, player, new ItemStack(Items.DIAMOND, 4));

        assertEquals(0, countInPlayer(Items.DIAMOND));
        verify(network).insertItem(any(), eq(4), eq(Action.PERFORM));
        verify(player, never()).getInventory();
    }

    @Test
    void savesEmptyCraftingMatrixBeforeReturningItems() {
        when(player.level()).thenReturn(mock(Level.class));
        IGrid grid = menu.getGrid();
        AbstractContainerMenu craftingMenu = mock(AbstractContainerMenu.class);
        TransientCraftingContainer matrix = new TransientCraftingContainer(craftingMenu, 3, 3);
        matrix.setItem(0, new ItemStack(Items.DIAMOND, 4));
        matrix.setItem(8, new ItemStack(Items.STONE, 7));
        when(grid.getCraftingMatrix()).thenReturn(matrix);
        when(network.insertItem(any(), anyInt(), any())).thenReturn(ItemStack.EMPTY);

        // 无线终端从物品 NBT 恢复九格，只有收到矩阵变更通知才会更新这份数据。
        CompoundTag terminalTag = new CompoundTag();
        StackUtils.writeItems(matrix, 1, terminalTag);
        doAnswer(call -> {
            StackUtils.writeItems(matrix, 1, terminalTag);
            return null;
        }).when(grid).onCraftingMatrixChanged();

        CraftingStationInventorySupport.returnCraftingMatrix(menu, player);
        TransientCraftingContainer reopened = new TransientCraftingContainer(craftingMenu, 3, 3);
        StackUtils.readItems(reopened, 1, terminalTag);
        assertTrue(reopened.isEmpty());
        assertEquals(9, reopened.getContainerSize());

        CraftingStationInventorySupport.returnCraftingMatrix(menu, player);
        InOrder order = inOrder(grid, network);
        order.verify(grid).onCraftingMatrixChanged();
        order.verify(network).insertItem(argThat(stack -> stack.is(Items.DIAMOND)), eq(4), eq(Action.PERFORM));
        order.verify(network).insertItem(argThat(stack -> stack.is(Items.STONE)), eq(7), eq(Action.PERFORM));
        verify(network, times(2)).insertItem(any(), anyInt(), eq(Action.PERFORM));
    }

    private int countInPlayer(Item item) {
        int total = 0;
        for (ItemStack stack : inventory.items) {
            if (stack.is(item)) total += stack.getCount();
        }
        return total;
    }
}
