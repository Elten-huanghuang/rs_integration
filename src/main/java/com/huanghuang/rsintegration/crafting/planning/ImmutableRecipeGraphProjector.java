package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.CraftPlanningRevision;
import com.huanghuang.rsintegration.crafting.IngredientMatcher;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.RecipeIndex;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.command.PerformanceMonitor;
import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.IngredientRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.MaterialRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.RecipeNode;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable pure-planning projection published with the matching recipe index generation. */
public final class ImmutableRecipeGraphProjector {
    private static volatile CachedProjection cachedProjection;

    private ImmutableRecipeGraphProjector() {}

    public static boolean isReady(Level level) {
        CachedProjection ready = cachedProjection;
        return ready != null && ready.matches(
                level.getRecipeManager(), CraftPlanningRevision.current());
    }

    public static ImmutableRecipeGraph capture(Level level) {
        PlanningThreadContext.requireMainThread("recipe graph projection");
        CachedProjection ready = cachedProjection;
        if (ready != null && ready.matches(
                level.getRecipeManager(), CraftPlanningRevision.current())) {
            PerformanceMonitor.recordRecipeGraphProjection(true, 0L);
            return ready.graph();
        }

        // Compatibility fallback for callers that invoke capture before the normal
        // startup/reload lifecycle. Planning packets never reach this path because
        // their readiness gate requires the complete generation.
        RecipeIndex.warmUp(level);
        ready = cachedProjection;
        if (ready == null || !ready.matches(
                level.getRecipeManager(), CraftPlanningRevision.current())) {
            throw new IllegalStateException("Recipe graph generation is unavailable");
        }
        return ready.graph();
    }

    /** Publishes a graph captured during the same pass that built the recipe index. */
    public static synchronized void publishCompiled(RecipeManager source, long revision,
                                                     ImmutableRecipeGraph graph,
                                                     long buildNanos) {
        cachedProjection = new CachedProjection(source, revision, graph);
        PerformanceMonitor.recordRecipeGraphProjection(false, buildNanos);
    }

    /** Captures one ordinary crafting recipe without invoking generic reflective extraction. */
    @Nullable
    public static RecipeNode projectCraftingRecipe(CraftingRecipe recipe, ItemStack output) {
        return projectCraftingRecipe(recipe, output,
                CraftPacketUtils.extractCraftingIngredientSpecs(recipe));
    }

    @Nullable
    public static RecipeNode projectCraftingRecipe(CraftingRecipe recipe, ItemStack output,
                                                    List<IngredientSpec> specs) {
        if (output.isEmpty()) return null;
        List<IngredientRef> inputs = new ArrayList<>();
        for (IngredientSpec spec : specs) {
            if (spec.isEmpty()) continue;
            IngredientRef input = projectIngredient(spec);
            if (input == null) return null;
            inputs.add(input);
        }
        MaterialRef outputRef = material(output, output.hasTag());
        return new RecipeNode(recipe.getId(), outputRef,
                Math.max(1, output.getCount()), inputs);
    }

    public static synchronized void clearCache() {
        cachedProjection = null;
    }

    public static Map<MaterialRef, Integer> projectAvailability(Map<StackKey, Integer> available) {
        Map<MaterialRef, Integer> projected = new java.util.HashMap<>();
        for (Map.Entry<StackKey, Integer> entry : available.entrySet()) {
            PlanningThreadContext.throwIfCancelled();
            ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(entry.getKey().item());
            if (itemId == null || entry.getValue() <= 0) continue;
            MaterialRef plain = new MaterialRef(itemId, "");
            projected.merge(plain, entry.getValue(), Integer::sum);
            if (entry.getKey().tag() != null && !entry.getKey().tag().isBlank()) {
                projected.merge(new MaterialRef(itemId, entry.getKey().tag()),
                        entry.getValue(), Integer::sum);
            }
        }
        return Map.copyOf(projected);
    }

    public static IngredientRef projectIngredient(IngredientSpec spec) {
        // The pure graph has no role field, so projecting reusable/transformed inputs
        // would silently turn them into per-execution consumables.
        if (spec.role() != DemandRole.CONSUMED) return null;
        Ingredient ingredient = spec.ingredient();
        boolean strictNbt = IngredientMatcher.requiresNbt(ingredient);
        Set<MaterialRef> alternatives = new LinkedHashSet<>();
        for (ItemStack candidate : ingredient.getItems()) {
            if (!candidate.isEmpty()) alternatives.add(material(candidate, strictNbt));
        }
        return alternatives.isEmpty() ? null
                : new IngredientRef(List.copyOf(alternatives), spec.count());
    }

    public static MaterialRef material(ItemStack stack, boolean includeNbt) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (id == null) throw new IllegalArgumentException("Unregistered item in planning snapshot");
        return new MaterialRef(id,
                includeNbt && stack.getTag() != null ? stack.getTag().toString() : "");
    }

    private record CachedProjection(RecipeManager source, long revision,
                                    ImmutableRecipeGraph graph) {
        private boolean matches(RecipeManager currentSource, long currentRevision) {
            return source == currentSource && revision == currentRevision;
        }
    }
}
