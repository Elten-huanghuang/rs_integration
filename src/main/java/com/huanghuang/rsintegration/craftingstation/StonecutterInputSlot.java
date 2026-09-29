package com.huanghuang.rsintegration.craftingstation;

import com.refinedmods.refinedstorage.container.GridContainerMenu;

/** 切石机唯一输入槽。 */
public final class StonecutterInputSlot extends CraftingStationInputSlot {
    public StonecutterInputSlot(GridContainerMenu menu, int index, int x, int y) {
        super(CraftingStationAccess.access(menu).rsi$getCraftingStationState(), index, x, y,
                index == 0);
    }
}
