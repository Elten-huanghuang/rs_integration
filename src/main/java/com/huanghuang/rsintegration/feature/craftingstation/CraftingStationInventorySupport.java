package com.huanghuang.rsintegration.feature.craftingstation;

import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.network.grid.INetworkAwareGrid;
import com.refinedmods.refinedstorage.api.util.Action;
import com.refinedmods.refinedstorage.api.util.IComparer;
import com.refinedmods.refinedstorage.container.GridContainerMenu;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;

/** 内嵌工作站共享的网络/背包物品转移和九格矩阵清理。 */
final class CraftingStationInventorySupport {
    private CraftingStationInventorySupport() {}

    static ItemStack extract(
            GridContainerMenu menu, Player player,
            ItemStack prototype, int count) {
        INetwork network = network(menu);
        ItemStack result = network == null ? ItemStack.EMPTY
                : network.extractItem(
                        prototype, count, IComparer.COMPARE_NBT,
                        Action.PERFORM);
        int remaining = count - result.getCount();
        if (remaining <= 0) return result;
        ItemStack fromPlayer = extractFromPlayer(
                player, prototype, remaining);
        if (result.isEmpty()) return fromPlayer;
        if (!fromPlayer.isEmpty()) result.grow(fromPlayer.getCount());
        return result;
    }

    static void returnToNetworkOrPlayer(
            GridContainerMenu menu, Player player, ItemStack stack) {
        INetwork network = network(menu);
        if (network == null) {
            player.getInventory().placeItemBackInInventory(stack);
            return;
        }
        ItemStack remaining = network.insertItem(
                stack, stack.getCount(), Action.PERFORM);
        if (!remaining.isEmpty()) {
            player.getInventory().placeItemBackInInventory(remaining);
        }
    }

    static void returnCraftingMatrix(
            GridContainerMenu menu, Player player) {
        if (player.level().isClientSide || menu.getGrid() == null
                || menu.getGrid().getCraftingMatrix() == null) return;
        CraftingContainer matrix = menu.getGrid().getCraftingMatrix();
        for (int index = 0; index < matrix.getContainerSize(); index++) {
            ItemStack stack = matrix.removeItemNoUpdate(index);
            if (!stack.isEmpty()) {
                returnToNetworkOrPlayer(menu, player, stack);
            }
        }
    }

    static void clearCraftingMatrix(GridContainerMenu menu) {
        if (menu.getGrid() == null) return;
        clearDisplayedMatrix(menu.getGrid().getCraftingMatrix());
    }

    static void clearDisplayedMatrix(@Nullable CraftingContainer matrix) {
        if (matrix == null) return;
        // 保持九格容器本身，只移除物品，避免客户端继续显示旧快照。
        for (int index = 0; index < matrix.getContainerSize(); index++) {
            matrix.removeItemNoUpdate(index);
        }
    }

    private static ItemStack extractFromPlayer(
            Player player, ItemStack prototype, int count) {
        ItemStack result = ItemStack.EMPTY;
        for (ItemStack current : player.getInventory().items) {
            if (count <= 0
                    || !ItemStack.isSameItemSameTags(current, prototype)) continue;
            ItemStack part = current.split(Math.min(count, current.getCount()));
            if (result.isEmpty()) result = part;
            else result.grow(part.getCount());
            count -= part.getCount();
        }
        for (ItemStack current : player.getInventory().offhand) {
            if (count <= 0
                    || !ItemStack.isSameItemSameTags(current, prototype)) continue;
            ItemStack part = current.split(Math.min(count, current.getCount()));
            if (result.isEmpty()) result = part;
            else result.grow(part.getCount());
            count -= part.getCount();
        }
        return result;
    }

    @Nullable
    private static INetwork network(GridContainerMenu menu) {
        return menu.getGrid() instanceof INetworkAwareGrid aware
                ? aware.getNetwork() : null;
    }
}
