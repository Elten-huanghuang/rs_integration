package com.huanghuang.rsintegration.mods.arsnouveau;

import com.huanghuang.rsintegration.util.PlayerUtils;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.util.Action;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

import javax.annotation.Nullable;
import java.util.List;

/** Recovers stale items from every pedestal scanned by an idle Ars machine. */
final class ArsPedestalRecovery {
    private ArsPedestalRecovery() {}

    static int recover(ServerLevel level, List<BlockPos> positions, ServerPlayer player,
                       @Nullable INetwork network) {
        int recovered = 0;
        for (BlockPos position : positions) {
            BlockEntity pedestal = level.getBlockEntity(position);
            if (!(pedestal instanceof Container container)) continue;
            ItemStack stack = container.getItem(0).copy();
            if (stack.isEmpty()) continue;

            // Remove first so a failed mutation can never duplicate the stack.
            container.setItem(0, ItemStack.EMPTY);
            pedestal.setChanged();

            ItemStack remainder = stack;
            if (network != null) {
                remainder = com.huanghuang.rsintegration.crafting.CraftStorageEndpoints
                        .insertLegacy(network, player, stack, false);
            }
            if (!remainder.isEmpty()) {
                PlayerUtils.safeGiveToPlayer(player, remainder, network);
            }
            recovered++;
        }
        return recovered;
    }
}
