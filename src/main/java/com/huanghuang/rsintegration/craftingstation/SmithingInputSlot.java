package com.huanghuang.rsintegration.craftingstation;

import com.refinedmods.refinedstorage.container.GridContainerMenu;
import com.refinedmods.refinedstorage.container.slot.grid.CraftingGridSlot;
import net.minecraft.world.item.ItemStack;

/** 保持 RS 槽位类型兼容，同时把前三格绑定到 RSI 锻造状态。 */
public final class SmithingInputSlot extends CraftingStationInputSlot {

    public SmithingInputSlot(GridContainerMenu menu, int index, int x, int y) {
        super(SmithingTerminalAccess.access(menu).rsi$getSmithingState(), index, x, y,
                index < 3);
    }
}
