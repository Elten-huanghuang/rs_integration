package com.huanghuang.rsintegration.resonance.bridge;

import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.resonance.api.ResonanceStorageResolvers;
import com.huanghuang.rsintegration.resonance.api.ResonanceStorageView;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;

/** Backend-neutral inventory operations shared by RS and BD resonance disks. */
public final class ResonanceInventoryBridge {

    private ResonanceInventoryBridge() {}

    public static List<ResonanceStorageView> getViews(ServerPlayer player) {
        if (!RSIntegrationConfig.ENABLE_RS_PASSIVE_EFFECTS.get()) return List.of();
        return ResonanceStorageResolvers.resolveAll(player);
    }

    public static boolean hasItem(ServerPlayer player, Predicate<ItemStack> predicate) {
        for (ResonanceStorageView view : getViews(player)) {
            if (view.hasItem(predicate)) return true;
        }
        return false;
    }

    public static int countItems(ServerPlayer player, Predicate<ItemStack> predicate) {
        int count = 0;
        for (ResonanceStorageView view : getViews(player)) {
            count += view.countItems(predicate);
        }
        return count;
    }

    @Nullable
    public static ItemStack findFirst(ServerPlayer player, Predicate<ItemStack> predicate) {
        for (ResonanceStorageView view : getViews(player)) {
            for (ResonanceStorageView.StoredStack stored : view.storedStacks()) {
                if (predicate.test(stored.stack())) return stored.stack().copy();
            }
        }
        return null;
    }

    public static ItemStack extract(ServerPlayer player, Predicate<ItemStack> predicate,
                                    int amount) {
        return extract(player, predicate, amount, false);
    }

    public static ItemStack extract(ServerPlayer player, Predicate<ItemStack> predicate,
                                    int amount, boolean simulate) {
        if (amount <= 0) return ItemStack.EMPTY;
        int remaining = amount;
        ItemStack result = ItemStack.EMPTY;
        List<Extracted> extractedStacks = new ArrayList<>();
        for (ResonanceStorageView view : getViews(player)) {
            for (ResonanceStorageView.StoredStack stored : view.storedStacks()) {
                ItemStack stack = stored.stack();
                if (!predicate.test(stack)) continue;
                int take = Math.min(remaining, stack.getCount());
                ItemStack extracted = view.extractExactView(stored.slot(), stack, take, simulate);
                if (extracted.isEmpty()) continue;
                if (result.isEmpty()) {
                    result = extracted.copy();
                } else if (ItemStack.isSameItemSameTags(result, extracted)) {
                    result.grow(extracted.getCount());
                } else {
                    if (!simulate) {
                        view.insertView(stored.slot(), extracted, extracted.getCount(), false);
                    }
                    continue;
                }
                extractedStacks.add(new Extracted(view, stored.slot(), extracted.copy()));
                remaining -= extracted.getCount();
                if (remaining <= 0) return result;
            }
        }
        if (!simulate) {
            for (Extracted extracted : extractedStacks) {
                extracted.view().insertView(extracted.slot(), extracted.stack(),
                        extracted.stack().getCount(), false);
            }
        }
        return ItemStack.EMPTY;
    }

    public static ItemStack insert(ServerPlayer player, ItemStack stack) {
        if (stack.isEmpty()) return ItemStack.EMPTY;
        ItemStack remaining = stack.copy();
        for (ResonanceStorageView view : getViews(player)) {
            if (remaining.isEmpty()) break;
            remaining = view.insertUnassigned(remaining, remaining.getCount(), false);
        }
        return remaining;
    }

    /** Extracts from the first matching variant, preserving legacy partial behavior. */
    public static ItemStack extractFirst(ServerPlayer player, Predicate<ItemStack> predicate,
                                         int amount, boolean simulate) {
        if (amount <= 0) return ItemStack.EMPTY;
        for (ResonanceStorageView view : getViews(player)) {
            for (ResonanceStorageView.StoredStack stored : view.storedStacks()) {
                ItemStack stack = stored.stack();
                if (!predicate.test(stack)) continue;
                int take = Math.min(amount, stack.getCount());
                return view.extractExactView(stored.slot(), stack, take, simulate);
            }
        }
        return ItemStack.EMPTY;
    }

    public static List<ItemStack> getCombinedItems(ServerPlayer player) {
        List<ItemStack> result = new ArrayList<>();
        for (ResonanceStorageView view : getViews(player)) {
            for (ResonanceStorageView.StoredStack stored : view.storedStacks()) {
                if (!stored.stack().isEmpty()) result.add(stored.stack().copy());
            }
        }
        return result;
    }

    public static Collection<ItemStack> getItems(ServerPlayer player) {
        return List.copyOf(getCombinedItems(player));
    }

    private record Extracted(ResonanceStorageView view, int slot, ItemStack stack) {}
}
