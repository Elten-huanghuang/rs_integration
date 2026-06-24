package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.batch.ModType;
import com.huanghuang.rsintegration.recipe.ModRecipeHandler;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.util.Diagnostics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.Level;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.*;

/**
 * Unified recipe index replacing the split {@code CraftingPlanManager} +
 * {@code ModRecipeIndex} dual-index pattern.
 *
 * <p>Single build pass, delegates extraction to {@link ModRecipeHandler}
 * when available, producing {@code Map<Item, List<Entry>>} for both vanilla
 * and mod recipe lookups.</p>
 */
public final class RecipeIndex {

    public record Entry(Recipe<?> recipe, ModType modType, ResourceLocation recipeTypeId) {}

    private static volatile Map<Item, List<Entry>> index;
    private static volatile RecipeManager source;

    private RecipeIndex() {}

    public static Map<Item, List<Entry>> get(Level level) {
        RecipeManager rm = level.getRecipeManager();
        Map<Item, List<Entry>> idx = index;
        if (idx != null && source == rm) return idx;

        synchronized (RecipeIndex.class) {
            idx = index;
            if (idx != null && source == rm) return idx;

            long diagTimer = Diagnostics.startTimer();
            long start = System.currentTimeMillis();
            idx = new HashMap<>();
            Set<ResourceLocation> seen = new HashSet<>();
            int skippedUnknown = 0, skippedEmptyResult = 0, skippedNoHandler = 0;

            for (Recipe<?> recipe : rm.getRecipes()) {
                if (!seen.add(recipe.getId())) continue;

                ModRecipeHandler handler = ModRecipeHandlers.handlerFor(recipe);
                ModType type;
                ItemStack result;

                if (handler != null) {
                    type = handler.modType();
                    result = handler.getResultItem(recipe, level.registryAccess());
                } else if (recipe instanceof CraftingRecipe cr
                        && ModType.classifyRecipe(recipe) == null) {
                    // Vanilla / CraftTweaker / datapack crafting recipe — no handler needed
                    type = ModType.GENERIC;
                    result = cr.getResultItem(level.registryAccess());
                } else {
                    skippedUnknown++;
                    continue;
                }

                if (result.isEmpty()) {
                    skippedEmptyResult++;
                    continue;
                }

                ResourceLocation typeId = recipe.getType() != null
                        ? ForgeRegistries.RECIPE_TYPES.getKey(recipe.getType())
                        : new ResourceLocation("minecraft:crafting");
                if (typeId == null) typeId = new ResourceLocation("minecraft:crafting");

                Entry entry = new Entry(recipe, type, typeId);
                idx.computeIfAbsent(result.getItem(), k -> new ArrayList<>()).add(entry);

                // Secondary outputs
                if (handler != null) {
                    for (ItemStack sec : handler.getSecondaryOutputs(recipe, level.registryAccess())) {
                        if (!sec.isEmpty()) {
                            idx.computeIfAbsent(sec.getItem(), k -> new ArrayList<>()).add(entry);
                        }
                    }
                }
            }

            index = idx;
            source = rm;

            long elapsed = System.currentTimeMillis() - start;
            Diagnostics.stopTimer("RecipeIndex.build", diagTimer);
            Diagnostics.record(Diagnostics.Category.INDEX_BUILD,
                    idx.size() + " items, " + seen.size() + " entries, " + elapsed + "ms"
                    + " (skipped: " + skippedUnknown + " unknown, " + skippedEmptyResult + " empty-result)");
            RSIntegrationMod.LOGGER.info("[RecipeIndex] built: {} items, {} entries in {}ms"
                            + " (skipped: {} unknown, {} empty-result)",
                    idx.size(), seen.size(), elapsed, skippedUnknown, skippedEmptyResult);
            return idx;
        }
    }

    /** Invalidate the cached index (e.g. on recipe reload). */
    public static void invalidate() {
        index = null;
        source = null;
    }
}
