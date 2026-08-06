package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.CraftPlanningRevision;
import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.IngredientMatcher;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reads live recipe objects on the server thread and projects them into immutable values. */
public final class ImmutableRecipeGraphProjector {
    private static volatile CachedProjection cachedProjection;
    private static ProjectionBuildState warmUpState;

    private ImmutableRecipeGraphProjector() {}

    public static boolean isReady(Level level) {
        CachedProjection ready = cachedProjection;
        return ready != null && ready.matches(
                level.getRecipeManager(), CraftPlanningRevision.current());
    }

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
            if (warmUpState == null || !warmUpState.matches(source, revision)) {
                warmUpState = new ProjectionBuildState(source, revision, RecipeIndex.get(level));
            }
            while (!warmUpState.advance(level, Long.MAX_VALUE)) {
                // Complete any remaining scheduled work for this immediate request.
            }
            return publish(warmUpState);
        }
    }

    public static synchronized void scheduleWarmUp(Level level) {
        RecipeManager source = level.getRecipeManager();
        long revision = CraftPlanningRevision.current();
        if (cachedProjection != null && cachedProjection.matches(source, revision)) return;
        if (warmUpState == null || !warmUpState.matches(source, revision)) {
            warmUpState = new ProjectionBuildState(source, revision, RecipeIndex.get(level));
        }
    }

    public static synchronized void tickWarmUp(Level level, long budgetNanos) {
        RecipeManager source = level.getRecipeManager();
        long revision = CraftPlanningRevision.current();
        if (cachedProjection != null && cachedProjection.matches(source, revision)) return;
        if (warmUpState == null) {
            if (!RecipeIndex.isReady(level)) return;
            warmUpState = new ProjectionBuildState(source, revision, RecipeIndex.get(level));
        }
        if (!warmUpState.matches(source, revision)) {
            if (!RecipeIndex.isReady(level)) {
                warmUpState = null;
                return;
            }
            warmUpState = new ProjectionBuildState(source, revision, RecipeIndex.get(level));
        }
        ProjectionBuildState state = warmUpState;
        long startedNanos = System.nanoTime();
        long deadline = System.nanoTime() + Math.max(1L, budgetNanos);
        boolean done = state.advance(level, deadline);
        state.reportBudgetOverrun(System.nanoTime() - startedNanos, budgetNanos);
        if (done) publish(state);
    }

    private static ImmutableRecipeGraph publish(ProjectionBuildState state) {
        ImmutableRecipeGraph graph = new ImmutableRecipeGraph(state.projected);
        cachedProjection = new CachedProjection(state.source, state.revision, graph);
        warmUpState = null;
        PerformanceMonitor.recordRecipeGraphProjection(false, state.workNanos);
        RSIntegrationMod.LOGGER.info("[RecipeGraph] incrementally projected {} recipes in {}ms CPU",
                graph.recipesById().size(), state.workNanos / 1_000_000L);
        return graph;
    }

    private static void projectEntry(Level level, Map<MaterialRef, List<RecipeNode>> projected,
                                     Set<ResourceLocation> seen, RecipeIndex.Entry entry) {
        if (entry.modType() != ModType.GENERIC
                || !(entry.recipe() instanceof CraftingRecipe recipe)
                || !seen.add(recipe.getId())) return;
        ItemStack output = recipe.getResultItem(level.registryAccess());
        if (output.isEmpty()) return;
        List<IngredientSpec> specs = CraftPacketUtils.extractCraftingIngredientSpecs(recipe);
        List<IngredientRef> inputs = new ArrayList<>();
        for (IngredientSpec spec : specs) {
            if (spec.isEmpty()) continue;
            IngredientRef input = projectIngredient(spec);
            if (input == null) return;
            inputs.add(input);
        }
        MaterialRef outputRef = material(output, output.hasTag());
        RecipeNode node = new RecipeNode(recipe.getId(), outputRef,
                Math.max(1, output.getCount()), inputs);
        projected.computeIfAbsent(outputRef, ignored -> new ArrayList<>()).add(node);
    }

    private static final class ProjectionBuildState {
        private final RecipeManager source;
        private final long revision;
        private final Iterator<List<RecipeIndex.Entry>> groups;
        private final Map<MaterialRef, List<RecipeNode>> projected = new HashMap<>();
        private final Set<ResourceLocation> seen = new HashSet<>();
        private Iterator<RecipeIndex.Entry> current = List.<RecipeIndex.Entry>of().iterator();
        private long workNanos;
        private String lastRecipe = "initialization";
        private boolean budgetOverrunReported;

        private ProjectionBuildState(RecipeManager source, long revision,
                                     Map<?, List<RecipeIndex.Entry>> index) {
            this.source = source;
            this.revision = revision;
            this.groups = index.values().iterator();
        }

        private boolean matches(RecipeManager manager, long currentRevision) {
            return source == manager && revision == currentRevision;
        }

        private boolean advance(Level level, long deadlineNanos) {
            long started = System.nanoTime();
            int processed = 0;
            while (processed++ == 0 || System.nanoTime() < deadlineNanos) {
                while (!current.hasNext() && groups.hasNext()) current = groups.next().iterator();
                if (!current.hasNext()) {
                    workNanos += System.nanoTime() - started;
                    return true;
                }
                RecipeIndex.Entry entry = current.next();
                lastRecipe = entry.recipe().getId().toString();
                projectEntry(level, projected, seen, entry);
            }
            workNanos += System.nanoTime() - started;
            return false;
        }

        private void reportBudgetOverrun(long elapsedNanos, long budgetNanos) {
            long threshold = Math.max(5_000_000L, Math.max(1L, budgetNanos) * 4L);
            if (budgetOverrunReported || elapsedNanos <= threshold) return;
            budgetOverrunReported = true;
            RSIntegrationMod.LOGGER.warn(
                    "[RecipeGraph] projection unit '{}' took {}ms (tick budget {}ms)",
                    lastRecipe, elapsedNanos / 1_000_000L,
                    Math.max(1L, budgetNanos) / 1_000_000.0D);
        }
    }

    public static synchronized void clearCache() {
        cachedProjection = null;
        warmUpState = null;
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
        return new MaterialRef(id, includeNbt && stack.getTag() != null ? stack.getTag().toString() : "");
    }

    private record CachedProjection(RecipeManager source, long revision,
                                    ImmutableRecipeGraph graph) {
        private boolean matches(RecipeManager currentSource, long currentRevision) {
            return source == currentSource && revision == currentRevision;
        }
    }
}
