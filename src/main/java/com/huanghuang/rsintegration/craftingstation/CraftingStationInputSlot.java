package com.huanghuang.rsintegration.craftingstation;

import com.refinedmods.refinedstorage.container.slot.grid.CraftingGridSlot;
import net.minecraft.world.item.ItemStack;

/** 将 RS 合成槽代理到当前工作站状态的通用输入槽。 */
public class CraftingStationInputSlot extends CraftingGridSlot {
    private final CraftingStationState state;
    private final int stationIndex;
    private final boolean active;

    public CraftingStationInputSlot(CraftingStationState state, int index, int x, int y,
                                    boolean active) {
        super(state.inputs(), active ? index : 0, x, y);
        this.state = state;
        this.stationIndex = index;
        this.active = active;
    }

    @Override
    public ItemStack getItem() {
        return active ? state.inputs().getItem(stationIndex) : ItemStack.EMPTY;
    }

    @Override
    public boolean hasItem() {
        return active && !getItem().isEmpty();
    }

    @Override
    public void set(ItemStack stack) {
        if (active) state.setInput(stationIndex, stack);
    }

    @Override
    public ItemStack remove(int amount) {
        if (!active) return ItemStack.EMPTY;
        ItemStack removed = state.inputs().removeItem(stationIndex, amount);
        if (!removed.isEmpty()) state.recompute();
        return removed;
    }

    @Override
    public boolean mayPlace(ItemStack stack) {
        return active && state.acceptsInput(stationIndex, stack);
    }

    @Override
    public void setChanged() {
        if (active) state.recompute();
    }

    @Override
    public boolean isActive() {
        return active;
    }

    public CraftingStationState stationState() {
        return state;
    }

    /** 返回该代理槽对应的 RS 原始合成槽编号。 */
    public int stationIndex() {
        return stationIndex;
    }
}
