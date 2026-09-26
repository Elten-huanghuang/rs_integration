package com.huanghuang.rsintegration.util;

import com.huanghuang.rsintegration.crafting.MaterialSources;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.storage.tracker.IStorageTracker;
import com.refinedmods.refinedstorage.api.util.Action;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import com.huanghuang.rsintegration.crafting.CraftStorageEndpoints;
import net.minecraft.server.level.ServerPlayer;

/** Inserts an item while recording the accepted delta before the real mutation. */
public final class TrackedNetworkInsertion {
    private TrackedNetworkInsertion() {}

    public static ItemStack insert(INetwork network, Player player, ItemStack input) {
        if (network == null) return input == null ? ItemStack.EMPTY : input.copy();
        if (player != null) {
            var result = CraftStorageEndpoints
                    .fromLegacyNetwork(network).insert(player, input, false);
            return result.remainder().orElse(ItemStack.EMPTY);
        }
        var endpoint = CraftStorageEndpoints
                .fromLegacyNetwork(network);
        return TrackedInsertionSequence.insert(input,
                (stack, phase) -> endpoint.insert(stack,
                        phase == TrackedInsertionSequence.Phase.SIMULATE).remainder()
                        .orElse(ItemStack.EMPTY),
                accepted -> {
                    IStorageTracker tracker = network.getItemStorageTracker();
                    if (tracker != null && player != null) tracker.changed(player, accepted);
                    if (player instanceof ServerPlayer serverPlayer) {
                        // A recursive chain may publish an intermediate product and
                        // consume it again in the same server tick. Do not let the
                        // per-player material snapshot hide that newly inserted stack.
                        MaterialSources.invalidateFor(serverPlayer);
                        serverPlayer.containerMenu.broadcastChanges();
                    }
                });
    }
}
