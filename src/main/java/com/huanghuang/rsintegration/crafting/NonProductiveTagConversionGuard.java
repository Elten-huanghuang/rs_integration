package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.common.crafting.StrictNBTIngredient;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Removes conversions that cannot increase the quantity accepted by a broad tag demand. */
final class NonProductiveTagConversionGuard {
    private NonProductiveTagConversionGuard() {}

    static boolean shouldSkip(Ingredient demand, CraftingRecipe recipe, ItemStack output) {
        if (demand instanceof StrictNBTIngredient || output.isEmpty() || output.hasTag()) return false;
        Set<Item> accepted = acceptedItems(demand);
        if (accepted.size() < 2 || !accepted.contains(output.getItem())) return false;

        List<IngredientSpec> specs = CraftPacketUtils.extractCraftingIngredientSpecs(recipe);
        if (specs == null || specs.isEmpty()) return false;
        long familyInputs = 0L;
        for (IngredientSpec spec : specs) {
            if (spec.isEmpty() || spec.role() != DemandRole.CONSUMED
                    || !isSubsetOfFamily(spec.ingredient(), accepted)) continue;
            familyInputs += spec.count();
        }
        if (familyInputs == 0L) return false;

        long returnedFamilyItems = 0L;
        for (ItemStack remainder : CraftPacketUtils.getRecipeRemainders(recipe)) {
            if (!remainder.isEmpty() && !remainder.hasTag()
                    && accepted.contains(remainder.getItem())) {
                returnedFamilyItems += remainder.getCount();
            }
        }
        long netFamilyInputs = Math.max(0L, familyInputs - returnedFamilyItems);
        return netFamilyInputs >= output.getCount();
    }

    private static Set<Item> acceptedItems(Ingredient ingredient) {
        Set<Item> accepted = new HashSet<>();
        for (ItemStack stack : ingredient.getItems()) {
            if (stack.isEmpty() || stack.hasTag()) return Set.of();
            accepted.add(stack.getItem());
        }
        return accepted;
    }

    private static boolean isSubsetOfFamily(Ingredient ingredient, Set<Item> accepted) {
        boolean found = false;
        for (ItemStack stack : ingredient.getItems()) {
            if (stack.isEmpty() || stack.hasTag() || !accepted.contains(stack.getItem())) {
                return false;
            }
            found = true;
        }
        return found;
    }
}
