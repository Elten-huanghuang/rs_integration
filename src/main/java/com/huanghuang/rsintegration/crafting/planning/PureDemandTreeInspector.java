package com.huanghuang.rsintegration.crafting.planning;

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
        RecipeNode target = graph.recipesById().get(targetRecipeId);
        if (target == null) return new Result(Status.TARGET_NOT_PROJECTED, 0, null);

        Walker walker = new Walker(graph, available, Math.max(1, maxNodes));
        int multiplier = Math.max(1, repeatCount);
        for (IngredientRef input : target.inputs()) {
            long scaled = (long) input.count() * multiplier;
            if (scaled > Integer.MAX_VALUE) {
                return new Result(Status.INCOMPLETE, walker.visitedNodes, first(input));
            }
            if (!walker.coverIngredient(new IngredientRef(input.alternatives(), (int) scaled))) {
                Status status = walker.nodeLimitReached ? Status.NODE_LIMIT : Status.INCOMPLETE;
                return new Result(status, walker.visitedNodes, walker.firstUnresolved);
            }
        }
        return new Result(Status.COMPLETE, walker.visitedNodes, null);
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

    public record Result(Status status, int visitedNodes, @Nullable MaterialRef unresolved) {
        public boolean complete() {
            return status == Status.COMPLETE;
        }
    }

    private static final class Walker {
        private final ImmutableRecipeGraph graph;
        private final Ledger ledger;
        private final Set<MaterialRef> visiting = new HashSet<>();
        private final int maxNodes;
        private int visitedNodes;
        private boolean nodeLimitReached;
        private MaterialRef firstUnresolved;

        private Walker(ImmutableRecipeGraph graph, Map<MaterialRef, Integer> available,
                       int maxNodes) {
            this.graph = graph;
            this.ledger = new Ledger(available);
            this.maxNodes = maxNodes;
        }

        private boolean coverIngredient(IngredientRef ingredient) {
            int mark = ledger.mark();
            if (consumeAcrossAlternatives(ingredient) == 0) return true;

            for (MaterialRef alternative : ingredient.alternatives()) {
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
                for (RecipeNode candidate : candidates) {
                    ledger.rollback(mark);
                    long batches = ((long) count + candidate.outputCount() - 1L)
                            / candidate.outputCount();
                    if (batches <= 0L || batches > Integer.MAX_VALUE) continue;

                    boolean covered = true;
                    for (IngredientRef input : candidate.inputs()) {
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
