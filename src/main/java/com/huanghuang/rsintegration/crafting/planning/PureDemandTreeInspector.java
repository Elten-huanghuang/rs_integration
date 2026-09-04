package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.crafting.SelfAmplifyingRecipePolicy;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
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
    private static final int LARGE_ALTERNATIVE_THRESHOLD = 16;

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
        return inspect(graph, available, targetRecipeId, repeatCount, maxNodes,
                reusableCatalystOutputIds, reusableCatalystRecipeIds, Set.of());
    }

    public static Result inspect(ImmutableRecipeGraph graph,
                                 Map<MaterialRef, Integer> available,
                                 ResourceLocation targetRecipeId,
                                 int repeatCount,
                                 int maxNodes,
                                 Set<ResourceLocation> reusableCatalystOutputIds,
                                 Set<ResourceLocation> reusableCatalystRecipeIds,
                                 Set<ResourceLocation> pureIncompatibleOutputIds) {
        boolean targetUsesReusableCatalyst = reusableCatalystRecipeIds != null
                && reusableCatalystRecipeIds.contains(targetRecipeId);
        RecipeNode target = graph.recipesById().get(targetRecipeId);
        if (target == null) {
            return new Result(Status.TARGET_NOT_PROJECTED, 0, null,
                    targetUsesReusableCatalyst);
        }

        Walker walker = new Walker(graph, available, Math.max(1, maxNodes),
                reusableCatalystOutputIds, pureIncompatibleOutputIds);
        Coverage targetCoverage = Coverage.COVERED;
        for (IngredientRef input : PureDemandNormalizer.mergeEquivalent(
                SelfAmplifyingRecipePolicy.scaleTargetInputs(target, repeatCount))) {
            Coverage coverage = walker.coverIngredient(input);
            if (coverage == Coverage.NODE_LIMIT) {
                targetCoverage = coverage;
                break;
            }
            // Target inputs are AND branches. Keep inspecting after a raw shortage so a later
            // typed-only dependency cannot be hidden by ingredient order.
            if (coverage == Coverage.UNPROJECTED_DEPENDENCY) {
                targetCoverage = coverage;
            } else if (coverage == Coverage.MISSING_MATERIALS
                    && targetCoverage == Coverage.COVERED) {
                targetCoverage = coverage;
            }
        }
        if (targetCoverage != Coverage.COVERED) {
            Status status = switch (targetCoverage) {
                case MISSING_MATERIALS -> Status.MISSING_MATERIALS;
                case UNPROJECTED_DEPENDENCY -> Status.UNPROJECTED_DEPENDENCY;
                case NODE_LIMIT -> Status.NODE_LIMIT;
                case COVERED -> throw new IllegalStateException("covered demand reported as failure");
            };
            return new Result(status, walker.visitedNodes, walker.firstUnresolved,
                    walker.catalystRouteAvailable);
        }
        return new Result(Status.COMPLETE, walker.visitedNodes, null,
                walker.catalystRouteAvailable);
    }

    private static MaterialRef first(IngredientRef ingredient) {
        return ingredient.alternatives().isEmpty() ? null : ingredient.alternatives().get(0);
    }

    public enum Status {
        COMPLETE,
        MISSING_MATERIALS,
        UNPROJECTED_DEPENDENCY,
        TARGET_NOT_PROJECTED,
        NODE_LIMIT
    }

    public record Result(Status status, int visitedNodes, @Nullable MaterialRef unresolved,
                         boolean catalystRouteAvailable) {
        public boolean complete() {
            return status == Status.COMPLETE;
        }

        public boolean pureCompatible() {
            return status == Status.COMPLETE || status == Status.MISSING_MATERIALS;
        }

        /**
         * Whether the immutable graph is safe to hand to the background planner.
         * NODE_LIMIT means this inexpensive routing probe stopped conservatively;
         * it does not mean that typed/main-thread recipe semantics are required.
         */
        public boolean backgroundCompatible() {
            return pureCompatible() || status == Status.NODE_LIMIT;
        }
    }

    private enum Coverage {
        COVERED,
        MISSING_MATERIALS,
        UNPROJECTED_DEPENDENCY,
        NODE_LIMIT
    }

    private static final class Walker {
        private final ImmutableRecipeGraph graph;
        private final Ledger ledger;
        private final Set<MaterialRef> visiting = new HashSet<>();
        private final Set<ResourceLocation> reusableCatalystOutputIds;
        private final Set<ResourceLocation> pureIncompatibleOutputIds;
        private final int maxNodes;
        private int visitedNodes;
        private boolean nodeLimitReached;
        private boolean catalystRouteAvailable;
        private MaterialRef firstUnresolved;

        private Walker(ImmutableRecipeGraph graph, Map<MaterialRef, Integer> available,
                       int maxNodes, Set<ResourceLocation> reusableCatalystOutputIds,
                       Set<ResourceLocation> pureIncompatibleOutputIds) {
            this.graph = graph;
            this.ledger = new Ledger(available);
            this.maxNodes = maxNodes;
            this.reusableCatalystOutputIds = reusableCatalystOutputIds == null
                    ? Set.of() : reusableCatalystOutputIds;
            this.pureIncompatibleOutputIds = pureIncompatibleOutputIds == null
                    ? Set.of() : pureIncompatibleOutputIds;
        }

        private Coverage coverIngredient(IngredientRef ingredient) {
            if (ingredient.role() == DemandRole.CATALYST) {
                return coverCatalyst(ingredient);
            }
            int mark = ledger.mark();
            if (consumeAcrossAlternatives(ingredient) == 0) return Coverage.COVERED;

            Coverage best = Coverage.UNPROJECTED_DEPENDENCY;
            boolean largeAlternativeSet = ingredient.alternatives().size()
                    >= LARGE_ALTERNATIVE_THRESHOLD;
            for (MaterialRef alternative : inventoryFirst(ingredient.alternatives())) {
                ledger.rollback(mark);
                int remaining = consumeMatching(alternative, ingredient.count());
                Coverage coverage = coverMaterial(alternative, remaining);
                if (coverage == Coverage.COVERED) return coverage;
                if (coverage == Coverage.NODE_LIMIT) {
                    best = Coverage.NODE_LIMIT;
                    break;
                }
                // Alternatives are OR branches. A fully projected branch that ends in raw
                // shortages is preferable to an unrelated typed-only producer.
                if (coverage == Coverage.MISSING_MATERIALS) {
                    best = Coverage.MISSING_MATERIALS;
                    // A large tag can contain hundreds of reverse-conversion variants. Once
                    // one inventory-first branch is known to be pure-compatible but short on
                    // stock, probing the remaining variants cannot improve route selection.
                    if (largeAlternativeSet) break;
                }
            }
            ledger.rollback(mark);
            // A catalyst-capable producer is a compatibility fallback, not a reason to
            // discard a pure route that was already proven usable. Only request typed
            // planning after every immutable alternative failed.
            noteCatalystOpportunity(ingredient);
            if (firstUnresolved == null) firstUnresolved = first(ingredient);
            return best;
        }

        private Coverage coverCatalyst(IngredientRef ingredient) {
            int mark = ledger.mark();
            int remaining = ingredient.count() - ledger.countAcrossAlternatives(ingredient);
            if (remaining <= 0) return Coverage.COVERED;

            Coverage best = Coverage.UNPROJECTED_DEPENDENCY;
            for (MaterialRef alternative : inventoryFirst(ingredient.alternatives())) {
                ledger.rollback(mark);
                Coverage coverage = coverMaterial(alternative, remaining);
                if (coverage == Coverage.COVERED) {
                    ledger.add(alternative, remaining);
                    return Coverage.COVERED;
                }
                if (coverage == Coverage.NODE_LIMIT) {
                    best = Coverage.NODE_LIMIT;
                    break;
                }
                if (coverage == Coverage.MISSING_MATERIALS) {
                    best = Coverage.MISSING_MATERIALS;
                }
            }
            ledger.rollback(mark);
            noteCatalystOpportunity(ingredient);
            if (firstUnresolved == null) firstUnresolved = first(ingredient);
            return best;
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

        private Coverage coverMaterial(MaterialRef material, int count) {
            if (count <= 0) return Coverage.COVERED;
            if (nodeLimitReached) return Coverage.NODE_LIMIT;
            // A closed conversion ring cannot create missing stock. It is still a normal
            // material shortage, not evidence that main-thread recipe semantics are needed.
            if (visiting.contains(material)) return Coverage.MISSING_MATERIALS;
            if (++visitedNodes > maxNodes) {
                nodeLimitReached = true;
                return Coverage.NODE_LIMIT;
            }

            List<RecipeNode> candidates = candidatesForMaterial(material);
            if (candidates.isEmpty()) {
                if (firstUnresolved == null) firstUnresolved = material;
                return pureIncompatibleOutputIds.contains(material.itemId())
                        ? Coverage.UNPROJECTED_DEPENDENCY
                        : Coverage.MISSING_MATERIALS;
            }

            int mark = ledger.mark();
            Coverage best = Coverage.UNPROJECTED_DEPENDENCY;
            visiting.add(material);
            try {
                for (RecipeNode candidate : inventoryFirstCandidates(candidates)) {
                    ledger.rollback(mark);
                    long batches = ((long) count + candidate.outputCount() - 1L)
                            / candidate.outputCount();
                    if (batches <= 0L || batches > Integer.MAX_VALUE) continue;
                    if (isUnseededReverseConversion(candidate, material, (int) batches)) continue;

                    Coverage candidateCoverage = Coverage.COVERED;
                    for (IngredientRef input : PureDemandNormalizer.mergeEquivalent(
                            candidate.inputs())) {
                        long scaled = scaledInputCount(input, batches);
                        if (scaled > Integer.MAX_VALUE) {
                            candidateCoverage = Coverage.UNPROJECTED_DEPENDENCY;
                            break;
                        }
                        Coverage inputCoverage = coverIngredient(input.withCount((int) scaled));
                        if (inputCoverage == Coverage.NODE_LIMIT) {
                            candidateCoverage = Coverage.NODE_LIMIT;
                            break;
                        }
                        // Recipe inputs are AND branches: one unprojected dependency means the
                        // candidate still needs typed planning even if another input is missing.
                        if (inputCoverage == Coverage.UNPROJECTED_DEPENDENCY) {
                            candidateCoverage = Coverage.UNPROJECTED_DEPENDENCY;
                        } else if (inputCoverage == Coverage.MISSING_MATERIALS
                                && candidateCoverage == Coverage.COVERED) {
                            candidateCoverage = Coverage.MISSING_MATERIALS;
                        }
                    }
                    if (candidateCoverage == Coverage.COVERED) return Coverage.COVERED;
                    if (candidateCoverage == Coverage.NODE_LIMIT) return Coverage.NODE_LIMIT;
                    // Candidate recipes are OR branches, so any fully projected shortage path
                    // makes this material safe for the background planner.
                    if (candidateCoverage == Coverage.MISSING_MATERIALS) {
                        // This inspector chooses the planner, not the final recipe. Once one
                        // candidate is representable by the pure graph, searching every
                        // compression/decompression sibling only risks walking conversion rings.
                        // The background planner still evaluates all candidates for feasibility.
                        return Coverage.MISSING_MATERIALS;
                    }
                }
            } finally {
                visiting.remove(material);
            }
            ledger.rollback(mark);
            if (best == Coverage.UNPROJECTED_DEPENDENCY
                    && !pureIncompatibleOutputIds.contains(material.itemId())) {
                best = Coverage.MISSING_MATERIALS;
            }
            return best;
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

        private List<RecipeNode> candidatesForMaterial(MaterialRef material) {
            List<RecipeNode> candidates = new ArrayList<>(graph.recipesByOutput()
                    .getOrDefault(material, List.of()));
            if (!material.nbt().isEmpty()) {
                MaterialRef tagless = new MaterialRef(material.itemId(), "");
                for (RecipeNode candidate : graph.recipesByOutput()
                        .getOrDefault(tagless, List.of())) {
                    if ("smithing".equals(candidate.modTypeId())) candidates.add(candidate);
                }
            }
            return candidates;
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
                if (input.role() == DemandRole.CATALYST) continue;
                long required = scaledInputCount(input, batches);
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

        private void noteCatalystOpportunity(IngredientRef ingredient) {
            if (catalystRouteAvailable || reusableCatalystOutputIds.isEmpty()) return;
            if (ledger.countAcrossAlternatives(ingredient) < ingredient.count()
                    && requiresReusableCatalystOutput(ingredient)) {
                catalystRouteAvailable = true;
            }
        }

        private boolean requiresReusableCatalystOutput(IngredientRef ingredient) {
            return !ingredient.alternatives().isEmpty()
                    && ingredient.alternatives().stream()
                    .allMatch(material -> reusableCatalystOutputIds.contains(material.itemId()));
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
                if (count <= 0) {
                    stock.remove(material);
                } else {
                    stock.put(material, count);
                    if (!order.contains(material)) {
                        order.add(material);
                        byItem.computeIfAbsent(material.itemId(), ignored -> new ArrayList<>())
                                .add(material);
                    }
                }
            }

            private void add(MaterialRef material, int count) {
                if (count <= 0) return;
                long combined = (long) stock.getOrDefault(material, 0) + count;
                set(material, (int) Math.min(Integer.MAX_VALUE, combined));
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

    private static long scaledInputCount(IngredientRef input, long batches) {
        return input.role() == DemandRole.CATALYST
                ? input.count() : (long) input.count() * batches;
    }
}
