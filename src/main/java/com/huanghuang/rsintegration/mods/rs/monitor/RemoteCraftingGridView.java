package com.huanghuang.rsintegration.mods.rs.monitor;

import com.huanghuang.rsintegration.ModItems;
import com.refinedmods.refinedstorage.api.network.grid.GridType;
import com.refinedmods.refinedstorage.api.network.grid.IGrid;
import com.refinedmods.refinedstorage.api.network.grid.IGridTab;
import com.refinedmods.refinedstorage.api.network.grid.handler.IFluidGridHandler;
import com.refinedmods.refinedstorage.api.network.grid.handler.IItemGridHandler;
import com.refinedmods.refinedstorage.api.storage.cache.IStorageCache;
import com.refinedmods.refinedstorage.api.storage.cache.IStorageCacheListener;
import com.refinedmods.refinedstorage.api.util.IFilter;
import com.refinedmods.refinedstorage.api.util.IStackList;
import com.refinedmods.refinedstorage.blockentity.data.BlockEntitySynchronizationManager;
import com.refinedmods.refinedstorage.blockentity.grid.GridBlockEntity;
import com.refinedmods.refinedstorage.inventory.item.FilterItemHandler;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandlerModifiable;

import java.util.ArrayList;
import java.util.List;

/** 客户端的原生合成终端视图。仅接收槽位和参数同步，不查找任何维度的方块实体。 */
final class RemoteCraftingGridView implements IGrid {
    private final List<IFilter> filters = new ArrayList<>();
    private final List<IGridTab> tabs = new ArrayList<>();
    private final FilterItemHandler filter = new FilterItemHandler(filters, tabs);
    private final ResultContainer result = new ResultContainer();
    private final CraftingContainer matrix = new TransientCraftingContainer(new AbstractContainerMenu(null, -1) {
        @Override public ItemStack quickMoveStack(Player player, int slot) { return ItemStack.EMPTY; }
        @Override public boolean stillValid(Player player) { return true; }
    }, 3, 3);

    RemoteCraftingGridView() {
        // 与有线终端一致，原生过滤槽同时接受 RSI 的虚空升级。
        filter.addValidator(stack -> ModItems.RS_VOID_UPGRADE != null
                && stack.is(ModItems.RS_VOID_UPGRADE.get()));
    }

    @Override public GridType getGridType() { return GridType.CRAFTING; }
    @Override public Component getTitle() { return Component.translatable("gui.refinedstorage.crafting_grid"); }
    @Override public boolean isGridActive() { return true; }
    @Override public int getSlotId() { return -1; }
    @Override public IItemHandlerModifiable getFilter() { return filter; }
    @Override public List<IFilter> getFilters() { return filters; }
    @Override public List<IGridTab> getTabs() { return tabs; }
    @Override public CraftingContainer getCraftingMatrix() { return matrix; }
    @Override public ResultContainer getCraftingResult() { return result; }
    @Override public int getViewType() { return GridBlockEntity.VIEW_TYPE.getValue(); }
    @Override public int getSortingType() { return GridBlockEntity.SORTING_TYPE.getValue(); }
    @Override public int getSortingDirection() { return GridBlockEntity.SORTING_DIRECTION.getValue(); }
    @Override public int getSearchBoxMode() { return GridBlockEntity.SEARCH_BOX_MODE.getValue(); }
    @Override public int getSize() { return GridBlockEntity.SIZE.getValue(); }
    @Override public int getTabSelected() { return GridBlockEntity.TAB_SELECTED.getValue(); }
    @Override public int getTabPage() { return GridBlockEntity.TAB_PAGE.getValue(); }
    @Override public int getTotalTabPages() { return Math.max(0, tabs.size() - 1) / TABS_PER_PAGE; }

    // RS 参数包按服务端当前菜单定位方块实体，不按客户端世界坐标定位，原生同步可跨维度复用。
    @Override public void onViewTypeChanged(int value) { BlockEntitySynchronizationManager.setParameter(GridBlockEntity.VIEW_TYPE, value); }
    @Override public void onSortingTypeChanged(int value) { BlockEntitySynchronizationManager.setParameter(GridBlockEntity.SORTING_TYPE, value); }
    @Override public void onSortingDirectionChanged(int value) { BlockEntitySynchronizationManager.setParameter(GridBlockEntity.SORTING_DIRECTION, value); }
    @Override public void onSearchBoxModeChanged(int value) { BlockEntitySynchronizationManager.setParameter(GridBlockEntity.SEARCH_BOX_MODE, value); }
    @Override public void onSizeChanged(int value) { BlockEntitySynchronizationManager.setParameter(GridBlockEntity.SIZE, value); }
    @Override public void onTabSelectionChanged(int value) { BlockEntitySynchronizationManager.setParameter(GridBlockEntity.TAB_SELECTED, value); }
    @Override public void onTabPageChanged(int value) {
        if (value >= 0 && value <= getTotalTabPages()) BlockEntitySynchronizationManager.setParameter(GridBlockEntity.TAB_PAGE, value);
    }

    // 库存操作和合成由 RS 消息交给服务端真实 GridNetworkNode；客户端仅保存显示副本。
    @Override public IStorageCacheListener createListener(ServerPlayer player) { throw new IllegalStateException("client grid view"); }
    @Override public IStorageCache getStorageCache() { return null; }
    @Override public IItemGridHandler getItemHandler() { return null; }
    @Override public IFluidGridHandler getFluidHandler() { return null; }
    @Override public void onCraftingMatrixChanged() {}
    @Override public void onCrafted(Player player, IStackList<ItemStack> items, IStackList<ItemStack> used) {}
    @Override public void onCraftedShift(Player player) {}
    @Override public void onClear(Player player) {}
    @Override public void onRecipeTransfer(Player player, ItemStack[][] recipe) {}
    @Override public void onClosed(Player player) {}
}
