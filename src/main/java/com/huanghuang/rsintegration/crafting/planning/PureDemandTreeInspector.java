package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.IngredientRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.MaterialRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.RecipeNode;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bounded inventory-aware check that pure planning covers the requested demand tree. */
public final class PureDemandTreeInspector {
    static final int DEFAULT_MAX_NODES = 512;

    private PureDemandTreeInspector() {}

    public static Result inspect(ImmutableRecipeGraph graph,
                                 Map<MaterialRef, Integer> available,
                                 ResourceLocation targetRecipeId,
                                 int repeatCount) {
        return inspect(graph, available, targetRecipeId, repeatCount, DEFAULT_MAX_NODES);
    }

    static Result inspect(ImmutableRecipeGraph graph,
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

    private record FailureKey(MaterialRef material, int count,
                              Map<MaterialRef, Integer> stock,
                              Set<MaterialRef> visiting) {}

    private static final class Walker {
        private final ImmutableRecipeGraph graph;
        private final Map<MaterialRef, Integer> stock = new HashMap<>();
        private final Set<MaterialRef> visiting = new HashSet<>();
        private final Set<FailureKey> failed = new HashSet<>();
        private final int maxNodes;
        private int visitedNodes;
        private boolean nodeLimitReached;
        private MaterialRef firstUnresolved;

        private Walker(ImmutableRecipeGraph graph, Map<MaterialRef, Integer> available,
                       int maxNodes) {
            this.graph = graph;
            available.forEach((material, count) -> {
                if (material != null && count != null && count > 0) stock.put(material, count);
            });
            this.maxNodes = maxNodes;
        }

        private boolean coverIngredient(IngredientRef ingredient) {
            Map<MaterialRef, Integer> before = new HashMap<>(stock);
            if (consumeAcrossAlternatives(ingredient) == 0) return true;

            for (MaterialRef alternative : ingredient.alternatives()) {
                restore(before);
                int remaining = consumeMatching(alternative, ingredient.count());
                if (coverMaterial(alternative, remaining)) return true;
                if (nodeLimitReached) break;
            }
            restore(before);
            if (firstUnresolved == null) firstUnresolved = first(ingredient);
            return false;
        }

        private int consumeAcrossAlternatives(IngredientRef ingredient) {
            Map<MaterialRef, Integer> before = new HashMap<>(stock);
            int remaining = ingredient.count();
            for (Map.Entry<MaterialRef, Integer> entry : Map.copyOf(stock).entrySet()) {
                if (!matchesAny(entry.getKey(), ingredient.alternatives())) continue;
                int take = Math.min(entry.getValue(), remaining);
                setStock(entry.getKey(), entry.getValue() - take);
                remaining -= take;
                if (remaining == 0) break;
            }
            if (remaining > 0) restore(before);
            return remaining;
        }

        private int consumeMatching(MaterialRef requested, int count) {
            int remaining = count;
            for (Map.Entry<MaterialRef, Integer> entry : Map.copyOf(stock).entrySet()) {
                if (!matches(entry.getKey(), requested)) continue;
                int take = Math.min(entry.getValue(), remaining);
                setStock(entry.getKey(), entry.getValue() - take);
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

            FailureKey key = new FailureKey(material, count, Map.copyOf(stock), Set.copyOf(visiting));
            if (failed.contains(key)) return false;

            List<RecipeNode> candidates = graph.recipesByOutput().getOrDefault(material, List.of());
            if (candidates.isEmpty()) {
                if (firstUnresolved == null) firstUnresolved = material;
                failed.add(key);
                return false;
            }

            Map<MaterialRef, Integer> before = new HashMap<>(stock);
            visiting.add(material);
            try {
                for (RecipeNode candidate : candidates) {
                    restore(before);
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
            restore(before);
            failed.add(key);
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

        private void restore(Map<MaterialRef, Integer> state) {
            stock.clear();
            stock.putAll(state);
        }

        private void setStock(MaterialRef material, int count) {
            if (count <= 0) stock.remove(material);
            else stock.put(material, count);
        }
    }
}
