package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.crafting.SelfAmplifyingRecipePolicy;
import com.huanghuang.rsintegration.config.CraftingPlanningConfig;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.IngredientRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.MaterialRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.RecipeNode;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bounded inventory-aware check that pure planning covers the requested demand tree. */
public final class PureDemandTreeInspector {
    private PureDemandTreeInspector() {}

    public static Result inspect(ImmutableRecipeGraph graph,
                                 Map<MaterialRef, Integer> available,
                                 ResourceLocation targetRecipeId,
                                 int repeatCount) {
        return inspect(graph, available, targetRecipeId, repeatCount,
                CraftingPlanningConfig.DEFAULT_DEMAND_TREE_NODES);
    }

    public static Result inspect(ImmutableRecipeGraph graph,
                                 Map<MaterialRef, Integer> available,
                                 ResourceLocation targetRecipeId,
                                 int repeatCount,
                                 int maxNodes) {
        return inspect(graph, available, targetRecipeId, repeatCount, maxNodes, Set.of());
    }

    public static Result inspect(ImmutableRecipeGraph graph,
                                 Map<MaterialRef, Integer> available,
                                 ResourceLocation targetRecipeId,
                                 int repeatCount,
                                 int maxNodes,
                                 Set<ResourceLocation> reusableCatalystOutputIds) {
        return inspect(graph, available, targetRecipeId, repeatCount, maxNodes,
                reusableCatalystOutputIds, Set.of());
    }

    public static Result inspect(ImmutableRecipeGraph graph,
                                 Map<MaterialRef, Integer> available,
                                 ResourceLocation targetRecipeId,
                                 int repeatCount,
                                 int maxNodes,
                                 Set<ResourceLocation> reusableCatalystOutputIds,
                                 Set<ResourceLocation> reusableCatalystRecipeIds) {
        boolean targetUsesReusableCatalyst = reusableCatalystRecipeIds != null
                && reusableCatalystRecipeIds.contains(targetRecipeId);
        RecipeNode target = graph.recipesById().get(targetRecipeId);
        if (target == null) {
            return new Result(Status.TARGET_NOT_PROJECTED, 0, null,
                    targetUsesReusableCatalyst);
        }

        Walker walker = new Walker(graph, available, Math.max(1, maxNodes),
                reusableCatalystOutputIds);
        walker.catalystRouteAvailable = targetUsesReusableCatalyst;
        for (IngredientRef input : PureDemandNormalizer.mergeEquivalent(
                SelfAmplifyingRecipePolicy.scaleTargetInputs(target, repeatCount))) {
            if (!walker.coverIngredient(input)) {
                Status status = walker.nodeLimitReached ? Status.NODE_LIMIT : Status.INCOMPLETE;
                return new Result(status, walker.visitedNodes, walker.firstUnresolved,
                        walker.catalystRouteAvailable);
            }
        }
        return new Result(Status.COMPLETE, walker.visitedNodes, null,
                walker.catalystRouteAvailable);
    }

    private static MaterialRef first(IngredientRef ingredient) {
        return ingredient.alternatives().isEmpty() ? null : ingredient.alternatives().get(0);
    }

    public enum Status {
        COMPLETE,
        TARGET_NOT_PROJECTED,
        INCOMPLETE,
        NODE_LIMIT
    }

    public record Result(Status status, int visitedNodes, @Nullable MaterialRef unresolved,
                         boolean catalystRouteAvailable) {
        public boolean complete() {
            return status == Status.COMPLETE;
        }
    }

    private static final class Walker {
        private final ImmutableRecipeGraph graph;
        private final Ledger ledger;
        private final Set<MaterialRef> visiting = new HashSet<>();
        private final Set<ResourceLocation> reusableCatalystOutputIds;
        private final int maxNodes;
        private int visitedNodes;
        private boolean nodeLimitReached;
        private boolean catalystRouteAvailable;
        private MaterialRef firstUnresolved;

        private Walker(ImmutableRecipeGraph graph, Map<MaterialRef, Integer> available,
                       int maxNodes, Set<ResourceLocation> reusableCatalystOutputIds) {
            this.graph = graph;
            this.ledger = new Ledger(available);
            this.maxNodes = maxNodes;
            this.reusableCatalystOutputIds = reusableCatalystOutputIds == null
                    ? Set.of() : reusableCatalystOutputIds;
        }

        private boolean coverIngredient(IngredientRef ingredient) {
            noteCatalystOpportunity(ingredient);
            int mark = ledger.mark();
            if (consumeAcrossAlternatives(ingredient) == 0) return true;

            for (MaterialRef alternative : inventoryFirst(ingredient.alternatives())) {
                ledger.rollback(mark);
                int remaining = consumeMatching(alternative, ingredient.count());
                if (coverMaterial(alternative, remaining)) return true;
                if (nodeLimitReached) break;
            }
            ledger.rollback(mark);
            if (firstUnresolved == null) firstUnresolved = first(ingredient);
            return false;
        }

        private int consumeAcrossAlternatives(IngredientRef ingredient) {
            int mark = ledger.mark();
            int remaining = ingredient.count();
            for (MaterialRef stocked : ledger.order()) {
                int available = ledger.count(stocked);
                if (available <= 0 || !matchesAny(stocked, ingredient.alternatives())) continue;
                int take = Math.min(available, remaining);
                ledger.set(stocked, available - take);
                remaining -= take;
                if (remaining == 0) break;
            }
            if (remaining > 0) ledger.rollback(mark);
            return remaining;
        }

        private int consumeMatching(MaterialRef requested, int count) {
            int remaining = count;
            for (MaterialRef stocked : ledger.byItem(requested.itemId())) {
                int available = ledger.count(stocked);
                if (available <= 0 || !matches(stocked, requested)) continue;
                int take = Math.min(available, remaining);
                ledger.set(stocked, available - take);
                remaining -= take;
                if (remaining == 0) break;
            }
            return remaining;
        }

        private boolean coverMaterial(MaterialRef material, int count) {
            if (count <= 0) return true;
            if (nodeLimitReached || visiting.contains(material)) return false;
            if (++visitedNodes > maxNodes) {
                nodeLimitReached = true;
                return false;
            }

            List<RecipeNode> candidates = graph.recipesByOutput().getOrDefault(material, List.of());
            if (candidates.isEmpty()) {
                if (firstUnresolved == null) firstUnresolved = material;
                return false;
            }

            int mark = ledger.mark();
            visiting.add(material);
            try {
                noteCatalystAlternatives(candidates, count);
                for (RecipeNode candidate : inventoryFirstCandidates(candidates)) {
                    ledger.rollback(mark);
                    long batches = ((long) count + candidate.outputCount() - 1L)
                            / candidate.outputCount();
                    if (batches <= 0L || batches > Integer.MAX_VALUE) continue;
                    if (isUnseededReverseConversion(candidate, material, (int) batches)) continue;

                    boolean covered = true;
                    for (IngredientRef input : PureDemandNormalizer.mergeEquivalent(
                            candidate.inputs())) {
                        long scaled = (long) input.count() * batches;
                        if (scaled > Integer.MAX_VALUE
                                || !coverIngredient(new IngredientRef(
                                input.alternatives(), (int) scaled))) {
                            covered = false;
                            break;
                        }
                    }
                    if (covered) return true;
                    if (nodeLimitReached) break;
                }
            } finally {
                visiting.remove(material);
            }
            ledger.rollback(mark);
            return false;
        }

        private List<MaterialRef> inventoryFirst(List<MaterialRef> alternatives) {
            if (alternatives.size() < 2) return alternatives;
            List<MaterialRef> ordered = new ArrayList<>(alternatives.size());
            for (MaterialRef material : alternatives) {
                if (ledger.count(material) > 0) ordered.add(material);
            }
            if (ordered.isEmpty() || ordered.size() == alternatives.size()) return alternatives;
            for (MaterialRef material : alternatives) {
                if (ledger.count(material) <= 0) ordered.add(material);
            }
            return ordered;
        }

        private List<RecipeNode> inventoryFirstCandidates(List<RecipeNode> candidates) {
            if (candidates.size() < 2) return candidates;
            List<RecipeNode> ordered = new ArrayList<>(candidates);
            ordered.sort(java.util.Comparator.comparingDouble(this::inputStockCoverage).reversed());
            return ordered;
        }

        private double inputStockCoverage(RecipeNode candidate) {
            long required = 0L;
            long covered = 0L;
            for (IngredientRef input : PureDemandNormalizer.mergeEquivalent(candidate.inputs())) {
                required += input.count();
                covered += Math.min(input.count(), ledger.countAcrossAlternatives(input));
            }
            return required <= 0L ? 1.0D : (double) covered / (double) required;
        }

        private boolean isUnseededReverseConversion(RecipeNode candidate, MaterialRef wanted,
                                                    int batches) {
            for (IngredientRef input : PureDemandNormalizer.mergeEquivalent(candidate.inputs())) {
                long required = (long) input.count() * batches;
                if (required <= ledger.countAcrossAlternatives(input)) continue;
                boolean sawProducer = false;
                boolean reverseOnly = true;
                for (MaterialRef alternative : input.alternatives()) {
                    List<RecipeNode> producers = graph.recipesByOutput()
                            .getOrDefault(alternative, List.of());
                    if (producers.isEmpty()) {
                        reverseOnly = false;
                        break;
                    }
                    sawProducer = true;
                    if (producers.stream().anyMatch(producer ->
                            producer.inputs().stream().noneMatch(ingredient ->
                                    ingredient.alternatives().contains(wanted)))) {
                        reverseOnly = false;
                        break;
                    }
                }
                if (sawProducer && reverseOnly) return true;
            }
            return false;
        }

        private void noteCatalystAlternatives(List<RecipeNode> candidates, int count) {
            if (reusableCatalystOutputIds.isEmpty()) return;
            for (RecipeNode candidate : candidates) {
                long batches = ((long) count + candidate.outputCount() - 1L)
                        / candidate.outputCount();
                if (batches <= 0L || batches > Integer.MAX_VALUE) continue;

                int mark = ledger.mark();
                try {
                    for (IngredientRef input : candidate.inputs()) {
                        long scaled = (long) input.count() * batches;
                        if (scaled > Integer.MAX_VALUE) break;
                        IngredientRef demand = new IngredientRef(input.alternatives(), (int) scaled);
                        int remaining = consumeAcrossAlternatives(demand);
                        if (remaining > 0 && containsReusableCatalystOutput(demand)) {
                            catalystRouteAvailable = true;
                            return;
                        }
                    }
                } finally {
                    ledger.rollback(mark);
                }
            }
        }

        private void noteCatalystOpportunity(IngredientRef ingredient) {
            if (catalystRouteAvailable || reusableCatalystOutputIds.isEmpty()) return;
            if (ledger.countAcrossAlternatives(ingredient) < ingredient.count()
                    && containsReusableCatalystOutput(ingredient)) {
                catalystRouteAvailable = true;
            }
        }

        private boolean containsReusableCatalystOutput(IngredientRef ingredient) {
            return ingredient.alternatives().stream()
                    .anyMatch(material -> reusableCatalystOutputIds.contains(material.itemId()));
        }

        private static boolean matchesAny(MaterialRef stocked, List<MaterialRef> alternatives) {
            for (MaterialRef requested : alternatives) {
                if (matches(stocked, requested)) return true;
            }
            return false;
        }

        private static boolean matches(MaterialRef stocked, MaterialRef requested) {
            return stocked.itemId().equals(requested.itemId())
                    && (requested.nbt().isEmpty() || stocked.nbt().equals(requested.nbt()));
        }

        /** Mutable inventory with rollback checkpoints; keys and item buckets are immutable. */
        private static final class Ledger {
            private record Change(MaterialRef material, int previousCount) {}

            private final Map<MaterialRef, Integer> stock = new HashMap<>();
            private final Map<ResourceLocation, List<MaterialRef>> byItem = new HashMap<>();
            private final List<MaterialRef> order = new ArrayList<>();
            private final List<Change> changes = new ArrayList<>();

            private Ledger(Map<MaterialRef, Integer> available) {
                available.forEach((material, count) -> {
                    if (material == null || count == null || count <= 0) return;
                    stock.put(material, count);
                    order.add(material);
                    byItem.computeIfAbsent(material.itemId(), ignored -> new ArrayList<>()).add(material);
                });
            }

            private int mark() {
                return changes.size();
            }

            private int count(MaterialRef material) {
                return stock.getOrDefault(material, 0);
            }

            private int countAcrossAlternatives(IngredientRef ingredient) {
                int remaining = ingredient.count();
                for (MaterialRef stocked : order) {
                    int available = count(stocked);
                    if (available <= 0 || !matchesAny(stocked, ingredient.alternatives())) continue;
                    remaining -= Math.min(available, remaining);
                    if (remaining == 0) break;
                }
                return ingredient.count() - remaining;
            }

            private List<MaterialRef> byItem(ResourceLocation itemId) {
                return byItem.getOrDefault(itemId, List.of());
            }

            private List<MaterialRef> order() {
                return order;
            }

            private void set(MaterialRef material, int count) {
                int previous = stock.getOrDefault(material, 0);
                if (previous == count) return;
                changes.add(new Change(material, previous));
                if (count <= 0) stock.remove(material);
                else stock.put(material, count);
            }

            private void rollback(int mark) {
                while (changes.size() > mark) {
                    Change change = changes.remove(changes.size() - 1);
                    if (change.previousCount() <= 0) stock.remove(change.material());
                    else stock.put(change.material(), change.previousCount());
                }
            }
        }
    }
}
