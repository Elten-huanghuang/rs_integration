package com.huanghuang.rsintegration.craftingstation;

import com.refinedmods.refinedstorage.api.network.grid.IGrid;
import com.refinedmods.refinedstorage.container.GridContainerMenu;
import com.refinedmods.refinedstorage.container.slot.grid.ResultCraftingGridSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** 将 RS 合成结果槽代理到当前工作站状态的通用结果槽。 */
public class CraftingStationResultSlot extends ResultCraftingGridSlot {
    private final CraftingStationState state;

    public CraftingStationResultSlot(GridContainerMenu menu, Player player, IGrid grid,
                                     int index, int x, int y) {
        super(player, grid, index, x, y);
        this.state = CraftingStationAccess.access(menu).rsi$getCraftingStationState();
    }

    @Override
    public ItemStack getItem() {
        return state.result();
    }

    @Override
    public boolean hasItem() {
        return !getItem().isEmpty();
    }

    @Override
    public void set(ItemStack stack) {
        state.setSyncedResult(stack);
    }

    @Override
    public ItemStack remove(int amount) {
        ItemStack stack = getItem().copy();
        if (!stack.isEmpty()) stack.setCount(Math.min(amount, stack.getCount()));
        return stack;
    }

    @Override
    public boolean mayPlace(ItemStack stack) {
        return false;
    }

    @Override
    public boolean mayPickup(Player player) {
        return state.canTakeResult(player);
    }

    @Override
    public void onTake(Player player, ItemStack stack) {
        if (!player.level().isClientSide) state.takeResult(player);
    }

    @Override
    public boolean isActive() {
        return true;
    }
}
