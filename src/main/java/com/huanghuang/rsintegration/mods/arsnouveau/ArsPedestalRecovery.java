package com.huanghuang.rsintegration.mods.arsnouveau;

import com.huanghuang.rsintegration.util.PlayerUtils;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;
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
                       @Nullable CraftStorageEndpoint endpoint) {
        int recovered = 0;
        for (BlockPos position : positions) {
            BlockEntity pedestal = level.getBlockEntity(position);
            if (!(pedestal instanceof Container container)) continue;
            ItemStack stack = container.getItem(0).copy();
            if (stack.isEmpty()) continue;
            container.setItem(0, ItemStack.EMPTY);
            pedestal.setChanged();
            ItemStack remainder = endpoint == null ? stack
                    : endpoint.insert(player, stack, false)
                    .remainder().orElse(ItemStack.EMPTY);
            if (!remainder.isEmpty()) {
                PlayerUtils.safeGiveToStorage(player, remainder, endpoint);
            }
            recovered++;
        }
        return recovered;
    }
}
