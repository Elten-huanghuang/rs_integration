package com.huanghuang.rsintegration.recipe;

import com.huanghuang.rsintegration.batch.ModType;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Per-mod recipe handler — encapsulates ingredient extraction, result retrieval,
 * and secondary output discovery for a specific mod's recipe patterns.
 *
 * <p>Replaces the scattered probe methods in {@code CraftPacketUtils} and
 * {@code ModRecipeIndex} with a single extension point per mod.</p>
 */
public interface ModRecipeHandler {

    ModType modType();

    /** Quick check: does this handler claim responsibility for the given recipe? */
    boolean canHandle(Recipe<?> recipe);

    /** Extract the primary result item for display/indexing purposes. */
    ItemStack getResultItem(Recipe<?> recipe, RegistryAccess access);

    /** Extract ingredients with their required counts. Returns null if this handler cannot parse the recipe. */
    @Nullable
    List<IngredientSpec> getIngredients(Recipe<?> recipe);

    /** Secondary/byproduct outputs. Default empty — most mods don't have them. */
    default List<ItemStack> getSecondaryOutputs(Recipe<?> recipe, RegistryAccess access) {
        return List.of();
    }
}
