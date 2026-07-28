package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.CraftPlanningRevision;
import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.RecipeIndex;
import com.huanghuang.rsintegration.command.PerformanceMonitor;
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
import net.minecraftforge.common.crafting.StrictNBTIngredient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reads live recipe objects on the server thread and projects them into immutable values. */
public final class ImmutableRecipeGraphProjector {
    private static volatile CachedProjection cachedProjection;

    private ImmutableRecipeGraphProjector() {}

    public static ImmutableRecipeGraph capture(Level level) {
        PlanningThreadContext.requireMainThread("recipe graph projection");
        RecipeManager source = level.getRecipeManager();
        long revision = CraftPlanningRevision.current();
        CachedProjection cached = cachedProjection;
        if (cached != null && cached.matches(source, revision)) {
            PerformanceMonitor.recordRecipeGraphProjection(true, 0L);
            return cached.graph();
        }

        synchronized (ImmutableRecipeGraphProjector.class) {
            cached = cachedProjection;
            if (cached != null && cached.matches(source, revision)) {
                PerformanceMonitor.recordRecipeGraphProjection(true, 0L);
                return cached.graph();
            }
            long started = System.nanoTime();
            ImmutableRecipeGraph graph = project(level);
            cachedProjection = new CachedProjection(source, revision, graph);
            PerformanceMonitor.recordRecipeGraphProjection(false, System.nanoTime() - started);
            return graph;
        }
    }

    private static ImmutableRecipeGraph project(Level level) {
        Map<MaterialRef, List<RecipeNode>> projected = new HashMap<>();
        Set<ResourceLocation> seen = new HashSet<>();
        for (List<RecipeIndex.Entry> entries : RecipeIndex.get(level).values()) {
            for (RecipeIndex.Entry entry : entries) {
                if (entry.modType() != ModType.GENERIC
                        || !(entry.recipe() instanceof CraftingRecipe recipe)
                        || !seen.add(recipe.getId())) continue;
                ItemStack output = recipe.getResultItem(level.registryAccess());
                if (output.isEmpty()) continue;
                List<IngredientSpec> specs = CraftPacketUtils.extractIngredientSpecs(recipe);
                if (specs == null || specs.isEmpty()) {
                    specs = CraftPacketUtils.extractCraftingIngredientSpecs(recipe);
                }
                List<IngredientRef> inputs = new ArrayList<>();
                boolean valid = true;
                for (IngredientSpec spec : specs) {
                    if (spec.isEmpty()) continue;
                    IngredientRef input = projectIngredient(spec);
                    if (input == null) { valid = false; break; }
                    inputs.add(input);
                }
                if (!valid) continue;
                MaterialRef outputRef = material(output, output.hasTag());
                RecipeNode node = new RecipeNode(recipe.getId(), outputRef,
                        Math.max(1, output.getCount()), inputs);
                projected.computeIfAbsent(outputRef, ignored -> new ArrayList<>()).add(node);
            }
        }
        return new ImmutableRecipeGraph(projected);
    }

    public static void clearCache() {
        cachedProjection = null;
    }

    public static Map<MaterialRef, Integer> projectAvailability(Map<StackKey, Integer> available) {
        Map<MaterialRef, Integer> projected = new HashMap<>();
        for (Map.Entry<StackKey, Integer> entry : available.entrySet()) {
            PlanningThreadContext.throwIfCancelled();
            ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(entry.getKey().item());
            if (itemId == null || entry.getValue() <= 0) continue;
            MaterialRef plain = new MaterialRef(itemId, "");
            projected.merge(plain, entry.getValue(), Integer::sum);
            if (entry.getKey().tag() != null && !entry.getKey().tag().isBlank()) {
                projected.merge(new MaterialRef(itemId, entry.getKey().tag()), entry.getValue(), Integer::sum);
            }
        }
        return Map.copyOf(projected);
    }

    public static IngredientRef projectIngredient(IngredientSpec spec) {
        Ingredient ingredient = spec.ingredient();
        boolean strictNbt = ingredient instanceof StrictNBTIngredient || allCandidatesHaveNbt(ingredient);
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
        return new MaterialRef(id, includeNbt && stack.getTag() != null ? stack.getTag().toString() : "");
    }

    private static boolean allCandidatesHaveNbt(Ingredient ingredient) {
        ItemStack[] candidates = ingredient.getItems();
        if (candidates.length == 0) return false;
        for (ItemStack candidate : candidates) {
            if (candidate.isEmpty() || !candidate.hasTag()) return false;
        }
        return true;
    }

    private record CachedProjection(RecipeManager source, long revision,
                                    ImmutableRecipeGraph graph) {
        private boolean matches(RecipeManager currentSource, long currentRevision) {
            return source == currentSource && revision == currentRevision;
        }
    }
}
