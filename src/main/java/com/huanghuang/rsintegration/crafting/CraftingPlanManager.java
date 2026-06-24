package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.batch.ModType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;

import java.util.*;

public final class CraftingPlanManager {

    private static volatile Map<Item, List<CraftingRecipe>> recipeIndex;
    private static volatile RecipeManager indexedRecipeManager;

    private CraftingPlanManager() {}

    public static Map<Item, List<CraftingRecipe>> getRecipeIndexForLevel(Level level) {
        RecipeManager rm = level.getRecipeManager();
        Map<Item, List<CraftingRecipe>> idx = recipeIndex;

        if (idx != null && indexedRecipeManager == rm) {
            return idx;
        }

        synchronized (CraftingPlanManager.class) {
            idx = recipeIndex;
            if (idx != null && indexedRecipeManager == rm) {
                return idx;
            }

            long start = System.currentTimeMillis();
            idx = new HashMap<>();

            List<CraftingRecipe> allCrafting = new ArrayList<>(
                    rm.getAllRecipesFor(RecipeType.CRAFTING));

            for (CraftingRecipe recipe : allCrafting) {
                // Double-check: exclude registered mod-type recipes.
                if (ModType.classifyRecipe(recipe) != null) continue;
                ItemStack result = recipe.getResultItem(level.registryAccess());
                if (result.isEmpty()) continue;
                idx.computeIfAbsent(result.getItem(), k -> new ArrayList<>()).add(recipe);
            }

            recipeIndex = idx;
            indexedRecipeManager = rm;

            long elapsed = System.currentTimeMillis() - start;
            RSIntegrationMod.LOGGER.debug("[CraftingPlan] built recipe index: {} recipes, {} items in {}ms",
                    allCrafting.size(), idx.size(), elapsed);

            return idx;
        }
    }
}
