package com.huanghuang.rsintegration.mods.crockpot;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.ToIntFunction;

/** Deterministic fuel selection and inventory accounting for Crock Pot automation. */
public final class CrockPotFuelPolicy {

    private CrockPotFuelPolicy() {}

    /**
     * Select a fuel type from the current RS snapshot. Configured items win in
     * declaration order; safe fallback fuels are ranked by total burn coverage.
     */
    @Nullable
    public static ItemStack select(List<ItemStack> candidates,
                                   List<? extends String> priorityIds,
                                   ToIntFunction<ItemStack> burnTime) {
        List<ItemStack> available = new ArrayList<>();
        for (ItemStack stack : candidates) {
            if (!stack.isEmpty() && stack.getCount() > 0 && burnTime.applyAsInt(stack) > 0) {
                available.add(stack);
            }
        }
        available.sort(Comparator.comparing(CrockPotFuelPolicy::registryId));

        for (String id : priorityIds) {
            ResourceLocation key = ResourceLocation.tryParse(id);
            if (key == null) continue;
            Item item = ForgeRegistries.ITEMS.getValue(key);
            if (item == null || item == Items.AIR) continue;
            for (ItemStack stack : available) {
                if (stack.getItem() == item) return stack.copyWithCount(1);
            }
        }

        ItemStack best = ItemStack.EMPTY;
        long bestCoverage = -1;
        for (ItemStack stack : available) {
            if (!isSafeFallback(stack)) continue;
            long coverage = (long) stack.getCount() * burnTime.applyAsInt(stack);
            if (coverage > bestCoverage) {
                bestCoverage = coverage;
                best = stack;
            }
        }
        return best.isEmpty() ? null : best.copyWithCount(1);
    }

    public static int insertionRoom(ItemStack current, ItemStack candidate, int slotLimit) {
        if (candidate.isEmpty() || slotLimit <= 0) return 0;
        if (!current.isEmpty() && !ItemStack.isSameItemSameTags(current, candidate)) return 0;
        int limit = Math.min(slotLimit, candidate.getMaxStackSize());
        return Math.max(0, limit - current.getCount());
    }

    public static int refundableCount(ItemStack suppliedType, int suppliedCount, ItemStack current) {
        if (suppliedCount <= 0 || suppliedType.isEmpty() || current.isEmpty()) return 0;
        if (!ItemStack.isSameItemSameTags(suppliedType, current)) return 0;
        return Math.min(suppliedCount, current.getCount());
    }

    private static boolean isSafeFallback(ItemStack stack) {
        if (stack.isDamageableItem()) return false;
        if (!stack.getCraftingRemainingItem().isEmpty()) return false;
        return !stack.hasTag();
    }

    private static String registryId(ItemStack stack) {
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return id != null ? id.toString() : "";
    }
}
