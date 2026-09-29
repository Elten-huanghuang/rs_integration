package com.huanghuang.rsintegration.craftingstation;

import com.refinedmods.refinedstorage.api.network.grid.IGrid;
import com.refinedmods.refinedstorage.container.GridContainerMenu;
import com.refinedmods.refinedstorage.container.slot.grid.ResultCraftingGridSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** RS 结果槽的兼容子类，点击结果时执行原版锻造台的三格消耗逻辑。 */
public final class SmithingResultSlot extends CraftingStationResultSlot {

    public SmithingResultSlot(GridContainerMenu menu, Player player, IGrid grid, int index, int x, int y) {
        super(menu, player, grid, index, x, y);
    }
}
