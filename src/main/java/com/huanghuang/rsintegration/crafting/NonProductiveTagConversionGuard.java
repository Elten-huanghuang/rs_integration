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

        return shouldSkipForFamily(accepted, recipe, output);
    }

    /**
     * Returns true when this recipe consumes at least as many members of the
     * supplied family as it produces. The family is supplied by the caller so
     * the guard still works after a tag has been narrowed to one concrete item
     * during recursive planning.
     */
    static boolean shouldSkipForFamily(Set<Item> family, CraftingRecipe recipe, ItemStack output) {
        if (family == null || family.size() < 2 || output.isEmpty() || output.hasTag()
                || !family.contains(output.getItem())) return false;

        List<IngredientSpec> specs = CraftPacketUtils.extractCraftingIngredientSpecs(recipe);
        if (specs == null || specs.isEmpty()) return false;
        long familyInputs = 0L;
        for (IngredientSpec spec : specs) {
            if (spec.isEmpty() || spec.role() != DemandRole.CONSUMED
                    || !isSubsetOfFamily(spec.ingredient(), family)) continue;
            familyInputs += spec.count();
        }
        if (familyInputs == 0L) return false;

        long returnedFamilyItems = 0L;
        for (ItemStack remainder : CraftPacketUtils.getRecipeRemainders(recipe)) {
            if (!remainder.isEmpty() && !remainder.hasTag()
                    && family.contains(remainder.getItem())) {
                returnedFamilyItems += remainder.getCount();
            }
        }
        long netFamilyInputs = Math.max(0L, familyInputs - returnedFamilyItems);
        return netFamilyInputs >= output.getCount();
    }

    /**
     * Finds broad material families explicitly present in a recipe's inputs.
     * These families are used as branch state when the recipe is selected, so
     * a later concrete lookup cannot re-enter the same conversion cycle.
     */
    static Set<Set<Item>> conversionFamilies(CraftingRecipe recipe, ItemStack output) {
        if (output.isEmpty() || output.hasTag()) return Set.of();
        List<IngredientSpec> specs = CraftPacketUtils.extractCraftingIngredientSpecs(recipe);
        if (specs == null || specs.isEmpty()) return Set.of();

        Set<Set<Item>> result = new HashSet<>();
        for (IngredientSpec spec : specs) {
            if (spec.isEmpty() || spec.role() != DemandRole.CONSUMED) continue;
            Set<Item> family = acceptedItems(spec.ingredient());
            if (family.size() < 2 || !family.contains(output.getItem())) continue;
            if (shouldSkipForFamily(family, recipe, output)) {
                result.add(Set.copyOf(family));
            }
        }
        return Set.copyOf(result);
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
