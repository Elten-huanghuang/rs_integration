package com.huanghuang.rsintegration.crafting.planning;

import com.google.gson.JsonElement;
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
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.NbtMatchMode;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.RecipeNode;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.Level;
import java.util.ArrayDeque;
import java.util.function.Supplier;
import net.minecraft.nbt.TagParser;
import net.minecraftforge.common.crafting.PartialNBTIngredient;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashMap;
import java.util.IdentityHashMap;

/** Immutable pure-planning projection published with the matching recipe index generation. */
public final class ImmutableRecipeGraphProjector {
    private static volatile CachedProjection cachedProjection;
    private static volatile CompiledIndexes compiledIndexes;

    private ImmutableRecipeGraphProjector() {}

    public static boolean isReady(Level level) {
        CachedProjection ready = cachedProjection;
        return ready != null && ready.matches(
                level.getRecipeManager(), CraftPlanningRevision.current());
    }

    public static ImmutableRecipeGraph capture(Level level) {
        PlanningThreadContext.requireServerThread("recipe graph projection");
        CachedProjection ready = cachedProjection;
        if (ready != null && ready.matches(
                level.getRecipeManager(), CraftPlanningRevision.current())) {
            PerformanceMonitor.recordRecipeGraphProjection(true, 0L);
            return ready.graph();
        }

        // 只读取已发布快照；目录未就绪时，由请求队列等待或返回可重试状态。
        throw new RecipeGraphUnavailableException(
                "Recipe catalog is still loading; planning requests should wait for RecipeIndex.isReady()");
    }

    public static final class RecipeGraphUnavailableException extends IllegalStateException {
        public RecipeGraphUnavailableException(String message) {
            super(message);
        }
    }

    /** Publishes a graph captured during the same pass that built the recipe index. */
    public static synchronized void publishCompiled(RecipeManager source, long revision,
                                                     ImmutableRecipeGraph graph,
                                                     long buildNanos) {
        publishPrepared(source, revision, prepareCompiled(graph), buildNanos);
    }

    /** 只整理不可变配方值；不会读取世界、注册表或第三方配方。 */
    public static PreparedProjection prepareCompiled(ImmutableRecipeGraph graph) {
        return new PreparedProjection(graph, CompiledIndexes.build(graph),
                PlanningLookupCache.prepareSharedNbtCache(graph));
    }

    public static synchronized void publishPrepared(RecipeManager source, long revision,
                                                      PreparedProjection prepared,
                                                      long buildNanos) {
        compiledIndexes = prepared.indexes;
        PlanningLookupCache.publishSharedNbtCache(prepared.nbtCache);
        cachedProjection = new CachedProjection(source, revision, prepared.graph);
        PerformanceMonitor.recordRecipeGraphProjection(false, buildNanos);
    }

    public static final class PreparedProjection {
        private final ImmutableRecipeGraph graph;
        private final CompiledIndexes indexes;
        private final PlanningLookupCache.SharedNbtCache nbtCache;

        private PreparedProjection(ImmutableRecipeGraph graph, CompiledIndexes indexes,
                                   PlanningLookupCache.SharedNbtCache nbtCache) {
            this.graph = graph;
            this.indexes = indexes;
            this.nbtCache = nbtCache;
        }
    }

    /** Returns the generation-level producer index, or null for an uncompiled test graph. */
    @Nullable
    static List<RecipeNode> publishedProducers(ImmutableRecipeGraph graph,
                                               ResourceLocation itemId) {
        CompiledIndexes indexes = compiledIndexes;
        GraphIndexes graphIndexes = indexes == null ? null : indexes.forGraph(graph);
        return graphIndexes == null ? null
                : graphIndexes.producers().getOrDefault(itemId, List.of());
    }

    /** Returns the generation-level output-variant index, or null for an uncompiled test graph. */
    @Nullable
    static List<MaterialRef> publishedOutputVariants(ImmutableRecipeGraph graph,
                                                     ResourceLocation itemId) {
        CompiledIndexes indexes = compiledIndexes;
        GraphIndexes graphIndexes = indexes == null ? null : indexes.forGraph(graph);
        return graphIndexes == null ? null
                : graphIndexes.outputs().getOrDefault(itemId, List.of());
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
        return projectRecipe(recipe.getId(), output, specs, "generic",
                new ResourceLocation("minecraft", "crafting"));
    }

    @Nullable
    public static RecipeNode projectRecipe(ResourceLocation recipeId, ItemStack output,
                                           List<IngredientSpec> specs, String modTypeId,
                                           ResourceLocation recipeTypeId) {
        return projectRecipe(recipeId, output, specs, modTypeId, recipeTypeId, true);
    }

    @Nullable
    public static RecipeNode projectRecipe(ResourceLocation recipeId, ItemStack output,
                                           List<IngredientSpec> specs, String modTypeId,
                                           ResourceLocation recipeTypeId,
                                           boolean includeOutputNbt) {
        if (output.isEmpty()) return null;
        List<IngredientRef> inputs = new ArrayList<>();
        for (IngredientSpec spec : specs) {
            if (spec.isEmpty()) continue;
            IngredientRef input = projectIngredient(spec);
            if (input == null) return null;
            inputs.add(input);
        }
        MaterialRef outputRef = includeOutputNbt ? material(output, output.hasTag())
                : new MaterialRef(BuiltInRegistries.ITEM.getKey(output.getItem()), "", true);
        return new RecipeNode(recipeId, outputRef, Math.max(1, output.getCount()),
                inputs, modTypeId, recipeTypeId);
    }

    public static synchronized void clearCache() {
        cachedProjection = null;
        compiledIndexes = null;
        PlanningLookupCache.clearSharedNbtCache();
    }

    private record CompiledIndexes(ImmutableRecipeGraph graph, GraphIndexes root,
                                   DependencyGraphCache dependencies) {
        private static CompiledIndexes build(ImmutableRecipeGraph graph) {
            GraphIndexes root = GraphIndexes.build(graph);
            return new CompiledIndexes(graph, root, new DependencyGraphCache(graph, root));
        }

        private GraphIndexes forGraph(ImmutableRecipeGraph candidate) {
            return candidate == graph ? root : dependencies.indexesFor(candidate);
        }
    }

    private record GraphIndexes(Map<ResourceLocation, List<RecipeNode>> producers,
                                Map<ResourceLocation, List<MaterialRef>> outputs) {
        private static GraphIndexes build(ImmutableRecipeGraph graph) {
            Map<ResourceLocation, List<RecipeNode>> producers = new HashMap<>();
            for (List<RecipeNode> nodes : graph.recipesByOutput().values()) {
                for (RecipeNode node : nodes) {
                    producers.computeIfAbsent(node.output().itemId(), ignored -> new ArrayList<>())
                            .add(node);
                }
            }
            producers.replaceAll((ignored, nodes) -> List.copyOf(nodes));

            Map<ResourceLocation, List<MaterialRef>> outputs = new HashMap<>();
            for (MaterialRef output : graph.recipesByOutput().keySet()) {
                outputs.computeIfAbsent(output.itemId(), ignored -> new ArrayList<>()).add(output);
            }
            outputs.replaceAll((ignored, variants) -> List.copyOf(variants));
            return new GraphIndexes(Map.copyOf(producers), Map.copyOf(outputs));
        }
    }

    /** Small generation-scoped LRU. Entries contain only immutable graph values and indexes. */
    private static final class DependencyGraphCache {
        private static final int MAX_ENTRIES = 32;

        private final ImmutableRecipeGraph rootGraph;
        private final GraphIndexes rootIndexes;
        private final LinkedHashMap<ResourceLocation, DependencyEntry> entries =
                new LinkedHashMap<>(16, 0.75f, true);
        private volatile Map<ImmutableRecipeGraph, GraphIndexes> indexesByGraph =
                new IdentityHashMap<>();

        private DependencyGraphCache(ImmutableRecipeGraph rootGraph, GraphIndexes rootIndexes) {
            this.rootGraph = rootGraph;
            this.rootIndexes = rootIndexes;
        }

        private ImmutableRecipeGraph getOrBuild(
                ResourceLocation recipeId,
                Supplier<ImmutableRecipeGraph> builder) {
            synchronized (this) {
                DependencyEntry cached = entries.get(recipeId);
                if (cached != null) return cached.graph();
            }

            ImmutableRecipeGraph graph = builder.get();
            GraphIndexes indexes = graph == rootGraph ? rootIndexes : GraphIndexes.build(graph);
            synchronized (this) {
                DependencyEntry concurrent = entries.get(recipeId);
                if (concurrent != null) return concurrent.graph();
                entries.put(recipeId, new DependencyEntry(graph, indexes));
                if (entries.size() > MAX_ENTRIES) {
                    var eldest = entries.entrySet().iterator();
                    eldest.next();
                    eldest.remove();
                }
                IdentityHashMap<ImmutableRecipeGraph, GraphIndexes> snapshot =
                        new IdentityHashMap<>();
                for (DependencyEntry entry : entries.values()) {
                    snapshot.put(entry.graph(), entry.indexes());
                }
                indexesByGraph = snapshot;
                return graph;
            }
        }

        private GraphIndexes indexesFor(ImmutableRecipeGraph graph) {
            return indexesByGraph.get(graph);
        }
    }

    private record DependencyEntry(ImmutableRecipeGraph graph, GraphIndexes indexes) {}

    public static Map<MaterialRef, Integer> projectAvailability(Map<StackKey, Integer> available) {
        Map<MaterialRef, Integer> projected = new HashMap<>();
        for (Map.Entry<StackKey, Integer> entry : available.entrySet()) {
            PlanningThreadContext.throwIfCancelled();
            ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(entry.getKey().item());
            if (itemId == null || entry.getValue() <= 0) continue;
            String nbt = entry.getKey().tag() == null ? "" : entry.getKey().tag();
            projected.merge(new MaterialRef(itemId, nbt), entry.getValue(), Integer::sum);
        }
        return Map.copyOf(projected);
    }

    /**
     * Bind value-only ingredient predicates to the concrete inventory variants in this snapshot.
     * Stock is kept one-to-one with physical item/NBT identities, so a tagged stack cannot be
     * counted once as an exact ingredient and again through a tagless alias.
     */
    public static ImmutableRecipeGraph bindAvailability(
            ImmutableRecipeGraph graph, Map<MaterialRef, Integer> available) {
        return PlanningLookupCache.run(() -> PlanningLookupCache.reusePreparation(
                PlanningLookupCache.PreparationStage.INVENTORY, graph, available,
                () -> bindAvailabilityInScope(graph, available)));
    }

    /**
     * Keeps only producers that can contribute to one target recipe's item
     * dependency closure. The catalog remains global, but planning no longer
     * prepares unrelated recipes from the whole modpack.
     */
    public static ImmutableRecipeGraph restrictToDependencies(ImmutableRecipeGraph graph,
                                                              ResourceLocation targetRecipeId) {
        CompiledIndexes indexes = compiledIndexes;
        if (indexes != null && indexes.graph() == graph) {
            return indexes.dependencies().getOrBuild(targetRecipeId,
                    () -> restrictToDependenciesUncached(graph, targetRecipeId));
        }
        return restrictToDependenciesUncached(graph, targetRecipeId);
    }

    private static ImmutableRecipeGraph restrictToDependenciesUncached(
            ImmutableRecipeGraph graph, ResourceLocation targetRecipeId) {
        RecipeNode target = graph.recipesById().get(targetRecipeId);
        if (target == null) return graph;

        Set<ResourceLocation> neededItems = new LinkedHashSet<>();
        Set<ResourceLocation> visitedRecipes = new LinkedHashSet<>();
        ArrayDeque<ResourceLocation> pendingItems = new ArrayDeque<>();
        visitedRecipes.add(target.recipeId());
        for (IngredientRef input : target.inputs()) {
            for (MaterialRef alternative : input.alternatives()) {
                if (neededItems.add(alternative.itemId())) pendingItems.addLast(alternative.itemId());
            }
        }

        while (!pendingItems.isEmpty()) {
            ResourceLocation itemId = pendingItems.removeFirst();
            for (RecipeNode producer : PlanningLookupCache.producers(graph, itemId)) {
                if (!producer.output().itemId().equals(itemId)
                        || !visitedRecipes.add(producer.recipeId())) continue;
                for (IngredientRef input : producer.inputs()) {
                    for (MaterialRef alternative : input.alternatives()) {
                        if (neededItems.add(alternative.itemId())) {
                            pendingItems.addLast(alternative.itemId());
                        }
                    }
                }
            }
        }

        Map<MaterialRef, List<RecipeNode>> projected = new LinkedHashMap<>();
        for (Map.Entry<MaterialRef, List<RecipeNode>> entry : graph.recipesByOutput().entrySet()) {
            List<RecipeNode> selected = entry.getValue().stream()
                    .filter(recipe -> visitedRecipes.contains(recipe.recipeId()))
                    .toList();
            if (!selected.isEmpty()) projected.put(entry.getKey(), selected);
        }
        return projected.size() == graph.recipesByOutput().size()
                ? graph : new ImmutableRecipeGraph(projected);
    }

    private static ImmutableRecipeGraph bindAvailabilityInScope(
            ImmutableRecipeGraph graph, Map<MaterialRef, Integer> available) {
        graph = bindSmithingStates(graph, available);
        Map<ResourceLocation, List<MaterialRef>> availableByItem = new HashMap<>();
        for (Map.Entry<MaterialRef, Integer> entry : available.entrySet()) {
            if (entry.getValue() == null || entry.getValue() <= 0) continue;
            availableByItem.computeIfAbsent(entry.getKey().itemId(), ignored -> new ArrayList<>())
                    .add(entry.getKey());
        }
        Map<IngredientRef, IngredientRef> boundIngredients = new HashMap<>();
        Map<MaterialRef, List<RecipeNode>> projected = new LinkedHashMap<>();
        Map<ResourceLocation, RecipeNode> indexed = new HashMap<>();
        boolean changed = false;
        for (Map.Entry<MaterialRef, List<RecipeNode>> entry : graph.recipesByOutput().entrySet()) {
            List<RecipeNode> recipes = null;
            int recipeIndex = 0;
            for (RecipeNode recipe : entry.getValue()) {
                List<IngredientRef> inputs = null;
                for (int inputIndex = 0; inputIndex < recipe.inputs().size(); inputIndex++) {
                    IngredientRef input = recipe.inputs().get(inputIndex);
                    IngredientRef boundInput = boundIngredients.computeIfAbsent(input,
                            key -> bindIngredientIndexed(key, availableByItem));
                    if (!boundInput.equals(input)) {
                        if (inputs == null) inputs = new ArrayList<>(recipe.inputs());
                        inputs.set(inputIndex, boundInput);
                    }
                }
                RecipeNode bound = inputs != null
                        ? new RecipeNode(recipe.recipeId(), recipe.output(), recipe.outputCount(),
                                inputs, recipe.modTypeId(), recipe.recipeTypeId())
                        : recipe;
                if (inputs != null) {
                    if (recipes == null) recipes = new ArrayList<>(entry.getValue());
                    recipes.set(recipeIndex, bound);
                    changed = true;
                }
                if (recipe.equals(graph.recipesById().get(recipe.recipeId()))) {
                    indexed.put(recipe.recipeId(), bound);
                }
                recipeIndex++;
            }
            projected.put(entry.getKey(), recipes == null ? entry.getValue() : recipes);
        }
        return changed ? new ImmutableRecipeGraph(projected, indexed) : graph;
    }

    static ImmutableRecipeGraph bindSmithingStates(
            ImmutableRecipeGraph graph, Map<MaterialRef, Integer> available) {
        return PlanningLookupCache.run(() -> PlanningLookupCache.reusePreparation(
                PlanningLookupCache.PreparationStage.SMITHING, graph, available,
                () -> bindSmithingStatesInScope(graph, available, null)));
    }

    /**
     * Propagates concrete equipment state only through smithing paths that feed
     * an exact or partial NBT demand reachable from this request's roots.
     */
    static ImmutableRecipeGraph bindSmithingStates(
            ImmutableRecipeGraph graph, Map<MaterialRef, Integer> available,
            List<IngredientRef> roots) {
        List<IngredientRef> normalizedRoots = PureDemandNormalizer.mergeEquivalent(roots);
        Set<ResourceLocation> relevantItems =
                stateSensitiveSmithingItems(graph, normalizedRoots);
        return PlanningLookupCache.run(() -> PlanningLookupCache.reusePreparation(
                PlanningLookupCache.PreparationStage.SMITHING, graph, available,
                relevantItems, () -> bindSmithingStatesInScope(
                        graph, available, relevantItems)));
    }

    private static ImmutableRecipeGraph bindSmithingStatesInScope(
            ImmutableRecipeGraph graph, Map<MaterialRef, Integer> available,
            @Nullable Set<ResourceLocation> relevantItems) {
        if (relevantItems != null && relevantItems.isEmpty()) return graph;
        Map<ResourceLocation, List<RecipeNode>> upgrades = new HashMap<>();
        for (RecipeNode recipe : graph.recipesById().values()) {
            if (!"smithing".equals(recipe.modTypeId()) || recipe.inputs().size() != 3) continue;
            if (relevantItems != null && !relevantItems.contains(recipe.output().itemId())) continue;
            Set<ResourceLocation> baseItems = new LinkedHashSet<>();
            for (MaterialRef base : recipe.inputs().get(1).alternatives()) baseItems.add(base.itemId());
            for (ResourceLocation item : baseItems) {
                upgrades.computeIfAbsent(item, ignored -> new ArrayList<>()).add(recipe);
            }
        }
        if (upgrades.isEmpty()) return graph;
        Set<MaterialRef> seen = new LinkedHashSet<>();
        available.forEach((material, count) -> {
            if (count != null && count > 0 && !material.nbt().isEmpty()
                    && upgrades.containsKey(material.itemId())
                    && (relevantItems == null || relevantItems.contains(material.itemId()))) {
                seen.add(material);
            }
        });
        ArrayDeque<MaterialRef> pending = new ArrayDeque<>(seen);
        Map<MaterialRef, List<RecipeNode>> additions = new LinkedHashMap<>();
        while (!pending.isEmpty()) {
            PlanningThreadContext.throwIfCancelled();
            MaterialRef actualBase = pending.removeFirst();
            for (RecipeNode recipe : upgrades.getOrDefault(actualBase.itemId(), List.of())) {
                IngredientRef base = recipe.inputs().get(1);
                if (!matchesIngredient(actualBase, base)) continue;
                MaterialRef output = new MaterialRef(recipe.output().itemId(), actualBase.nbt());
                List<IngredientRef> inputs = new ArrayList<>(recipe.inputs());
                inputs.set(1, new IngredientRef(List.of(actualBase), base.count(),
                        NbtMatchMode.EXACT, base.role()));
                RecipeNode specialized = new RecipeNode(recipe.recipeId(), output,
                        recipe.outputCount(), inputs, recipe.modTypeId(), recipe.recipeTypeId());
                if (!graph.recipesByOutput().getOrDefault(output, List.of()).contains(specialized)) {
                    List<RecipeNode> variants = additions.computeIfAbsent(output, ignored -> new ArrayList<>());
                    if (!variants.contains(specialized)) variants.add(specialized);
                }
                if (seen.add(output)) pending.addLast(output);
            }
        }
        if (additions.isEmpty()) return graph;
        Map<MaterialRef, List<RecipeNode>> projected = new LinkedHashMap<>(graph.recipesByOutput());
        additions.forEach((output, variants) -> {
            List<RecipeNode> combined = new ArrayList<>(projected.getOrDefault(output, List.of()));
            combined.addAll(variants);
            projected.put(output, combined);
        });
        return new ImmutableRecipeGraph(projected, graph.recipesById());
    }

    private static Set<ResourceLocation> stateSensitiveSmithingItems(
            ImmutableRecipeGraph graph, List<IngredientRef> roots) {
        Set<ResourceLocation> visitedItems = new LinkedHashSet<>();
        Set<ResourceLocation> visitedRecipes = new LinkedHashSet<>();
        Set<ResourceLocation> statefulDemands = new LinkedHashSet<>();
        ArrayDeque<IngredientRef> pending = new ArrayDeque<>(roots);
        while (!pending.isEmpty()) {
            PlanningThreadContext.throwIfCancelled();
            IngredientRef demand = pending.removeFirst();
            if (demand.nbtMatchMode() != NbtMatchMode.ANY) {
                demand.alternatives().stream()
                        .filter(material -> !material.nbt().isEmpty())
                        .map(MaterialRef::itemId)
                        .forEach(statefulDemands::add);
            }
            for (MaterialRef alternative : demand.alternatives()) {
                if (!visitedItems.add(alternative.itemId())) continue;
                for (RecipeNode producer : PlanningLookupCache.producers(
                        graph, alternative.itemId())) {
                    if (!visitedRecipes.add(producer.recipeId())) continue;
                    pending.addAll(producer.inputs());
                }
            }
        }
        if (statefulDemands.isEmpty()) return Set.of();

        Map<ResourceLocation, List<RecipeNode>> smithingByOutput = new HashMap<>();
        for (RecipeNode recipe : graph.recipesById().values()) {
            if ("smithing".equals(recipe.modTypeId()) && recipe.inputs().size() == 3) {
                smithingByOutput.computeIfAbsent(recipe.output().itemId(), ignored -> new ArrayList<>())
                        .add(recipe);
            }
        }
        Set<ResourceLocation> relevant = new LinkedHashSet<>(statefulDemands);
        ArrayDeque<ResourceLocation> pendingOutputs =
                new ArrayDeque<>(statefulDemands);
        while (!pendingOutputs.isEmpty()) {
            PlanningThreadContext.throwIfCancelled();
            ResourceLocation output = pendingOutputs.removeFirst();
            for (RecipeNode recipe : smithingByOutput.getOrDefault(output, List.of())) {
                for (MaterialRef base : recipe.inputs().get(1).alternatives()) {
                    if (relevant.add(base.itemId())) pendingOutputs.addLast(base.itemId());
                }
            }
        }
        return Set.copyOf(relevant);
    }

    static IngredientRef bindIngredient(IngredientRef ingredient,
                                        Map<MaterialRef, Integer> available) {
        Map<ResourceLocation, List<MaterialRef>> availableByItem = new HashMap<>();
        for (Map.Entry<MaterialRef, Integer> entry : available.entrySet()) {
            if (entry.getValue() == null || entry.getValue() <= 0) continue;
            availableByItem.computeIfAbsent(entry.getKey().itemId(), ignored -> new ArrayList<>())
                    .add(entry.getKey());
        }
        return bindIngredientIndexed(ingredient, availableByItem);
    }

    private static IngredientRef bindIngredientIndexed(
            IngredientRef ingredient,
            Map<ResourceLocation, List<MaterialRef>> availableByItem) {
        Set<MaterialRef> alternatives = new LinkedHashSet<>(ingredient.alternatives());
        for (MaterialRef expected : ingredient.alternatives()) {
            for (MaterialRef actual : availableByItem.getOrDefault(
                    expected.itemId(), List.of())) {
                if (matchesIngredient(actual, ingredient)) {
                    alternatives.add(actual);
                }
            }
        }
        List<MaterialRef> boundAlternatives = List.copyOf(alternatives);
        if (boundAlternatives.equals(ingredient.alternatives())) return ingredient;
        return new IngredientRef(boundAlternatives, ingredient.count(),
                ingredient.nbtMatchMode(), ingredient.role());
    }

    static boolean partialNbtMatches(String expectedSnbt, String actualSnbt) {
        return PlanningLookupCache.matchesNbt(expectedSnbt, actualSnbt, true);
    }

    /** Exact matching counterpart that accepts the two vanilla/CraftTweaker
     * serializations of a pristine unbreakable tool. */
    static boolean exactNbtMatches(String expectedSnbt, String actualSnbt) {
        return PlanningLookupCache.matchesNbt(expectedSnbt, actualSnbt, false);
    }

    static boolean matchesIngredient(MaterialRef actual, IngredientRef ingredient) {
        Boolean cached = PlanningLookupCache.lookupIngredientMatch(actual, ingredient);
        if (cached != null) return cached;
        boolean result = matchesIngredientUncached(actual, ingredient);
        PlanningLookupCache.recordIngredientMatch(actual, ingredient, result);
        return result;
    }

    private static boolean matchesIngredientUncached(MaterialRef actual, IngredientRef ingredient) {
        for (MaterialRef expected : ingredient.alternatives()) {
            if (!actual.itemId().equals(expected.itemId())) continue;
            if (ingredient.nbtMatchMode() == NbtMatchMode.ANY) return true;
            if (actual.runtimeNbt()) continue;
            if (isTaintedEarthHeart(actual, expected)) return true;
            if (ingredient.nbtMatchMode() == NbtMatchMode.EXACT
                    ? exactNbtMatches(expected.nbt(), actual.nbt())
                    : partialNbtMatches(expected.nbt(), actual.nbt())) return true;
        }
        return false;
    }

    private static boolean isTaintedEarthHeart(MaterialRef actual, MaterialRef expected) {
        if (!"enigmaticlegacy".equals(actual.itemId().getNamespace())
                || !"earth_heart".equals(actual.itemId().getPath())) return false;
        try {
            var expectedTag = TagParser.parseTag(expected.nbt());
            var actualTag = TagParser.parseTag(actual.nbt());
            return expectedTag.getBoolean("isTainted") && actualTag.getBoolean("isTainted");
        } catch (Exception ignored) {
            return false;
        }
    }

    static RecipeNode withDemandedOutput(RecipeNode recipe, MaterialRef wanted,
                                         NbtMatchMode mode) {
        if (wanted.runtimeNbt() || (wanted.nbt().isEmpty() && mode != NbtMatchMode.EXACT)
                || !"smithing".equals(recipe.modTypeId())
                || !recipe.output().itemId().equals(wanted.itemId())
                || recipe.inputs().size() != 3) return recipe;
        List<IngredientRef> inputs = new ArrayList<>(recipe.inputs());
        IngredientRef base = inputs.get(1);
        List<MaterialRef> bases = base.alternatives().stream()
                .map(material -> new MaterialRef(material.itemId(), wanted.nbt()))
                .filter(material -> matchesIngredient(material, base)).toList();
        if (bases.isEmpty()) return null;
        inputs.set(1, new IngredientRef(bases, base.count(), NbtMatchMode.EXACT, base.role()));
        return new RecipeNode(recipe.recipeId(), wanted, recipe.outputCount(), inputs,
                recipe.modTypeId(), recipe.recipeTypeId());
    }

    static List<RecipeNode> candidates(ImmutableRecipeGraph graph, MaterialRef wanted,
                                       NbtMatchMode mode) {
        if (mode == NbtMatchMode.EXACT && wanted.nbt().isEmpty()) {
            if (wanted.runtimeNbt()) return List.of();
            List<RecipeNode> direct = graph.recipesByOutput().getOrDefault(wanted, List.of());
            List<RecipeNode> combined = null;
            // Preserve the constant-time tagless lookup. Only smithing can prove
            // a tagless result from an unknown output by constraining its base.
            for (RecipeNode recipe : graph.recipesByOutput().getOrDefault(
                    new MaterialRef(wanted.itemId(), "", true), List.of())) {
                if (!"smithing".equals(recipe.modTypeId())) continue;
                RecipeNode specialized = withDemandedOutput(recipe, wanted, mode);
                if (specialized == null || specialized.output().runtimeNbt()) continue;
                if (combined == null) combined = new ArrayList<>(direct);
                combined.add(specialized);
            }
            return combined == null ? direct : combined;
        }
        IngredientRef demand = new IngredientRef(List.of(wanted), 1, mode);
        List<RecipeNode> candidates = new ArrayList<>();
        for (MaterialRef output : PlanningLookupCache.outputVariants(graph, wanted.itemId())) {
            for (RecipeNode recipe : graph.recipesByOutput().get(output)) {
                if (matchesIngredient(output, demand)) {
                    candidates.add(recipe);
                } else if ("smithing".equals(recipe.modTypeId())) {
                    RecipeNode specialized = withDemandedOutput(recipe, wanted, mode);
                    if (specialized != null && matchesIngredient(specialized.output(), demand)) {
                        candidates.add(specialized);
                    }
                }
            }
        }
        return candidates;
    }

    public static IngredientRef projectIngredient(IngredientSpec spec) {
        // Replacement containers still consume one full input per execution; their
        // remainder is emitted by the real crafting executor. Catalysts are retained as
        // non-consuming demands by the immutable planner. Transformed inputs still need
        // typed execution semantics and therefore cannot be projected.
        if (spec.role() != DemandRole.CONSUMED
                && spec.role() != DemandRole.CONTAINER_RETURNING
                && spec.role() != DemandRole.CATALYST) return null;
        Ingredient ingredient = spec.ingredient();
        boolean strictNbt = IngredientMatcher.requiresNbt(ingredient);
        NbtMatchMode matchMode = nbtMatchMode(ingredient);
        Set<MaterialRef> alternatives = new LinkedHashSet<>();
        for (ItemStack candidate : ingredient.getItems()) {
            if (!candidate.isEmpty()) alternatives.add(material(candidate, strictNbt));
        }
        return alternatives.isEmpty() ? null
                : new IngredientRef(List.copyOf(alternatives), spec.count(), matchMode,
                spec.role());
    }

    private static boolean isPartialNbtIngredient(Ingredient ingredient) {
        if (ingredient instanceof PartialNBTIngredient) return true;
        for (Class<?> type = ingredient.getClass(); type != null; type = type.getSuperclass()) {
            if (isPartialNbtIngredientClass(type.getName())) return true;
        }
        try {
            return containsPartialTagType(ingredient.toJson());
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    /** Determines the immutable planner's NBT semantics for one live ingredient. */
    public static NbtMatchMode nbtMatchMode(Ingredient ingredient) {
        if (!IngredientMatcher.requiresNbt(ingredient)) return NbtMatchMode.ANY;
        return isPartialNbtIngredient(ingredient) ? NbtMatchMode.PARTIAL : NbtMatchMode.EXACT;
    }

    static boolean containsPartialTagType(JsonElement json) {
        if (json == null || json.isJsonNull()) return false;
        if (json.isJsonPrimitive()) {
            return json.getAsJsonPrimitive().isString()
                    && "crafttweaker:partial_tag".equals(json.getAsString());
        }
        if (json.isJsonArray()) {
            for (JsonElement child : json.getAsJsonArray()) {
                if (containsPartialTagType(child)) return true;
            }
            return false;
        }
        for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject().entrySet()) {
            if (containsPartialTagType(entry.getValue())) return true;
        }
        return false;
    }

    static boolean isPartialNbtIngredientClass(String className) {
        return "com.blamejared.crafttweaker.api.ingredient.type.IngredientPartialTag"
                .equals(className);
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
