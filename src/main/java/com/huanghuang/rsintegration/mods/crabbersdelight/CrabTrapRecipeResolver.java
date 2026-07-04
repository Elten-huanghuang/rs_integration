package com.huanghuang.rsintegration.mods.crabbersdelight;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;

import javax.annotation.Nullable;

/**
 * Resolves CrabbersDelight crab trap loot entries from synthetic JEI IDs.
 * The JEI wrapper has no getId(), so the client sends a synthetic ID:
 * {@code crabbersdelight:crab_trap_loot/<namespace>/<path>}.
 * <p>
 * Crab trap bait→loot mappings use loot tables, not standard recipes,
 * so this resolver creates a {@link CrabTrapLootWrapper} that bridges
 * the gap for the batch crafting system.
 */
public final class CrabTrapRecipeResolver {
    private CrabTrapRecipeResolver() {}

    @Nullable
    public static Recipe<?> resolveRecipe(ServerLevel level, ResourceLocation recipeId) {
        if (!"crabbersdelight".equals(recipeId.getNamespace())) return null;
        String path = recipeId.getPath();
        if (!path.startsWith("crab_trap_loot/")) return null;

        String rest = path.substring("crab_trap_loot/".length());
        int slash = rest.indexOf('/');
        if (slash <= 0) return null;
        String itemNs = rest.substring(0, slash);
        String itemPath = rest.substring(slash + 1);
        ResourceLocation itemId = new ResourceLocation(itemNs, itemPath);

        // Resolve the bait item from the registry
        var item = BuiltInRegistries.ITEM.get(itemId);
        if (item == null) return null;
        ItemStack bait = new ItemStack(item);

        // Return a virtual wrapper — crab trap uses loot tables, not recipes
        return new CrabTrapLootWrapper(recipeId, bait);
    }
}
