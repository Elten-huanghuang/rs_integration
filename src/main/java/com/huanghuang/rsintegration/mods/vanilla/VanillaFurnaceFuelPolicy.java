package com.huanghuang.rsintegration.mods.vanilla;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Pure selection policy shared by automatic furnace crafting and GUI prefill. */
public final class VanillaFurnaceFuelPolicy {

    private VanillaFurnaceFuelPolicy() {}

    public record Selection(ItemStack fuel, int amount, boolean partial) {}

    /**
     * Select one safe fuel type. Configured fuels are tried in order; remaining
     * fuels are ordered by registry id so RS cache iteration cannot affect the result.
     */
    @Nullable
    public static Selection select(List<ItemStack> candidates,
                                   List<? extends String> priorityIds,
                                   RecipeType<?> recipeType,
                                   int requiredTicks) {
        return select(candidates, priorityIds, requiredTicks,
                stack -> ForgeHooks.getBurnTime(stack, recipeType));
    }

    public static Selection select(List<ItemStack> candidates,
                            List<? extends String> priorityIds,
                            int requiredTicks,
                            java.util.function.ToIntFunction<ItemStack> burnTime) {
        if (requiredTicks <= 0) return null;

        List<ItemStack> safe = new ArrayList<>();
        for (ItemStack stack : candidates) {
            if (!stack.isEmpty() && isSafeAutoFuel(stack, burnTime.applyAsInt(stack))) safe.add(stack);
        }
        safe.sort(Comparator.comparing(VanillaFurnaceFuelPolicy::registryId));

        Set<Item> tried = new HashSet<>();
        for (String id : priorityIds) {
            ResourceLocation key = ResourceLocation.tryParse(id);
            if (key == null) continue;
            Item item = ForgeRegistries.ITEMS.getValue(key);
            if (item == null || item == Items.AIR || !tried.add(item)) continue;
            Selection selection = fullSelection(findByItem(safe, item), requiredTicks, burnTime);
            if (selection != null) return selection;
        }

        for (ItemStack stack : safe) {
            if (tried.contains(stack.getItem())) continue;
            Selection selection = fullSelection(stack, requiredTicks, burnTime);
            if (selection != null) return selection;
        }

        ItemStack best = ItemStack.EMPTY;
        long bestCoverage = 0;
        for (ItemStack stack : safe) {
            int singleBurnTime = burnTime.applyAsInt(stack);
            long coverage = (long) stack.getCount() * singleBurnTime;
            if (coverage > bestCoverage) {
                bestCoverage = coverage;
                best = stack;
            }
        }
        return best.isEmpty() ? null : new Selection(best.copyWithCount(1), best.getCount(), true);
    }

    public static int requiredAmount(int requiredTicks, int singleBurnTime) {
        if (requiredTicks <= 0) return 0;
        if (singleBurnTime <= 0) return Integer.MAX_VALUE;
        return Math.max(1, (requiredTicks + singleBurnTime - 1) / singleBurnTime);
    }

    public static boolean isSafeAutoFuel(ItemStack stack, RecipeType<?> recipeType) {
        return isSafeAutoFuel(stack, ForgeHooks.getBurnTime(stack, recipeType));
    }

    private static boolean isSafeAutoFuel(ItemStack stack, int burnTime) {
        if (stack.isEmpty() || burnTime <= 0) return false;
        if (stack.isDamageableItem()) return false;
        if (!stack.getCraftingRemainingItem().isEmpty()) return false;
        return !stack.hasTag();
    }

    @Nullable
    private static Selection fullSelection(@Nullable ItemStack stack,
                                           int requiredTicks,
                                           java.util.function.ToIntFunction<ItemStack> burnTime) {
        if (stack == null || stack.isEmpty()) return null;
        int singleBurnTime = burnTime.applyAsInt(stack);
        int amount = requiredAmount(requiredTicks, singleBurnTime);
        if (amount <= 0 || amount > stack.getCount() || amount > stack.getMaxStackSize()) return null;
        return new Selection(stack.copyWithCount(1), amount, false);
    }

    @Nullable
    private static ItemStack findByItem(List<ItemStack> candidates, Item item) {
        for (ItemStack stack : candidates) {
            if (stack.getItem() == item) return stack;
        }
        return null;
    }

    private static String registryId(ItemStack stack) {
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return id != null ? id.toString() : "";
    }
}
