package com.huanghuang.rsintegration.voidupgrade;

import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.storage.cache.IStorageCache;
import com.refinedmods.refinedstorage.api.storage.cache.IStorageCacheListener;
import com.refinedmods.refinedstorage.api.util.Action;
import com.refinedmods.refinedstorage.api.util.IComparer;
import com.refinedmods.refinedstorage.api.util.StackListResult;
import net.minecraft.world.item.ItemStack;

import java.util.List;

final class VoidUpgradeCoordinator implements IStorageCacheListener<ItemStack> {
    private static final int MAX_TYPES_PER_TICK = 64;
    private static final int MAX_ITEMS_PER_TICK = 4096;

    private final INetwork network;
    private final IStorageCache<ItemStack> cache;
    private final VoidChangeQueue pending = new VoidChangeQueue();
    private CompiledVoidRules rules = CompiledVoidRules.compile(List.of());
    private boolean attached;
    private boolean extracting;

    VoidUpgradeCoordinator(INetwork network) {
        this.network = network;
        this.cache = network.getItemStorageCache();
    }

    void setRules(List<VoidUpgradeConfig> configs) {
        rules = CompiledVoidRules.compile(configs);
        if (rules.isEmpty()) {
            pending.clear();
            detach();
        } else if (!attached) {
            cache.addListener(this);
            attached = true;
        }
    }

    boolean isActive() {
        return !rules.isEmpty();
    }

    void tick() {
        if (!attached || !network.canRun() || pending.isEmpty()) return;
        int types = 0;
        int items = 0;
        while (types < MAX_TYPES_PER_TICK && items < MAX_ITEMS_PER_TICK && !pending.isEmpty()) {
            VoidChangeQueue.Entry change = pending.poll(MAX_ITEMS_PER_TICK - items);
            if (change == null) break;
            // Always compare NBT during extraction. Rules may ignore NBT, but the queued
            // delta represents this exact changed variant and must not consume another one.
            extracting = true;
            try {
                network.extractItem(change.stack(), change.amount(), IComparer.COMPARE_NBT, Action.PERFORM);
            } finally {
                extracting = false;
            }
            types++;
            items += change.amount();
        }
    }

    void detach() {
        if (attached) {
            cache.removeListener(this);
            attached = false;
        }
        pending.clear();
    }

    @Override
    public void onAttached() {
        // Attaching is deliberately not followed by a storage scan.
    }

    @Override
    public void onInvalidated() {
        // Cache rebuilds describe existing storage, not post-install insertions.
        pending.clear();
    }

    @Override
    public void onChanged(StackListResult<ItemStack> result) {
        record(result);
    }

    @Override
    public void onChangedBulk(List<StackListResult<ItemStack>> results) {
        for (StackListResult<ItemStack> result : results) record(result);
    }

    private void record(StackListResult<ItemStack> result) {
        if (!attached || result == null || result.getStack() == null) return;
        ItemStack stack = result.getStack();
        int delta = result.getChange();
        if (extracting && delta < 0) return;
        pending.record(stack, delta, delta > 0 && rules.matches(stack));
    }
}
