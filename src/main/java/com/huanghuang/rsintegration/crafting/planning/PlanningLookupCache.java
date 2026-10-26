package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.mods.slashblade.SlashBladePlanning;

import com.huanghuang.rsintegration.util.PerformanceMonitor;
import com.huanghuang.rsintegration.crafting.IngredientMatcher;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.MaterialRef;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.concurrent.ConcurrentHashMap;

final class PlanningLookupCache {
    private static final int COMPACT_INDEX_THRESHOLD = 4_096;
    // Large networks can contain thousands of distinct NBT variants. Keeping only the
    // first 512 tags makes later variants fall back to TagParser on every comparison.
    // This cache is scoped to one request and is released after planning.
    static final Limits DEFAULT_LIMITS = new Limits(8_192, 4 * 1024 * 1024, 4, 65_536);
    private static volatile SharedNbtCache sharedNbtCache;
    private static final ThreadLocal<PlanningLookupCache> CURRENT = new ThreadLocal<>();
    private static final ParsedNbt EMPTY_NBT = new ParsedNbt(null, true);
    private static final ParsedNbt INVALID_NBT = new ParsedNbt(null, false);

    private final Limits limits;
    private final Map<String, ParsedNbt> parsedTags = new HashMap<>();
    private final Map<ImmutableRecipeGraph, Map<ResourceLocation, List<MaterialRef>>> outputIndexes =
            new IdentityHashMap<>();
    private final Map<ImmutableRecipeGraph, CompactOutputIndex> compactOutputIndexes =
            new IdentityHashMap<>();
    private final Map<ImmutableRecipeGraph, RecipeProducerIndex>
            producerIndexes = new IdentityHashMap<>();
    private final Map<ImmutableRecipeGraph.IngredientRef, Map<MaterialRef, Boolean>> ingredientMatches =
            new IdentityHashMap<>();
    private final List<PreparedGraph> preparedGraphs = new ArrayList<>();
    private final Map<PreparationStage, PreparationCounters> preparationCounters =
            new EnumMap<>(PreparationStage.class);
    private int retainedPreparationUnits;
    private int cachedNbtCharacters;
    private int indexedVariants;
    private long compactIndexBytes;
    private int cachedIngredientMatches;
    private long nbtParses;
    private long nbtCacheHits;
    private long outputIndexBuilds;
    private long outputScans;
    private long candidateVariants;
    private long reachabilityBuilds;
    private long reachabilitySeedCandidates;
    private long reachabilitySeeds;
    private long reachabilityIndexedEdges;
    private long reachabilityMatchedEdges;
    private long reachabilityReachedRecipes;
    private long reachabilityStateAllocations;
    private long reachabilityNanos;
    private long reachabilityMaxNanos;

    private PlanningLookupCache(Limits limits) {
        this.limits = Objects.requireNonNull(limits);
    }

    static <T> T run(Supplier<T> work) {
        return run(DEFAULT_LIMITS, work);
    }

    static <T> T run(Limits limits, Supplier<T> work) {
        if (CURRENT.get() != null) return work.get();
        PlanningLookupCache cache = new PlanningLookupCache(limits);
        CURRENT.set(cache);
        try {
            return work.get();
        } finally {
            CURRENT.remove();
            PerformanceMonitor.recordPlanningLookups(cache.nbtParses, cache.nbtCacheHits,
                    cache.outputIndexBuilds, cache.outputScans, cache.candidateVariants);
            cache.preparationCounters.forEach((stage, counters) ->
                    PerformanceMonitor.recordPlanningPreparation(stage == PreparationStage.INVENTORY,
                            counters.builds, counters.hits, counters.elapsedNanos, counters.maxNanos));
        }
    }

    static boolean isActive() {
        return CURRENT.get() != null;
    }

    static Stats currentStats() {
        PlanningLookupCache cache = Objects.requireNonNull(CURRENT.get(), "no planning lookup scope");
        return new Stats(cache.nbtParses, cache.nbtCacheHits, cache.outputIndexBuilds,
                cache.outputScans, cache.candidateVariants, cache.parsedTags.size(),
                cache.cachedNbtCharacters,
                cache.outputIndexes.size() + cache.compactOutputIndexes.size(), cache.indexedVariants,
                cache.compactIndexBytes, cache.reachabilityBuilds,
                cache.reachabilitySeedCandidates, cache.reachabilitySeeds,
                cache.reachabilityIndexedEdges, cache.reachabilityMatchedEdges,
                cache.reachabilityReachedRecipes, cache.reachabilityStateAllocations,
                cache.reachabilityNanos, cache.reachabilityMaxNanos);
    }

    static void recordReachability(long seedCandidates, long seeds, long indexedEdges,
                                   long matchedEdges, long reachedRecipes,
                                   long stateAllocations, long elapsedNanos) {
        PlanningLookupCache cache = CURRENT.get();
        if (cache == null) return;
        long elapsed = Math.max(0L, elapsedNanos);
        cache.reachabilityBuilds++;
        cache.reachabilitySeedCandidates += Math.max(0L, seedCandidates);
        cache.reachabilitySeeds += Math.max(0L, seeds);
        cache.reachabilityIndexedEdges += Math.max(0L, indexedEdges);
        cache.reachabilityMatchedEdges += Math.max(0L, matchedEdges);
        cache.reachabilityReachedRecipes += Math.max(0L, reachedRecipes);
        cache.reachabilityStateAllocations += Math.max(0L, stateAllocations);
        cache.reachabilityNanos += elapsed;
        cache.reachabilityMaxNanos = Math.max(cache.reachabilityMaxNanos, elapsed);
    }

    static PreparationStats preparationStats(PreparationStage stage) {
        PlanningLookupCache cache = Objects.requireNonNull(CURRENT.get(), "no planning lookup scope");
        PreparationCounters counters = cache.preparationCounters.get(stage);
        return new PreparationStats(counters == null ? 0 : counters.builds,
                counters == null ? 0 : counters.hits,
                counters == null ? 0 : counters.elapsedNanos,
                counters == null ? 0 : counters.maxNanos,
                cache.preparedGraphs.size(), cache.retainedPreparationUnits);
    }

    static ImmutableRecipeGraph reusePreparation(PreparationStage stage,
                                                 ImmutableRecipeGraph graph,
                                                 Map<MaterialRef, ? extends Number> supplied,
                                                 Supplier<ImmutableRecipeGraph> work) {
        return reusePreparation(stage, graph, supplied, null, work);
    }

    static ImmutableRecipeGraph reusePreparation(PreparationStage stage,
                                                 ImmutableRecipeGraph graph,
                                                 Map<MaterialRef, ? extends Number> supplied,
                                                 Object discriminator,
                                                 Supplier<ImmutableRecipeGraph> work) {
        PlanningLookupCache cache = CURRENT.get();
        return cache == null ? work.get()
                : cache.prepare(stage, graph, supplied, discriminator, work);
    }

    private ImmutableRecipeGraph prepare(PreparationStage stage, ImmutableRecipeGraph graph,
                                          Map<MaterialRef, ? extends Number> available,
                                          Object discriminator,
                                          Supplier<ImmutableRecipeGraph> work) {
        PlanningThreadContext.throwIfCancelled();
        PreparationCounters counters = preparationCounters.computeIfAbsent(
                stage, ignored -> new PreparationCounters());
        long started = System.nanoTime();
        try {
            List<AvailableEntry> current = snapshotAvailability(available);
            for (PreparedGraph prepared : preparedGraphs) {
                if (prepared.stage() == stage && prepared.source() == graph
                        && Objects.equals(prepared.discriminator(), discriminator)
                        && prepared.available().equals(current)) {
                    counters.hits++;
                    return prepared.result();
                }
            }
            counters.builds++;
            List<AvailableEntry> captured = null;
            if (preparedGraphs.size() < limits.maxGraphs()
                    && current.size() <= limits.maxOutputVariants() - retainedPreparationUnits) {
                captured = current;
            }
            ImmutableRecipeGraph result = work.get();
            long retainedUnits = (long) current.size() + result.recipesByOutput().size();
            if (captured != null && preparedGraphs.size() < limits.maxGraphs()
                    && retainedUnits <= limits.maxOutputVariants() - retainedPreparationUnits
                    && captured.equals(snapshotAvailability(available))) {
                preparedGraphs.add(new PreparedGraph(
                        stage, graph, captured, discriminator, result));
                retainedPreparationUnits += (int) retainedUnits;
            }
            return result;
        } finally {
            long elapsed = System.nanoTime() - started;
            counters.elapsedNanos += elapsed;
            counters.maxNanos = Math.max(counters.maxNanos, elapsed);
        }
    }

    private static List<AvailableEntry> snapshotAvailability(
            Map<MaterialRef, ? extends Number> available) {
        return available.entrySet().stream()
                .filter(entry -> entry.getKey() != null && entry.getValue() != null
                        && entry.getValue().longValue() > 0L)
                .map(entry -> new AvailableEntry(entry.getKey(), entry.getValue().longValue()))
                .toList();
    }

    static boolean matchesNbt(String expectedSnbt, String actualSnbt, boolean partial) {
        if (!partial && Objects.equals(expectedSnbt, actualSnbt)) return true;
        PlanningLookupCache cache = CURRENT.get();
        ParsedNbt expected = cache == null ? parseUncached(expectedSnbt) : cache.parse(expectedSnbt);
        ParsedNbt actual = cache == null ? parseUncached(actualSnbt) : cache.parse(actualSnbt);
        return expected.valid() && actual.valid()
                && IngredientMatcher.nbtMatches(expected.tag(), actual.tag(), partial);
    }

    static boolean matchesSlashBlade(String expectedSnbt, String actualSnbt) {
        return SlashBladePlanning.matches(readOnlyTag(expectedSnbt), readOnlyTag(actualSnbt));
    }

    /** 返回共享只读值；任何调用方需要修改时必须先 copy。 */
    static CompoundTag readOnlyTag(String snbt) {
        PlanningLookupCache cache = CURRENT.get();
        ParsedNbt parsed = cache == null ? parseUncached(snbt) : cache.parse(snbt);
        return parsed.valid() ? parsed.tag() : null;
    }

    static Boolean lookupIngredientMatch(MaterialRef actual,
                                         ImmutableRecipeGraph.IngredientRef ingredient) {
        PlanningLookupCache cache = CURRENT.get();
        if (cache == null) return null;
        Map<MaterialRef, Boolean> matches = cache.ingredientMatches.get(ingredient);
        return matches == null ? null : matches.get(actual);
    }

    static void recordIngredientMatch(MaterialRef actual,
                                      ImmutableRecipeGraph.IngredientRef ingredient,
                                      boolean result) {
        PlanningLookupCache cache = CURRENT.get();
        if (cache == null || cache.cachedIngredientMatches >= cache.limits.maxOutputVariants()) return;
        Map<MaterialRef, Boolean> matches = cache.ingredientMatches.computeIfAbsent(
                ingredient, ignored -> new HashMap<>());
        if (matches.putIfAbsent(actual, result) == null) cache.cachedIngredientMatches++;
    }

    private ParsedNbt parse(String snbt) {
        if (snbt == null || snbt.isBlank()) return EMPTY_NBT;
        ParsedNbt cached = parsedTags.get(snbt);
        if (cached != null) {
            nbtCacheHits++;
            return cached;
        }
        SharedNbtCache shared = sharedNbtCache;
        if (shared != null) {
            cached = shared.get(snbt);
            if (cached != null) {
                nbtCacheHits++;
                retainLocally(snbt, cached);
                return cached;
            }
        }
        nbtParses++;
        ParsedNbt parsed = parseUncached(snbt);
        if (shared != null) parsed = shared.retain(snbt, parsed);
        retainLocally(snbt, parsed);
        return parsed;
    }

    private void retainLocally(String snbt, ParsedNbt parsed) {
        if (parsedTags.size() < limits.maxTags()
                && snbt.length() <= limits.maxNbtCharacters() - cachedNbtCharacters
                && parsedTags.putIfAbsent(snbt, parsed) == null) {
            cachedNbtCharacters += snbt.length();
        }
    }

    /** Replaces generation-scoped immutable NBT parsing state after a recipe rebuild. */
    static void replaceSharedNbtCache(ImmutableRecipeGraph graph) {
        publishSharedNbtCache(prepareSharedNbtCache(graph));
    }

    static SharedNbtCache prepareSharedNbtCache(ImmutableRecipeGraph graph) {
        SharedNbtCache replacement = new SharedNbtCache();
        if (graph != null) {
            for (ImmutableRecipeGraph.RecipeNode recipe : graph.recipesById().values()) {
                retainSharedTag(replacement, recipe.output().nbt());
                for (ImmutableRecipeGraph.IngredientRef ingredient : recipe.inputs()) {
                    for (MaterialRef alternative : ingredient.alternatives()) {
                        retainSharedTag(replacement, alternative.nbt());
                    }
                }
            }
        }
        return replacement;
    }

    static void publishSharedNbtCache(SharedNbtCache prepared) {
        sharedNbtCache = prepared;
    }

    static void clearSharedNbtCache() {
        sharedNbtCache = null;
    }

    private static void retainSharedTag(SharedNbtCache cache, String snbt) {
        if (snbt == null || snbt.isBlank() || cache.get(snbt) != null) return;
        cache.retain(snbt, parseUncached(snbt));
    }

    private static ParsedNbt parseUncached(String snbt) {
        if (snbt == null || snbt.isBlank()) return EMPTY_NBT;
        try {
            return new ParsedNbt(TagParser.parseTag(snbt), true);
        } catch (Exception ignored) {
            return INVALID_NBT;
        }
    }

    static List<MaterialRef> outputVariants(ImmutableRecipeGraph graph, ResourceLocation itemId) {
        List<MaterialRef> published = ImmutableRecipeGraphProjector.publishedOutputVariants(graph, itemId);
        if (published != null) return published;
        PlanningLookupCache cache = CURRENT.get();
        return cache == null ? scanOutputs(graph, itemId) : cache.findOutputs(graph, itemId);
    }

    static List<ImmutableRecipeGraph.RecipeNode> producers(
            ImmutableRecipeGraph graph, ResourceLocation itemId) {
        List<ImmutableRecipeGraph.RecipeNode> published =
                ImmutableRecipeGraphProjector.publishedProducers(graph, itemId);
        if (published != null) return published;
        if (CURRENT.get() == null) {
            List<ImmutableRecipeGraph.RecipeNode> selected = new ArrayList<>();
            for (List<ImmutableRecipeGraph.RecipeNode> nodes : graph.recipesByOutput().values()) {
                PlanningThreadContext.throwIfCancelled();
                for (ImmutableRecipeGraph.RecipeNode node : nodes) {
                    if (node.output().itemId().equals(itemId)) selected.add(node);
                }
            }
            return List.copyOf(selected);
        }
        return producerIndex(graph).producers(itemId);
    }

    static List<MaterialRef> recipeOutputs(ImmutableRecipeGraph graph, ResourceLocation recipeId) {
        List<MaterialRef> published = ImmutableRecipeGraphProjector.publishedRecipeOutputs(graph, recipeId);
        return published != null ? published : producerIndex(graph).outputs(graph, recipeId);
    }

    private static RecipeProducerIndex producerIndex(ImmutableRecipeGraph graph) {
        PlanningLookupCache cache = CURRENT.get();
        return cache == null ? RecipeProducerIndex.build(graph)
                : cache.producerIndexes.computeIfAbsent(graph, RecipeProducerIndex::build);
    }

    private List<MaterialRef> findOutputs(ImmutableRecipeGraph graph, ResourceLocation itemId) {
        PlanningThreadContext.throwIfCancelled();
        Map<ResourceLocation, List<MaterialRef>> indexed = outputIndexes.get(graph);
        CompactOutputIndex compact = compactOutputIndexes.get(graph);
        boolean canRetainGraph = outputIndexes.size() + compactOutputIndexes.size() < limits.maxGraphs();
        int outputCount = graph.recipesByOutput().size();
        // 大图优先用数组，避免刚好低于旧阈值时反而分配数万个桶对象。
        if (indexed == null && compact == null && canRetainGraph
                && (outputCount >= COMPACT_INDEX_THRESHOLD
                || outputCount > limits.maxOutputVariants() - indexedVariants)) {
            long bytes = CompactOutputIndex.estimatedBytes(outputCount);
            if (bytes <= limits.maxCompactIndexBytes() - compactIndexBytes) {
                compact = new CompactOutputIndex(graph.recipesByOutput().keySet());
                compactOutputIndexes.put(graph, compact);
                compactIndexBytes += bytes;
                outputScans += outputCount;
                outputIndexBuilds++;
            }
        }
        if (indexed == null && compact == null && canRetainGraph
                && outputCount <= limits.maxOutputVariants() - indexedVariants) {
            Map<ResourceLocation, List<MaterialRef>> building = new HashMap<>();
            for (MaterialRef output : graph.recipesByOutput().keySet()) {
                PlanningThreadContext.throwIfCancelled();
                outputScans++;
                building.computeIfAbsent(output.itemId(), ignored -> new ArrayList<>()).add(output);
            }
            building.replaceAll((ignored, variants) -> List.copyOf(variants));
            indexed = Map.copyOf(building);
            outputIndexes.put(graph, indexed);
            indexedVariants += graph.recipesByOutput().size();
            outputIndexBuilds++;
        }
        List<MaterialRef> selected;
        if (compact != null) {
            selected = compact.find(itemId);
        } else if (indexed == null) {
            outputScans += graph.recipesByOutput().size();
            selected = scanOutputs(graph, itemId);
        } else {
            selected = indexed.getOrDefault(itemId, List.of());
        }
        candidateVariants += selected.size();
        return selected;
    }

    private static List<MaterialRef> scanOutputs(ImmutableRecipeGraph graph, ResourceLocation itemId) {
        List<MaterialRef> selected = new ArrayList<>();
        int inspected = 0;
        for (MaterialRef output : graph.recipesByOutput().keySet()) {
            if ((inspected++ & 1023) == 0) PlanningThreadContext.throwIfCancelled();
            if (output.itemId().equals(itemId)) selected.add(output);
        }
        return List.copyOf(selected);
    }

    record Limits(int maxTags, int maxNbtCharacters, int maxGraphs, int maxOutputVariants) {
        // 默认额外使用最多 2MiB 数组索引；多张派生图共用额度，关闭缓存时额度也为零。
        long maxCompactIndexBytes() {
            return Math.min(8L * 1024 * 1024, 32L * maxOutputVariants);
        }

        Limits {
            if (maxTags < 0 || maxNbtCharacters < 0 || maxGraphs < 0 || maxOutputVariants < 0) {
                throw new IllegalArgumentException("negative lookup cache capacity");
            }
        }
    }

    record Stats(long nbtParses, long nbtCacheHits, long outputIndexBuilds,
                 long outputScans, long candidateVariants, int cachedTags,
                 int cachedNbtCharacters, int indexedGraphs, int indexedVariants,
                 long compactIndexBytes, long reachabilityBuilds,
                 long reachabilitySeedCandidates, long reachabilitySeeds,
                 long reachabilityIndexedEdges, long reachabilityMatchedEdges,
                 long reachabilityReachedRecipes, long reachabilityStateAllocations,
                 long reachabilityNanos, long reachabilityMaxNanos) {}

    private record ParsedNbt(CompoundTag tag, boolean valid) {}

    /** Atomically replaced on recipe reload; old requests may safely finish on the old instance. */
    static final class SharedNbtCache {
        private final Map<String, ParsedNbt> tags = new ConcurrentHashMap<>();
        private int retainedCharacters;

        private ParsedNbt get(String snbt) {
            return tags.get(snbt);
        }

        private synchronized ParsedNbt retain(String snbt, ParsedNbt parsed) {
            ParsedNbt existing = tags.get(snbt);
            if (existing != null) return existing;
            if (tags.size() >= DEFAULT_LIMITS.maxTags()
                    || snbt.length() > DEFAULT_LIMITS.maxNbtCharacters() - retainedCharacters) {
                return parsed;
            }
            tags.put(snbt, parsed);
            retainedCharacters += snbt.length();
            return parsed;
        }
    }

    enum PreparationStage { INVENTORY, SMITHING }

    record PreparationStats(long builds, long hits, long elapsedNanos, long maxNanos,
                            int retainedGraphs, int retainedUnits) {}

    private record AvailableEntry(MaterialRef material, Long count) {}

    private record PreparedGraph(PreparationStage stage, ImmutableRecipeGraph source,
                                  List<AvailableEntry> available, Object discriminator,
                                  ImmutableRecipeGraph result) {}

    private static final class PreparationCounters {
        private long builds;
        private long hits;
        private long elapsedNanos;
        private long maxNanos;
    }
}
