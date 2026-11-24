package com.huanghuang.rsintegration.feature.craftingstation;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.network.grid.IGrid;
import com.refinedmods.refinedstorage.api.network.grid.INetworkAwareGrid;
import com.refinedmods.refinedstorage.api.storage.tracker.IStorageTracker;
import com.refinedmods.refinedstorage.api.util.Action;
import com.refinedmods.refinedstorage.container.GridContainerMenu;
import com.refinedmods.refinedstorage.util.StackUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/** 工作站退回网络的物品必须刷新「最后修改时间」，否则只能靠搜索找到。 */
class CraftingStationReturnTrackerTest extends BootstrapTest {
    private Player player;
    private INetwork network;
    private IStorageTracker<ItemStack> tracker;
    private GridContainerMenu menu;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        player = mock(Player.class);
        when(player.getInventory()).thenReturn(new Inventory(player));
        network = mock(INetwork.class);
        tracker = mock(IStorageTracker.class);
        when(network.getItemStorageTracker()).thenReturn(tracker);
        IGrid grid = mock(IGrid.class,
                withSettings().extraInterfaces(INetworkAwareGrid.class));
        when(((INetworkAwareGrid) grid).getNetwork()).thenReturn(network);
        menu = mock(GridContainerMenu.class);
        when(menu.getGrid()).thenReturn(grid);
    }

    @Test
    void recordsReturnedItemsAsRecentlyModified() {
        when(network.insertItem(any(), anyInt(), any())).thenReturn(ItemStack.EMPTY);

        ItemStack returned = new ItemStack(Items.DIAMOND_SWORD, 3);
        CraftingStationInventorySupport.returnToNetworkOrPlayer(menu, player, returned);

        ArgumentCaptor<ItemStack> accepted = ArgumentCaptor.forClass(ItemStack.class);
        verify(tracker).changed(eq(player), accepted.capture());
        assertTrue(ItemStack.isSameItemSameTags(returned, accepted.getValue()));
        assertEquals(3, accepted.getValue().getCount());
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
    void savesEmptyCraftingMatrixBeforeReturningItems() {
        when(player.level()).thenReturn(mock(Level.class));
        IGrid grid = menu.getGrid();
        AbstractContainerMenu craftingMenu = mock(AbstractContainerMenu.class);
        TransientCraftingContainer matrix = new TransientCraftingContainer(craftingMenu, 3, 3);
        matrix.setItem(0, new ItemStack(Items.DIAMOND, 4));
        matrix.setItem(8, new ItemStack(Items.STONE, 7));
        when(grid.getCraftingMatrix()).thenReturn(matrix);
        when(network.insertItem(any(), anyInt(), eq(Action.PERFORM))).thenReturn(ItemStack.EMPTY);

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
        for (ItemStack stack : player.getInventory().items) {
            if (stack.is(item)) total += stack.getCount();
        }
        return total;
    }
}
