package com.huanghuang.rsintegration.feature.craftingstation;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.network.grid.IGrid;
import com.refinedmods.refinedstorage.api.network.grid.INetworkAwareGrid;
import com.refinedmods.refinedstorage.api.storage.tracker.IStorageTracker;
import com.refinedmods.refinedstorage.container.GridContainerMenu;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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

    private int countInPlayer(Item item) {
        int total = 0;
        for (ItemStack stack : player.getInventory().items) {
            if (stack.is(item)) total += stack.getCount();
        }
        return total;
    }
}
