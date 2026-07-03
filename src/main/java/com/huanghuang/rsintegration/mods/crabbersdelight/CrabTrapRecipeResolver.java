package com.huanghuang.rsintegration.mods.crabbersdelight;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;

import javax.annotation.Nullable;

/**
 * Resolves CrabbersDelight crab trap loot recipes from synthetic JEI IDs.
 * The JEI wrapper has no getId(), so the client sends a synthetic ID:
 * {@code crabbersdelight:crab_trap_loot/namespace/path}.
 */
public final class CrabTrapRecipeResolver {
    private CrabTrapRecipeResolver() {}

    @Nullable
    public static Recipe<?> resolveRecipe(ServerLevel level, ResourceLocation recipeId) {
        if (!"crabbersdelight".equals(recipeId.getNamespace())) return null;
        String path = recipeId.getPath();
        if (!path.startsWith("crab_trap_loot/")) return null;
        // Path: crab_trap_loot/<itemNamespace>/<itemPath>
        String rest = path.substring("crab_trap_loot/".length());
        int slash = rest.indexOf('/');
        if (slash <= 0) return null;
        String itemNs = rest.substring(0, slash);
        String itemPath = rest.substring(slash + 1);
        ResourceLocation itemId = new ResourceLocation(itemNs, itemPath);

        for (Recipe<?> r : level.getRecipeManager().getRecipes()) {
            if (!"crabbersdelight".equals(r.getId().getNamespace())) continue;
            if (!r.getIngredients().isEmpty()) {
                for (ItemStack match : r.getIngredients().get(0).getItems()) {
                    ResourceLocation key = BuiltInRegistries.ITEM.getKey(match.getItem());
                    if (itemId.equals(key)) return r;
                }
            }
        }
        return null;
    }
}
