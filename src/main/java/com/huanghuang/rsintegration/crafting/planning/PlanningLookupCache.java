package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.command.PerformanceMonitor;
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

final class PlanningLookupCache {
    static final Limits DEFAULT_LIMITS = new Limits(512, 262_144, 4, 65_536);
    private static final ThreadLocal<PlanningLookupCache> CURRENT = new ThreadLocal<>();
    private static final ParsedNbt EMPTY_NBT = new ParsedNbt(null, true);
    private static final ParsedNbt INVALID_NBT = new ParsedNbt(null, false);

    private final Limits limits;
    private final Map<String, ParsedNbt> parsedTags = new HashMap<>();
    private final Map<ImmutableRecipeGraph, Map<ResourceLocation, List<MaterialRef>>> outputIndexes =
            new IdentityHashMap<>();
    private final Map<ImmutableRecipeGraph, Map<ResourceLocation, List<ImmutableRecipeGraph.RecipeNode>>>
            producerIndexes = new IdentityHashMap<>();
    private final List<PreparedGraph> preparedGraphs = new ArrayList<>();
    private final Map<PreparationStage, PreparationCounters> preparationCounters =
            new EnumMap<>(PreparationStage.class);
    private int retainedPreparationUnits;
    private int cachedNbtCharacters;
    private int indexedVariants;
    private long nbtParses;
    private long nbtCacheHits;
    private long outputIndexBuilds;
    private long outputScans;
    private long candidateVariants;

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
                cache.cachedNbtCharacters, cache.outputIndexes.size(), cache.indexedVariants);
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
                                                 Map<MaterialRef, Integer> available,
                                                 Supplier<ImmutableRecipeGraph> work) {
        PlanningLookupCache cache = CURRENT.get();
        return cache == null ? work.get() : cache.prepare(stage, graph, available, work);
    }

    private ImmutableRecipeGraph prepare(PreparationStage stage, ImmutableRecipeGraph graph,
                                          Map<MaterialRef, Integer> available,
                                          Supplier<ImmutableRecipeGraph> work) {
        PlanningThreadContext.throwIfCancelled();
        PreparationCounters counters = preparationCounters.computeIfAbsent(
                stage, ignored -> new PreparationCounters());
        long started = System.nanoTime();
        try {
            for (PreparedGraph prepared : preparedGraphs) {
                if (prepared.stage() == stage && prepared.source() == graph
                        && matchesAvailability(prepared.available(), available)) {
                    counters.hits++;
                    return prepared.result();
                }
            }
            counters.builds++;
            List<AvailableEntry> captured = null;
            if (preparedGraphs.size() < limits.maxGraphs()
                    && available.size() <= limits.maxOutputVariants() - retainedPreparationUnits) {
                captured = available.entrySet().stream()
                        .map(entry -> new AvailableEntry(entry.getKey(), entry.getValue())).toList();
            }
            ImmutableRecipeGraph result = work.get();
            long retainedUnits = (long) available.size() + result.recipesByOutput().size();
            if (captured != null && preparedGraphs.size() < limits.maxGraphs()
                    && retainedUnits <= limits.maxOutputVariants() - retainedPreparationUnits
                    && matchesAvailability(captured, available)) {
                preparedGraphs.add(new PreparedGraph(stage, graph, captured, result));
                retainedPreparationUnits += (int) retainedUnits;
            }
            return result;
        } finally {
            long elapsed = System.nanoTime() - started;
            counters.elapsedNanos += elapsed;
            counters.maxNanos = Math.max(counters.maxNanos, elapsed);
        }
    }

    private static boolean matchesAvailability(List<AvailableEntry> captured,
                                                Map<MaterialRef, Integer> available) {
        if (captured.size() != available.size()) return false;
        var entries = available.entrySet().iterator();
        for (AvailableEntry previous : captured) {
            if (!entries.hasNext()) return false;
            var current = entries.next();
            if (!Objects.equals(previous.material(), current.getKey())
                    || !Objects.equals(previous.count(), current.getValue())) return false;
        }
        return !entries.hasNext();
    }

    static boolean matchesNbt(String expectedSnbt, String actualSnbt, boolean partial) {
        if (!partial && Objects.equals(expectedSnbt, actualSnbt)) return true;
        PlanningLookupCache cache = CURRENT.get();
        ParsedNbt expected = cache == null ? parseUncached(expectedSnbt) : cache.parse(expectedSnbt);
        ParsedNbt actual = cache == null ? parseUncached(actualSnbt) : cache.parse(actualSnbt);
        return expected.valid() && actual.valid()
                && IngredientMatcher.nbtMatches(expected.tag(), actual.tag(), partial);
    }

    private ParsedNbt parse(String snbt) {
        if (snbt == null || snbt.isBlank()) return EMPTY_NBT;
        ParsedNbt cached = parsedTags.get(snbt);
        if (cached != null) {
            nbtCacheHits++;
            return cached;
        }
        nbtParses++;
        ParsedNbt parsed = parseUncached(snbt);
        if (parsedTags.size() < limits.maxTags()
                && snbt.length() <= limits.maxNbtCharacters() - cachedNbtCharacters) {
            parsedTags.put(snbt, parsed);
            cachedNbtCharacters += snbt.length();
        }
        return parsed;
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
        PlanningLookupCache cache = CURRENT.get();
        if (cache == null) {
            List<ImmutableRecipeGraph.RecipeNode> result = new ArrayList<>();
            for (List<ImmutableRecipeGraph.RecipeNode> nodes : graph.recipesByOutput().values()) {
                for (ImmutableRecipeGraph.RecipeNode node : nodes) {
                    if (node.output().itemId().equals(itemId)) result.add(node);
                }
            }
            return List.copyOf(result);
        }
        Map<ResourceLocation, List<ImmutableRecipeGraph.RecipeNode>> indexed =
                cache.producerIndexes.get(graph);
        if (indexed == null) {
            Map<ResourceLocation, List<ImmutableRecipeGraph.RecipeNode>> building = new HashMap<>();
            for (List<ImmutableRecipeGraph.RecipeNode> nodes : graph.recipesByOutput().values()) {
                PlanningThreadContext.throwIfCancelled();
                for (ImmutableRecipeGraph.RecipeNode node : nodes) {
                    building.computeIfAbsent(node.output().itemId(), ignored -> new ArrayList<>())
                            .add(node);
                }
            }
            building.replaceAll((ignored, nodes) -> List.copyOf(nodes));
            indexed = Map.copyOf(building);
            cache.producerIndexes.put(graph, indexed);
        }
        return indexed.getOrDefault(itemId, List.of());
    }

    private List<MaterialRef> findOutputs(ImmutableRecipeGraph graph, ResourceLocation itemId) {
        Map<ResourceLocation, List<MaterialRef>> indexed = outputIndexes.get(graph);
        if (indexed == null && outputIndexes.size() < limits.maxGraphs()
                && graph.recipesByOutput().size() <= limits.maxOutputVariants() - indexedVariants) {
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
        if (indexed == null) {
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
        for (MaterialRef output : graph.recipesByOutput().keySet()) {
            if (output.itemId().equals(itemId)) selected.add(output);
        }
        return List.copyOf(selected);
    }

    record Limits(int maxTags, int maxNbtCharacters, int maxGraphs, int maxOutputVariants) {
        Limits {
            if (maxTags < 0 || maxNbtCharacters < 0 || maxGraphs < 0 || maxOutputVariants < 0) {
                throw new IllegalArgumentException("negative lookup cache capacity");
            }
        }
    }

    record Stats(long nbtParses, long nbtCacheHits, long outputIndexBuilds,
                 long outputScans, long candidateVariants, int cachedTags,
                 int cachedNbtCharacters, int indexedGraphs, int indexedVariants) {}

    private record ParsedNbt(CompoundTag tag, boolean valid) {}

    enum PreparationStage { INVENTORY, SMITHING }

    record PreparationStats(long builds, long hits, long elapsedNanos, long maxNanos,
                            int retainedGraphs, int retainedUnits) {}

    private record AvailableEntry(MaterialRef material, Integer count) {}

    private record PreparedGraph(PreparationStage stage, ImmutableRecipeGraph source,
                                  List<AvailableEntry> available, ImmutableRecipeGraph result) {}

    private static final class PreparationCounters {
        private long builds;
        private long hits;
        private long elapsedNanos;
        private long maxNanos;
    }
}
