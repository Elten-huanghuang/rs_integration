package com.huanghuang.rsintegration.util;

import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.storage.tracker.IStorageTracker;
import com.refinedmods.refinedstorage.api.util.Action;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** Inserts an item while recording the accepted delta before the real mutation. */
public final class TrackedNetworkInsertion {
    private TrackedNetworkInsertion() {}

    public static ItemStack insert(INetwork network, Player player, ItemStack input) {
        if (network == null) return input == null ? ItemStack.EMPTY : input.copy();
        return TrackedInsertionSequence.insert(input,
                (stack, phase) -> network.insertItem(stack, stack.getCount(),
                        phase == TrackedInsertionSequence.Phase.SIMULATE
                                ? Action.SIMULATE : Action.PERFORM),
                accepted -> {
                    IStorageTracker tracker = network.getItemStorageTracker();
                    if (tracker != null && player != null) tracker.changed(player, accepted);
                });
    }
}
