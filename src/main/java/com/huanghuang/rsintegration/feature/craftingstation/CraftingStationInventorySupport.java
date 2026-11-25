package com.huanghuang.rsintegration.feature.craftingstation;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoints;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.network.grid.IGrid;
import com.refinedmods.refinedstorage.api.network.grid.INetworkAwareGrid;
import com.refinedmods.refinedstorage.api.util.Action;
import com.refinedmods.refinedstorage.api.util.IComparer;
import com.refinedmods.refinedstorage.container.GridContainerMenu;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

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
        // 退料不是产物；由带玩家身份的写回统一刷新「最后修改时间」。
        var result = CraftStorageEndpoint.insertInternal(
                CraftStorageEndpoints.fromResolvedNetwork(network), player, stack, false);
        var remainder = result.remainder();
        if (remainder.isEmpty()) {
            RSIntegrationMod.LOGGER.error("[RSI-工作站] 退料写回结果不确定，不重复返还：{}（{}）",
                    stack, result.diagnosticCode());
            return;
        }
        ItemStack remaining = remainder.orElseThrow();
        if (!remaining.isEmpty()) {
            player.getInventory().placeItemBackInInventory(remaining);
        }
    }

    static void returnCraftingMatrix(
            GridContainerMenu menu, Player player) {
        if (player.level().isClientSide) return;
        IGrid grid = menu.getGrid();
        if (grid == null) return;
        CraftingContainer matrix = grid.getCraftingMatrix();
        if (matrix == null) return;

        List<ItemStack> removed = new ArrayList<>();
        for (int index = 0; index < matrix.getContainerSize(); index++) {
            ItemStack stack = matrix.removeItemNoUpdate(index);
            if (!stack.isEmpty()) removed.add(stack);
        }
        if (removed.isEmpty()) return;

        // 无线终端在矩阵变更时写入物品 NBT，归还前必须先保存空九格。
        grid.onCraftingMatrixChanged();
        for (ItemStack stack : removed) {
            returnToNetworkOrPlayer(menu, player, stack);
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
