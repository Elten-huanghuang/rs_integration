package com.huanghuang.rsintegration.mods.majruszsaccessories;

import com.majruszsaccessories.common.AccessoryHolder;
import com.majruszsaccessories.items.AccessoryItem;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandler;
import net.p3pp3rf1y.sophisticatedcore.upgrades.FilterLogic;

import java.util.*;

/**
 * Auto-combines MAJ accessories inside a Sophisticated Backpack's
 * Compacting Upgrade filter. Algorithm: pick the max-efficiency
 * accessory, combine with any other of the same type, repeat until
 * all accessories of that type reach 100% efficiency.
 * <p>
 * Mimics MAJ's {@code CombineAccessoriesRecipe}: for each pair
 * (best + other), the new bonus is {@code maxBonus + 0.07f},
 * clamped to {@code [0, 1]}.
 */
public final class MajAccessoryCompressor {

    private static final float COMBINE_GAIN = 0.07f;
    private static final float MAX_EFFICIENCY = 1.0f;
    private static final int MAX_COMBINES_PER_CALL = 8;

    private MajAccessoryCompressor() {}

    /**
     * Scan the backpack inventory for MAJ accessories matching the
     * compacting filter, group by type, and iteratively combine them
     * until each group is fully compressed (all 100%) or only one remains.
     */
    public static void compress(IItemHandler inventory, FilterLogic filter) {
        Map<AccessoryItem, List<SlotEntry>> groups = new LinkedHashMap<>();

        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (stack.isEmpty()) continue;
            if (!(stack.getItem() instanceof AccessoryItem item)) continue;
            if (!filter.matchesFilter(stack)) continue;

            AccessoryHolder holder = AccessoryHolder.getOrCreate(stack);
            if (holder.hasMaxBonus()) continue;

            groups.computeIfAbsent(item, k -> new ArrayList<>())
                    .add(new SlotEntry(slot, holder));
        }

        int[] remaining = {MAX_COMBINES_PER_CALL};
        for (List<SlotEntry> entries : groups.values()) {
            if (remaining[0] <= 0) break;
            compressGroup(inventory, entries, remaining);
        }
    }

    private static void compressGroup(IItemHandler inventory, List<SlotEntry> entries, int[] remaining) {
        while (entries.size() >= 2 && remaining[0] > 0) {
            // Linear scan for max-bonus entry (avoids O(n log n) sort per iteration)
            SlotEntry best = entries.get(0);
            int bestIdx = 0;
            for (int i = 1; i < entries.size(); i++) {
                if (entries.get(i).holder.getBonus() > best.holder.getBonus()) {
                    best = entries.get(i);
                    bestIdx = i;
                }
            }
            if (best.holder.hasMaxBonus()) break;

            // Pick any other entry (not the best one)
            int otherIdx = (bestIdx == 0) ? 1 : 0;
            SlotEntry other = entries.get(otherIdx);

            float newBonus = Math.min(best.holder.getBonus() + COMBINE_GAIN, MAX_EFFICIENCY);

            // Extract inputs
            ItemStack extractedBest = inventory.extractItem(best.slot, 1, false);
            if (extractedBest.isEmpty()) {
                entries.remove(best);
                continue;
            }
            ItemStack extractedOther = inventory.extractItem(other.slot, 1, false);
            if (extractedOther.isEmpty()) {
                inventory.insertItem(best.slot, extractedBest, false);
                entries.remove(other);
                continue;
            }

            // Create combined result
            AccessoryHolder newHolder = AccessoryHolder.create(best.holder.getItem());
            newHolder.setBonus(newBonus);
            ItemStack result = newHolder.getItemStack();

            // Insert result — prefer best's slot, then other's, then any — recording
            // the exact (slot, count) placements so a rollback undoes only what we
            // inserted, never a pre-existing identical accessory elsewhere.
            List<int[]> placements = new ArrayList<>();
            ItemStack leftover = insertTracked(inventory, best.slot, result, placements);
            if (!leftover.isEmpty()) {
                leftover = insertTracked(inventory, other.slot, leftover, placements);
            }
            if (!leftover.isEmpty()) {
                for (int i = 0; i < inventory.getSlots() && !leftover.isEmpty(); i++) {
                    leftover = insertTracked(inventory, i, leftover, placements);
                }
            }
            if (!leftover.isEmpty()) {
                // Rollback: extract exactly the placements we made, then restore inputs.
                for (int[] p : placements) {
                    inventory.extractItem(p[0], p[1], false);
                }
                inventory.insertItem(best.slot, extractedBest, false);
                inventory.insertItem(other.slot, extractedOther, false);
                break;
            }

            // Update entries: other is consumed, best now holds the result
            remaining[0]--;
            entries.remove(otherIdx);
            if (otherIdx < bestIdx) bestIdx--;
            ItemStack newStack = inventory.getStackInSlot(best.slot);
            if (!newStack.isEmpty() && newStack.getItem() instanceof AccessoryItem) {
                entries.set(bestIdx, new SlotEntry(best.slot, AccessoryHolder.getOrCreate(newStack)));
            } else {
                entries.remove(bestIdx);
            }
        }
    }

    /**
     * Insert {@code stack} into {@code slot}, recording the placed count into
     * {@code placements} (as {@code [slot, count]}) so it can be undone precisely.
     * Returns the leftover that did not fit.
     */
    private static ItemStack insertTracked(IItemHandler inventory, int slot,
                                           ItemStack stack, List<int[]> placements) {
        int before = stack.getCount();
        ItemStack leftover = inventory.insertItem(slot, stack, false);
        int placed = before - leftover.getCount();
        if (placed > 0) placements.add(new int[]{slot, placed});
        return leftover;
    }

    private record SlotEntry(int slot, AccessoryHolder holder) {}
}
