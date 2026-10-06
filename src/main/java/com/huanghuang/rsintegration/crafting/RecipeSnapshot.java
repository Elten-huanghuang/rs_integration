package com.huanghuang.rsintegration.crafting;

import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;

import java.util.Collection;
import java.util.Map;

/**
 * Immutable snapshot of recipe state captured on the server thread.
 * <p>
 * Does NOT hold references to Level, RecipeManager, or Recipe instances —
 * stores only identity/type information needed for background indexing.
 * The actual Recipe instances are retrieved from the validated RecipeManager
 * after the background build completes.
 */
public final class RecipeSnapshot {
    private final Map<ResourceLocation, RecipeIdentity> recipes;
    private final RecipeManager sourceManager;
    private final long revision;

    private RecipeSnapshot(Map<ResourceLocation, RecipeIdentity> recipes,
                          RecipeManager sourceManager,
                          long revision) {
        this.recipes = Map.copyOf(recipes);
        this.sourceManager = sourceManager;
        this.revision = revision;
    }

    /**
     * Captures recipe identities from the RecipeManager on the server thread.
     * Does NOT extract Recipe instances or hold any mutable world state.
     */
    public static RecipeSnapshot capture(RecipeManager manager, long revision) {
        // Must be called on server thread. Extract only identity data.
        Map<ResourceLocation, RecipeIdentity> recipeIdentities = new java.util.HashMap<>();
        for (Map.Entry<net.minecraft.world.item.crafting.RecipeType<?>, Map<ResourceLocation, Recipe<?>>> entry
                : manager.recipes.entrySet()) {
            ResourceLocation typeId = net.minecraftforge.registries.ForgeRegistries.RECIPE_TYPES.getKey(entry.getKey());
            for (Map.Entry<ResourceLocation, Recipe<?>> recipeEntry : entry.getValue().entrySet()) {
                recipeIdentities.put(recipeEntry.getKey(), new RecipeIdentity(recipeEntry.getKey(), typeId));
            }
        }
        return new RecipeSnapshot(recipeIdentities, manager, revision);
    }

    public Collection<RecipeIdentity> recipes() {
        return recipes.values();
    }

    public RecipeManager sourceManager() {
        return sourceManager;
    }

    public long revision() {
        return revision;
    }

    /**
     * Identity tuple for a recipe: ID + type. Does NOT hold Recipe instance.
     * The background thread processes only these identities; the server thread
     * retrieves actual Recipe instances after validating the RecipeManager.
     */
    public record RecipeIdentity(ResourceLocation id, ResourceLocation typeId) {}
}
