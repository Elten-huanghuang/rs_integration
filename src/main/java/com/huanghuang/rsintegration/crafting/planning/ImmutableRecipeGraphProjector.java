package com.huanghuang.rsintegration.crafting.planning;

import com.google.gson.JsonElement;
import com.huanghuang.rsintegration.crafting.CanonicalNbt;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.CraftPlanningRevision;
import com.huanghuang.rsintegration.crafting.IngredientMatcher;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.ScaledIngredientSpec;
import com.huanghuang.rsintegration.crafting.RecipeIndex;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.crafting.plan.MagicResourceCostCatalog;
import com.huanghuang.rsintegration.magic.MagicResourceCost;
import com.huanghuang.rsintegration.mods.slashblade.SlashBladePlanning;
import com.huanghuang.rsintegration.mods.slashblade.SlashBladeRecipeHandler;
import com.huanghuang.rsintegration.util.PerformanceMonitor;
import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.IngredientRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.MaterialRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.NbtMatchMode;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.RecipeNode;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.ResourceKind;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.Recipe;
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
                : graphIndexes.producers().producers(itemId);
    }

    @Nullable
    static List<MaterialRef> publishedRecipeOutputs(ImmutableRecipeGraph graph,
                                                    ResourceLocation recipeId) {
        CompiledIndexes indexes = compiledIndexes;
        GraphIndexes graphIndexes = indexes == null ? null : indexes.forGraph(graph);
        return graphIndexes == null ? null : graphIndexes.producers().outputs(graph, recipeId);
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
        return projectRecipe(recipe, output, specs, "generic",
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
        return projectRecipe(recipeId, output, specs, modTypeId, recipeTypeId,
                includeOutputNbt, List.of());
    }

    public static RecipeNode projectRecipe(Recipe<?> recipe, ItemStack output,
                                           List<IngredientSpec> specs, String modTypeId,
                                           ResourceLocation recipeTypeId) {
        return projectRecipe(recipe, output, specs, modTypeId, recipeTypeId, true);
    }

    public static RecipeNode projectRecipe(Recipe<?> recipe, ItemStack output,
                                           List<IngredientSpec> specs, String modTypeId,
                                           ResourceLocation recipeTypeId,
                                           boolean includeOutputNbt) {
        return projectRecipe(recipe.getId(), output, specs, modTypeId, recipeTypeId,
                includeOutputNbt, MagicResourceCostCatalog.costsFor(recipe));
    }

    private static RecipeNode projectRecipe(ResourceLocation recipeId, ItemStack output,
                                            List<IngredientSpec> specs, String modTypeId,
                                            ResourceLocation recipeTypeId,
                                            boolean includeOutputNbt,
                                            List<MagicResourceCost> magicCosts) {
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
        if ("slashblade".equals(modTypeId)) {
            ItemStack minimum = SlashBladePlanning.minimumOutput(output, specs);
            if (minimum == null) return null;
            outputRef = new MaterialRef(outputRef.itemId(), minimum.getTag().toString(), false, true);
        }
        if (outputRef.runtimeNbt() && "smithing".equals(modTypeId)
                && inputs.size() == 3 && templateProducesOutput(inputs.get(0), outputRef)) {
            ResourceLocation outputItemId = outputRef.itemId();
            outputRef = inputs.get(0).alternatives().stream()
                    .filter(material -> material.itemId().equals(outputItemId))
                    .findFirst()
                    .orElse(outputRef);
        }
        return new RecipeNode(recipeId, outputRef, Math.max(1, output.getCount()),
                inputs, modTypeId, recipeTypeId, magicCosts);
    }

    private static boolean templateProducesOutput(IngredientRef template, MaterialRef output) {
        return output.isItem() && template.alternatives().stream()
                .anyMatch(material -> material.itemId().equals(output.itemId()));
    }

    /** 根据当前机器状态替换指定配方的单次输入，不修改已发布的全局配方图。 */
    public static ImmutableRecipeGraph withRecipeInputs(
            ImmutableRecipeGraph graph, Map<ResourceLocation, List<IngredientRef>> replacements) {
        if (replacements.isEmpty()) return graph;
        Map<MaterialRef, List<RecipeNode>> projected = new LinkedHashMap<>();
        boolean changed = false;
        for (Map.Entry<MaterialRef, List<RecipeNode>> entry : graph.recipesByOutput().entrySet()) {
            List<RecipeNode> recipes = new ArrayList<>(entry.getValue().size());
            for (RecipeNode recipe : entry.getValue()) {
                List<IngredientRef> inputs = replacements.get(recipe.recipeId());
                if (inputs != null && !inputs.equals(recipe.inputs())) {
                    recipes.add(new RecipeNode(recipe.recipeId(), recipe.output(),
                            recipe.outputCount(), inputs, recipe.modTypeId(),
                            recipe.recipeTypeId(), recipe.magicCosts(),
                            recipe.hasFluidOutputs(),
                            recipe.dynamicIdentityTargetable()));
                    changed = true;
                } else {
                    recipes.add(recipe);
                }
            }
            projected.put(entry.getKey(), recipes);
        }
        return changed ? new ImmutableRecipeGraph(projected) : graph;
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

    private record GraphIndexes(RecipeProducerIndex producers,
                                Map<ResourceLocation, List<MaterialRef>> outputs) {
        private static GraphIndexes build(ImmutableRecipeGraph graph) {
            Map<ResourceLocation, List<MaterialRef>> outputs = new HashMap<>();
            for (MaterialRef output : graph.recipesByOutput().keySet()) {
                outputs.computeIfAbsent(output.itemId(), ignored -> new ArrayList<>()).add(output);
            }
            outputs.replaceAll((ignored, variants) -> List.copyOf(variants));
            return new GraphIndexes(RecipeProducerIndex.build(graph), Map.copyOf(outputs));
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

    private record ResourceLookup(ResourceKind kind, ResourceLocation id) {
        private static ResourceLookup of(MaterialRef material) {
            return new ResourceLookup(
                    material.kind(), material.resourceId());
        }
    }

    public static Map<MaterialRef, Long> projectAvailability(
            Map<StackKey, ? extends Number> available) {
        Map<MaterialRef, Long> projected = new HashMap<>();
        for (Map.Entry<StackKey, ? extends Number> entry : available.entrySet()) {
            long count = entry.getValue() == null ? 0L : entry.getValue().longValue();
            ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(entry.getKey().item());
            if (itemId == null || count <= 0L) continue;
            String nbt = entry.getKey().tag() == null ? "" : entry.getKey().tag();
            MaterialRef material = new MaterialRef(itemId, nbt);
            projected.merge(material, count, ImmutableRecipeGraphProjector::saturatedAdd);
        }
        return Map.copyOf(projected);
    }

    /** 复制已按资源类型区分的不可变库存快照。 */
    public static Map<MaterialRef, Long> copyAvailability(
            Map<MaterialRef, ? extends Number> available
    ) {
        Map<MaterialRef, Long> projected = new HashMap<>(available.size());
        available.forEach((material, amount) -> {
            if (material == null || amount == null) return;
            long count = Math.max(0L, amount.longValue());
            if (count > 0L) {
                projected.merge(material, count,
                        ImmutableRecipeGraphProjector::saturatedAdd);
            }
        });
        return Map.copyOf(projected);
    }

    private static long saturatedAdd(long left, long right) {
        return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
    }

    /**
     * Bind value-only ingredient predicates to the concrete inventory variants in this snapshot.
     * Stock is kept one-to-one with physical item/NBT identities, so a tagged stack cannot be
     * counted once as an exact ingredient and again through a tagless alias.
     */
    public static ImmutableRecipeGraph bindAvailability(
            ImmutableRecipeGraph graph, Map<MaterialRef, ? extends Number> supplied) {
        return PlanningLookupCache.run(() -> PlanningLookupCache.reusePreparation(
                PlanningLookupCache.PreparationStage.INVENTORY, graph, supplied,
                () -> bindAvailabilityInScope(graph, supplied)));
    }

    /**
     * Keeps only producers that can contribute to one target recipe's item
     * dependency closure. The catalog remains global, but planning no longer
     * prepares unrelated recipes from the whole modpack.
     */
    public static ImmutableRecipeGraph restrictToDependencies(ImmutableRecipeGraph graph,
                                                              ResourceLocation targetRecipeId) {
        PlanningThreadContext.throwIfCancelled();
        CompiledIndexes indexes = compiledIndexes;
        if (indexes != null && indexes.graph() == graph) {
            return indexes.dependencies().getOrBuild(targetRecipeId,
                    () -> PlanningLookupCache.run(() -> restrictToDependenciesUncached(graph, targetRecipeId)));
        }
        return PlanningLookupCache.run(() -> restrictToDependenciesUncached(graph, targetRecipeId));
    }

    private static ImmutableRecipeGraph restrictToDependenciesUncached(
            ImmutableRecipeGraph graph, ResourceLocation targetRecipeId) {
        RecipeNode target = graph.recipesById().get(targetRecipeId);
        if (target == null) return graph;

        Set<ResourceLookup> neededResources = new LinkedHashSet<>();
        Set<ResourceLocation> visitedRecipes = new LinkedHashSet<>();
        ArrayDeque<ResourceLookup> pendingResources = new ArrayDeque<>();
        visitedRecipes.add(target.recipeId());
        for (IngredientRef input : target.inputs()) {
            for (MaterialRef alternative : input.alternatives()) {
                ResourceLookup key = ResourceLookup.of(alternative);
                if (neededResources.add(key)) pendingResources.addLast(key);
            }
        }

        while (!pendingResources.isEmpty()) {
            PlanningThreadContext.throwIfCancelled();
            ResourceLookup resource = pendingResources.removeFirst();
            for (RecipeNode producer : PlanningLookupCache.producers(
                    graph, resource.id())) {
                if (producer.output().kind() != resource.kind()
                        || !producer.output().resourceId().equals(resource.id())
                        || !visitedRecipes.add(producer.recipeId())) continue;
                for (IngredientRef input : producer.inputs()) {
                    for (MaterialRef alternative : input.alternatives()) {
                        ResourceLookup key = ResourceLookup.of(alternative);
                        if (neededResources.add(key)) {
                            pendingResources.addLast(key);
                        }
                    }
                }
            }
        }

        if (visitedRecipes.size() == graph.recipesById().size()) return graph;

        // 只检查命中配方的输出桶；同 ID 的 NBT/动态变体也必须完整保留。
        Set<MaterialRef> selectedOutputs = new LinkedHashSet<>();
        Map<ResourceLocation, RecipeNode> selectedById = new LinkedHashMap<>();
        for (ResourceLocation recipeId : visitedRecipes) {
            PlanningThreadContext.throwIfCancelled();
            selectedOutputs.addAll(PlanningLookupCache.recipeOutputs(graph, recipeId));
            RecipeNode canonical = graph.recipesById().get(recipeId);
            if (canonical != null) selectedById.put(recipeId, canonical);
        }
        Map<MaterialRef, List<RecipeNode>> projected = new LinkedHashMap<>();
        for (MaterialRef output : selectedOutputs) {
            PlanningThreadContext.throwIfCancelled();
            List<RecipeNode> selected = new ArrayList<>();
            for (RecipeNode recipe : graph.recipesByOutput().getOrDefault(output, List.of())) {
                if (visitedRecipes.contains(recipe.recipeId())) {
                    selected.add(recipe);
                    selectedById.putIfAbsent(recipe.recipeId(), recipe);
                }
            }
            if (!selected.isEmpty()) projected.put(output, selected);
        }
        return new ImmutableRecipeGraph(projected, selectedById);
    }

    private static ImmutableRecipeGraph bindAvailabilityInScope(
            ImmutableRecipeGraph graph, Map<MaterialRef, ? extends Number> available) {
        graph = bindSmithingStates(graph, available);
        Map<ResourceLocation, List<MaterialRef>> availableByItem = new HashMap<>();
        for (Map.Entry<MaterialRef, ? extends Number> entry : available.entrySet()) {
            if (entry.getValue() == null || entry.getValue().longValue() <= 0L) continue;
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
                                inputs, recipe.modTypeId(), recipe.recipeTypeId(),
                                recipe.magicCosts(), recipe.hasFluidOutputs(),
                                recipe.dynamicIdentityTargetable())
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
            ImmutableRecipeGraph graph, Map<MaterialRef, ? extends Number> supplied) {
        return PlanningLookupCache.run(() -> PlanningLookupCache.reusePreparation(
                PlanningLookupCache.PreparationStage.SMITHING, graph, supplied,
                () -> bindSmithingStatesInScope(graph, supplied, null)));
    }

    /**
     * Propagates concrete equipment state only through smithing paths that feed
     * an exact or partial NBT demand reachable from this request's roots.
     */
    static ImmutableRecipeGraph bindSmithingStates(
            ImmutableRecipeGraph graph, Map<MaterialRef, ? extends Number> supplied,
            List<IngredientRef> roots) {
        return PlanningLookupCache.run(() -> {
            List<IngredientRef> normalizedRoots = PureDemandNormalizer.mergeEquivalent(roots);
            Set<ResourceLocation> relevantItems = stateSensitiveSmithingItems(graph, normalizedRoots);
            return PlanningLookupCache.reusePreparation(
                    PlanningLookupCache.PreparationStage.SMITHING, graph, supplied,
                    relevantItems, () -> bindSmithingStatesInScope(graph, supplied, relevantItems));
        });
    }

    private static ImmutableRecipeGraph bindSmithingStatesInScope(
            ImmutableRecipeGraph graph, Map<MaterialRef, ? extends Number> available,
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
            if (count != null && count.longValue() > 0L && !material.nbt().isEmpty()
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
                        NbtMatchMode.EXACT, base.role(), base.recursive()));
                RecipeNode specialized = new RecipeNode(recipe.recipeId(), output,
                        recipe.outputCount(), inputs, recipe.modTypeId(),
                        recipe.recipeTypeId(), recipe.magicCosts(),
                        recipe.hasFluidOutputs(),
                        recipe.dynamicIdentityTargetable());
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
                                        Map<MaterialRef, ? extends Number> available) {
        Map<ResourceLocation, List<MaterialRef>> availableByItem = new HashMap<>();
        for (Map.Entry<MaterialRef, ? extends Number> entry : available.entrySet()) {
            if (entry.getValue() == null || entry.getValue().longValue() <= 0L) continue;
            availableByItem.computeIfAbsent(entry.getKey().itemId(), ignored -> new ArrayList<>())
                    .add(entry.getKey());
        }
        return bindIngredientIndexed(ingredient, availableByItem);
    }

    private static IngredientRef bindIngredientIndexed(
            IngredientRef ingredient,
            Map<ResourceLocation, List<MaterialRef>> availableByItem) {
        // 这些 alternatives 保存条件，不是整份物品快照；追加库存会错误抬高门槛。
        if (ingredient.nbtMatchMode() == NbtMatchMode.SLASHBLADE) return ingredient;
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
                ingredient.nbtMatchMode(), ingredient.role(), ingredient.recursive());
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
            if (actual.kind() != expected.kind()
                    || !actual.resourceId().equals(expected.resourceId())) continue;
            if (ingredient.nbtMatchMode() == NbtMatchMode.ANY) return true;
            if (actual.runtimeNbt()) continue;
            if (ingredient.nbtMatchMode() == NbtMatchMode.SLASHBLADE) {
                if (PlanningLookupCache.matchesSlashBlade(expected.nbt(), actual.nbt())) return true;
                continue;
            }
            // 继承数值的下限不能冒充实际输出的完整 NBT。
            if (actual.constrainedNbt()) continue;
            if (isTaintedEarthHeart(actual, expected)) return true;
            if (ingredient.nbtMatchMode() == NbtMatchMode.EXACT
                    ? exactNbtMatches(expected.nbt(), actual.nbt())
                    : partialNbtMatches(expected.nbt(), actual.nbt())) return true;
            // Purity 只补偿普通 NBT 精确比较无法表达的元数据差异，必须放在
            // 普通比较之后，避免为完全相同的药水重复构建和解析状态。
            if (ingredient.nbtMatchMode() == NbtMatchMode.EXACT
                    && matchesPotionPuritySemantics(expected, actual)) return true;
        }
        return false;
    }

    private static boolean matchesPotionPuritySemantics(MaterialRef expected, MaterialRef actual) {
        if (!expected.isItem() || !actual.isItem()
                || expected.runtimeNbt() || actual.runtimeNbt()
                || !isVanillaPotionId(expected.itemId())
                || !expected.itemId().equals(actual.itemId())) return false;
        return IngredientMatcher.matchesPotionPurityBridge(
                PlanningLookupCache.readOnlyTag(expected.nbt()),
                PlanningLookupCache.readOnlyTag(actual.nbt()));
    }

    private static boolean isVanillaPotionId(ResourceLocation itemId) {
        return "minecraft".equals(itemId.getNamespace())
                && ("potion".equals(itemId.getPath())
                || "splash_potion".equals(itemId.getPath())
                || "lingering_potion".equals(itemId.getPath()));
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
        if (mode == NbtMatchMode.SLASHBLADE && recipe.output().constrainedNbt()) {
            return withDemandedBladeState(recipe, wanted);
        }
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
        inputs.set(1, new IngredientRef(bases, base.count(), NbtMatchMode.EXACT,
                base.role(), base.recursive()));
        return new RecipeNode(recipe.recipeId(), wanted, recipe.outputCount(), inputs,
                recipe.modTypeId(), recipe.recipeTypeId(), recipe.magicCosts(),
                recipe.hasFluidOutputs(),
                recipe.dynamicIdentityTargetable());
    }

    /** 单刀升级把更高的目标门槛传回原料；禁止凭空提升产物数值。 */
    private static RecipeNode withDemandedBladeState(RecipeNode recipe, MaterialRef wanted) {
        if (!"slashblade".equals(recipe.modTypeId())
                || !recipe.output().itemId().equals(wanted.itemId())) return null;
        CompoundTag cachedOutput = PlanningLookupCache.readOnlyTag(recipe.output().nbt());
        CompoundTag requiredTag = PlanningLookupCache.readOnlyTag(wanted.nbt());
        if (cachedOutput == null || requiredTag == null
                || !cachedOutput.getCompound("bladeState").getString("translationKey")
                .equals(requiredTag.getCompound("bladeState").getString("translationKey"))) return null;
        int bladeInput = -1;
        for (int i = 0; i < recipe.inputs().size(); i++) {
            IngredientRef input = recipe.inputs().get(i);
            if (input.nbtMatchMode() != NbtMatchMode.SLASHBLADE) continue;
            if (bladeInput >= 0 || input.count() != 1 || input.alternatives().size() != 1
                    || input.role() != DemandRole.CONSUMED) return null;
            bladeInput = i;
        }
        if (bladeInput < 0) return null;
        try {
            CompoundTag output = cachedOutput.copy();
            CompoundTag state = output.getCompound("bladeState");
            CompoundTag required = requiredTag.getCompound("bladeState");
            IngredientRef original = recipe.inputs().get(bladeInput);
            MaterialRef source = original.alternatives().get(0);
            CompoundTag originalTag = PlanningLookupCache.readOnlyTag(source.nbt());
            if (originalTag == null) return null;
            CompoundTag input = SlashBladePlanning.raiseInput(originalTag, output, requiredTag);
            if (input == null) return null;
            for (String field : List.of("killCount", "proudSoul", "RepairCounter")) {
                int needed = required.getInt(field);
                int guaranteed = state.getInt(field);
                if (needed <= guaranteed) continue;
                state.putInt(field, needed);
            }
            List<IngredientRef> inputs = new ArrayList<>(recipe.inputs());
            inputs.set(bladeInput, new IngredientRef(List.of(new MaterialRef(source.itemId(), input.toString())),
                    original.count(), NbtMatchMode.SLASHBLADE, original.role()));
            return new RecipeNode(recipe.recipeId(), new MaterialRef(recipe.output().itemId(),
                    output.toString(), false, true), recipe.outputCount(), inputs,
                    recipe.modTypeId(), recipe.recipeTypeId(), recipe.magicCosts(),
                    recipe.hasFluidOutputs(),
                    recipe.dynamicIdentityTargetable());
        } catch (Exception ignored) {
            return null;
        }
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
                    new MaterialRef(wanted.kind(), wanted.resourceId(),
                            "", true, false), List.of())) {
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
                } else if ("smithing".equals(recipe.modTypeId())
                        || mode == NbtMatchMode.SLASHBLADE && recipe.output().constrainedNbt()) {
                    RecipeNode specialized = withDemandedOutput(recipe, wanted, mode);
                    if (specialized != null && matchesIngredient(specialized.output(), demand)) {
                        candidates.add(specialized);
                    }
                } else if (recipe.output().runtimeNbt()) {
                    RecipeNode specialized =
                            withRuntimeOutput(recipe, wanted);
                    if (specialized != null
                            && matchesIngredient(specialized.output(), demand)) {
                        candidates.add(specialized);
                    }
                }
            }
        }
        return candidates;
    }

    @Nullable
    private static RecipeNode withRuntimeOutput(
            RecipeNode recipe,
            MaterialRef wanted
    ) {
        if (!recipe.dynamicIdentityTargetable()
                || !recipe.output().runtimeNbt()
                || recipe.output().kind() != wanted.kind()
                || !recipe.output().resourceId().equals(wanted.resourceId())
                || wanted.runtimeNbt()) {
            return null;
        }
        return new RecipeNode(
                recipe.recipeId(),
                wanted,
                recipe.outputCount(),
                recipe.inputs(),
                recipe.modTypeId(),
                recipe.recipeTypeId(),
                recipe.magicCosts(),
                recipe.hasFluidOutputs(),
                recipe.dynamicIdentityTargetable()
        );
    }

    public static IngredientRef projectIngredient(IngredientSpec spec) {
        return projectIngredient(spec.ingredient(), spec.count(), spec.role());
    }

    public static IngredientRef projectIngredient(ScaledIngredientSpec spec) {
        return projectIngredient(spec.ingredient(), spec.count(), spec.role());
    }

    private static IngredientRef projectIngredient(
            Ingredient ingredient, long count, DemandRole role) {
        // Replacement containers still consume one full input per execution; their
        // remainder is emitted by the real crafting executor. Catalysts are retained as
        // non-consuming demands by the immutable planner. Transformed inputs still need
        // typed execution semantics and therefore cannot be projected.
        if (role != DemandRole.CONSUMED
                && role != DemandRole.CONTAINER_RETURNING
                && role != DemandRole.CATALYST) return null;
        if (SlashBladeRecipeHandler.isSlashBladeIngredient(ingredient)) {
            CompoundTag requirement = SlashBladePlanning.requirement(ingredient);
            if (requirement == null) return null;
            Set<MaterialRef> alternatives = new LinkedHashSet<>();
            String nbt = requirement.toString();
            for (ItemStack candidate : ingredient.getItems()) {
                if (!candidate.isEmpty()) alternatives.add(new MaterialRef(
                        BuiltInRegistries.ITEM.getKey(candidate.getItem()), nbt));
            }
            return alternatives.isEmpty() ? null : new IngredientRef(List.copyOf(alternatives),
                    count, NbtMatchMode.SLASHBLADE, role);
        }
        boolean strictNbt = IngredientMatcher.requiresNbt(ingredient);
        NbtMatchMode matchMode = nbtMatchMode(ingredient);
        Set<MaterialRef> alternatives = new LinkedHashSet<>();
        for (ItemStack candidate : ingredient.getItems()) {
            if (!candidate.isEmpty()) alternatives.add(material(candidate, strictNbt));
        }
        return alternatives.isEmpty() ? null
                : new IngredientRef(List.copyOf(alternatives), count, matchMode, role);
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
        if (SlashBladeRecipeHandler.isSlashBladeIngredient(ingredient)
                && SlashBladePlanning.requirement(ingredient) != null) return NbtMatchMode.SLASHBLADE;
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
        String nbt = includeNbt ? CanonicalNbt.exactSnbt(stack.getTag()) : null;
        return new MaterialRef(id, nbt == null ? "" : nbt);
    }

    private record CachedProjection(RecipeManager source, long revision,
                                    ImmutableRecipeGraph graph) {
        private boolean matches(RecipeManager currentSource, long currentRevision) {
            return source == currentSource && revision == currentRevision;
        }
    }
}
