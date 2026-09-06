package com.huanghuang.rsintegration.recipe;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;

import javax.annotation.Nonnull;
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

    @Nonnull
    ModType modType();

    /** Quick check: does this handler claim responsibility for the given recipe? */
    boolean canHandle(@Nonnull Recipe<?> recipe);

    /** Whether {@link #canHandle} depends only on the recipe class. */
    default boolean cacheByRecipeClass() {
        return true;
    }

    /** Whether this handler's ingredient semantics override generic CraftTweaker probes. */
    default boolean preferHandlerIngredients() {
        return false;
    }

    /** Runtime requirements that are intentionally not consumed as ingredients. */
    default boolean isAvailableForPlanning(@Nonnull Recipe<?> recipe,
                                           @Nullable ServerPlayer player) {
        return true;
    }

    /** Extract the primary result item for display/indexing purposes. */
    @Nonnull
    ItemStack getResultItem(@Nonnull Recipe<?> recipe, @Nonnull RegistryAccess access);

    /** Extract ingredients with their required counts. Returns null if this handler cannot parse the recipe. */
    @Nullable
    List<IngredientSpec> getIngredients(Recipe<?> recipe);

    /**
     * Total quantity required for one input when planning a multi-execution order.
     * Most inputs use their demand role directly; machine handlers may override
     * this when one physical cycle covers several logical executions.
     */
    default int requiredIngredientCount(@Nonnull Recipe<?> recipe,
                                        @Nonnull IngredientSpec spec,
                                        int inputIndex, int executions) {
        return CraftPacketUtils.requiredCount(spec, executions);
    }

    /** Secondary/byproduct outputs. Default empty — most mods don't have them. */
    @Nonnull
    default List<ItemStack> getSecondaryOutputs(@Nonnull Recipe<?> recipe, @Nonnull RegistryAccess access) {
        return List.of();
    }

    /** Whether one execution is guaranteed to produce the item returned by {@link #getResultItem}. */
    default boolean hasDeterministicPrimaryOutput(@Nonnull Recipe<?> recipe) {
        return true;
    }

    /** Whether the primary output copies or otherwise derives NBT from runtime inputs. */
    default boolean hasRuntimeDependentPrimaryNbt(@Nonnull Recipe<?> recipe) {
        return false;
    }

    /**
     * Whether this recipe exposes a complete value-only input/output contract.
     * Such recipes may be projected once during catalog construction and
     * planned off-thread; machine interaction still remains on the server thread.
     * The default is intentionally conservative for handlers that implement
     * this interface directly. Prefix-based handlers inherit the stricter
     * default from {@link AbstractRecipeHandler}.
     */
    default boolean supportsBackgroundPlanning(@Nonnull Recipe<?> recipe) {
        if (!hasDeterministicPrimaryOutput(recipe) || hasRuntimeDependentPrimaryNbt(recipe)) {
            return false;
        }
        List<IngredientSpec> specs = getIngredients(recipe);
        return specs != null && !specs.isEmpty();
    }

    /**
     * Whether the JEI stack clicked by the player selects a concrete output
     * variant that cannot be recovered from the recipe's declared result alone.
     */
    default boolean useClickedPrimaryOutput(@Nonnull Recipe<?> recipe,
                                            @Nonnull ItemStack declared,
                                            @Nonnull ItemStack clicked) {
        return declared.hasTag() && clicked.hasTag();
    }

    /**
     * Whether the primary result should be exposed as a recursive item producer.
     * Some recipes use a JEI-only placeholder for an entity/fluid/world action;
     * those recipes may still be previewable, but the placeholder must not enter
     * the item recipe index.
     */
    default boolean indexPrimaryOutput(@Nonnull Recipe<?> recipe) {
        return true;
    }
}
