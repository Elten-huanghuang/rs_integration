package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.crafting.SelfAmplifyingRecipePolicy;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.config.CraftingPlanningConfig;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.IngredientRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.MaterialRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.NbtMatchMode;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.RecipeNode;
import net.minecraft.resources.ResourceLocation;
import java.util.Comparator;
import java.util.LinkedHashSet;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bounded inventory-aware check that pure planning covers the requested demand tree. */
public final class PureDemandTreeInspector {
    private static final int LARGE_ALTERNATIVE_THRESHOLD = 16;

    private PureDemandTreeInspector() {}

    public static Result inspect(ImmutableRecipeGraph graph,
                                 Map<MaterialRef, ? extends Number> available,
                                 ResourceLocation targetRecipeId,
                                 int repeatCount) {
        return inspect(graph, available, targetRecipeId, repeatCount,
                CraftingPlanningConfig.DEFAULT_DEMAND_TREE_NODES);
    }

    public static Result inspect(ImmutableRecipeGraph graph,
                                 Map<MaterialRef, ? extends Number> available,
                                 ResourceLocation targetRecipeId,
                                 int repeatCount,
                                 int maxNodes) {
        return inspect(graph, available, targetRecipeId, repeatCount, maxNodes, Set.of());
    }

    public static Result inspect(ImmutableRecipeGraph graph,
                                 Map<MaterialRef, ? extends Number> available,
                                 ResourceLocation targetRecipeId,
                                 int repeatCount,
                                 int maxNodes,
                                 Set<ResourceLocation> reusableCatalystOutputIds) {
        return inspect(graph, available, targetRecipeId, repeatCount, maxNodes,
                reusableCatalystOutputIds, Set.of());
    }

    public static Result inspect(ImmutableRecipeGraph graph,
                                 Map<MaterialRef, ? extends Number> available,
                                 ResourceLocation targetRecipeId,
                                 int repeatCount,
                                 int maxNodes,
                                 Set<ResourceLocation> reusableCatalystOutputIds,
                                 Set<ResourceLocation> reusableCatalystRecipeIds) {
        return inspect(graph, available, targetRecipeId, repeatCount, maxNodes,
                reusableCatalystOutputIds, reusableCatalystRecipeIds, Set.of());
    }

    public static Result inspect(ImmutableRecipeGraph graph,
                                 Map<MaterialRef, ? extends Number> available,
                                 ResourceLocation targetRecipeId,
                                 int repeatCount,
                                 int maxNodes,
                                 Set<ResourceLocation> reusableCatalystOutputIds,
                                 Set<ResourceLocation> reusableCatalystRecipeIds,
                                 Set<ResourceLocation> pureIncompatibleOutputIds) {
        return PlanningLookupCache.run(() -> inspectInScope(graph, available, targetRecipeId,
                repeatCount, maxNodes, reusableCatalystOutputIds, reusableCatalystRecipeIds,
                pureIncompatibleOutputIds, Long.MAX_VALUE));
    }

    static Result inspectWithDeadline(ImmutableRecipeGraph graph,
                                      Map<MaterialRef, ? extends Number> supplied,
                                      ResourceLocation targetRecipeId,
                                      int repeatCount,
                                      int maxNodes,
                                      Set<ResourceLocation> reusableCatalystOutputIds,
                                      Set<ResourceLocation> reusableCatalystRecipeIds,
                                      Set<ResourceLocation> pureIncompatibleOutputIds,
                                      long deadlineNanos) {
        return PlanningLookupCache.run(() -> inspectInScope(graph, supplied, targetRecipeId,
                repeatCount, maxNodes, reusableCatalystOutputIds, reusableCatalystRecipeIds,
                pureIncompatibleOutputIds, deadlineNanos));
    }

    private static Result inspectInScope(ImmutableRecipeGraph graph,
                                         Map<MaterialRef, ? extends Number> supplied,
                                         ResourceLocation targetRecipeId, int repeatCount, int maxNodes,
                                         Set<ResourceLocation> reusableCatalystOutputIds,
                                         Set<ResourceLocation> reusableCatalystRecipeIds,
                                         Set<ResourceLocation> pureIncompatibleOutputIds,
                                         long deadlineNanos) {
        boolean targetUsesReusableCatalyst = reusableCatalystRecipeIds != null
                && reusableCatalystRecipeIds.contains(targetRecipeId);
        RecipeNode target = graph.recipesById().get(targetRecipeId);
        if (target == null) {
            return new Result(Status.TARGET_NOT_PROJECTED, 0, null,
                    targetUsesReusableCatalyst);
        }

        List<IngredientRef> roots = PureDemandNormalizer.mergeEquivalent(
                SelfAmplifyingRecipePolicy.scaleTargetInputs(target, repeatCount));
        Map<MaterialRef, Long> available = longCounts(supplied);
        graph = ImmutableRecipeGraphProjector.bindSmithingStates(graph, available, roots);
        Walker walker = new Walker(graph, available, Math.max(1, maxNodes),
                reusableCatalystOutputIds, pureIncompatibleOutputIds, deadlineNanos);
        Coverage targetCoverage = Coverage.COVERED;
        for (IngredientRef input : roots) {
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
        private final long deadlineNanos;
        private int visitedNodes;
        private boolean nodeLimitReached;
        private boolean catalystRouteAvailable;
        private MaterialRef firstUnresolved;

        private Walker(ImmutableRecipeGraph graph, Map<MaterialRef, Long> available,
                       int maxNodes, Set<ResourceLocation> reusableCatalystOutputIds,
                       Set<ResourceLocation> pureIncompatibleOutputIds, long deadlineNanos) {
            this.graph = graph;
            this.ledger = new Ledger(available);
            this.maxNodes = maxNodes;
            this.deadlineNanos = deadlineNanos;
            this.reusableCatalystOutputIds = reusableCatalystOutputIds == null
                    ? Set.of() : reusableCatalystOutputIds;
            this.pureIncompatibleOutputIds = pureIncompatibleOutputIds == null
                    ? Set.of() : pureIncompatibleOutputIds;
        }

        private Coverage coverIngredient(IngredientRef ingredient) {
            // 帧保存候选游标和账本检查点，深度不再占用 Java 调用栈。
            ArrayDeque<InspectionFrame> stack = new ArrayDeque<>();
            stack.push(new IngredientFrame(ingredient));
            Coverage completed = null;
            while (!stack.isEmpty()) {
                PlanningThreadContext.throwIfCancelled();
                InspectionFrame frame = stack.peek();
                InspectionFrame child = frame.advance(completed);
                completed = null;
                if (child != null) {
                    stack.push(child);
                } else {
                    completed = frame.result;
                    stack.pop();
                }
            }
            return completed;
        }

        private abstract class InspectionFrame {
            protected Coverage result;

            /** 返回待执行子帧；返回 null 表示本帧已产生 result。 */
            abstract InspectionFrame advance(@Nullable Coverage child);

            protected InspectionFrame finish(Coverage coverage) {
                result = coverage;
                return null;
            }
        }

        private final class IngredientFrame extends InspectionFrame {
            private final IngredientRef ingredient;
            private final boolean catalyst;
            private List<MaterialRef> alternatives;
            private int mark;
            private int cursor;
            private long catalystMissing;
            private Coverage best = Coverage.UNPROJECTED_DEPENDENCY;

            private IngredientFrame(IngredientRef ingredient) {
                this.ingredient = ingredient;
                this.catalyst = ingredient.role() == DemandRole.CATALYST;
            }

            @Override
            InspectionFrame advance(@Nullable Coverage child) {
                if (alternatives == null) {
                    if (budgetExpired()) return finish(Coverage.NODE_LIMIT);
                    mark = ledger.mark();
                    if (catalyst) {
                        catalystMissing = Math.max(0L, routingCount(ingredient.count())
                                - ledger.countAcrossAlternatives(ingredient));
                        if (catalystMissing == 0L) return finish(Coverage.COVERED);
                    } else if (consumeAcrossAlternatives(ingredient) == 0L) {
                        return finish(Coverage.COVERED);
                    }
                    alternatives = inventoryFirst(ingredient.alternatives());
                }
                if (child == Coverage.COVERED) return finish(child);
                if (child == Coverage.NODE_LIMIT) return failed(child);
                if (child == Coverage.MISSING_MATERIALS) {
                    best = child;
                    // 宽标签找到纯图可表达的缺料路线后就停止路由探测，实际选择由主求解器决定。
                    if (!catalyst && alternatives.size() >= LARGE_ALTERNATIVE_THRESHOLD) return failed(best);
                }
                if (cursor == alternatives.size()) return failed(best);
                ledger.rollback(mark);
                MaterialRef alternative = alternatives.get(cursor++);
                long remaining = catalyst ? catalystMissing
                        : consumeMatching(alternative, routingCount(ingredient.count()), ingredient.nbtMatchMode());
                return new MaterialFrame(alternative, remaining, ingredient.nbtMatchMode(), catalyst);
            }

            private InspectionFrame failed(Coverage coverage) {
                ledger.rollback(mark);
                noteCatalystOpportunity(ingredient);
                if (firstUnresolved == null) firstUnresolved = first(ingredient);
                return finish(coverage);
            }
        }

        private boolean budgetExpired() {
            PlanningThreadContext.throwIfCancelled();
            if (deadlineNanos == Long.MAX_VALUE) return false;
            return System.nanoTime() >= deadlineNanos;
        }

        private long consumeAcrossAlternatives(IngredientRef ingredient) {
            int mark = ledger.mark();
            long remaining = routingCount(ingredient.count());
            for (MaterialRef stocked : ledger.matching(ingredient)) {
                long available = ledger.count(stocked);
                if (available <= 0) continue;
                long take = Math.min(available, remaining);
                ledger.set(stocked, available - take);
                remaining -= take;
                if (remaining == 0) break;
            }
            if (remaining > 0) ledger.rollback(mark);
            return remaining;
        }

        private long consumeMatching(MaterialRef requested, long count,
                                     NbtMatchMode mode) {
            long remaining = count;
            for (MaterialRef stocked : ledger.byItem(requested.itemId())) {
                long available = ledger.count(stocked);
                if (available <= 0 || !ImmutableRecipeGraphProjector.matchesIngredient(stocked,
                        new IngredientRef(List.of(requested), count, mode))) continue;
                long take = Math.min(available, remaining);
                ledger.set(stocked, available - take);
                remaining -= take;
                if (remaining == 0) break;
            }
            return remaining;
        }

        private final class MaterialFrame extends InspectionFrame {
            private final MaterialRef material;
            private final long count;
            private final NbtMatchMode mode;
            private final boolean catalyst;
            private List<RecipeNode> candidates;
            private int mark;
            private int cursor;
            private boolean visitingAdded;
            private RecipeNode candidate;
            private List<IngredientRef> inputs;
            private int port;
            private long batches;
            private Coverage candidateCoverage;
            private Coverage best = Coverage.UNPROJECTED_DEPENDENCY;

            private MaterialFrame(MaterialRef material, long count, NbtMatchMode mode, boolean catalyst) {
                this.material = material;
                this.count = count;
                this.mode = mode;
                this.catalyst = catalyst;
            }

            @Override
            InspectionFrame advance(@Nullable Coverage child) {
                if (candidates == null) {
                    if (count <= 0L) return finish(Coverage.COVERED);
                    if (budgetExpired() || nodeLimitReached) return finish(Coverage.NODE_LIMIT);
                    if (visiting.contains(material)) return finish(Coverage.MISSING_MATERIALS);
                    if (++visitedNodes > maxNodes) {
                        nodeLimitReached = true;
                        return finish(Coverage.NODE_LIMIT);
                    }
                    candidates = ImmutableRecipeGraphProjector.candidates(graph, material, mode);
                    if (candidates.isEmpty()) {
                        if (firstUnresolved == null) firstUnresolved = material;
                        return finish(pureIncompatibleOutputIds.contains(material.itemId())
                                ? Coverage.UNPROJECTED_DEPENDENCY : Coverage.MISSING_MATERIALS);
                    }
                    mark = ledger.mark();
                    visiting.add(material);
                    visitingAdded = true;
                    candidates = inventoryFirstCandidates(candidates);
                }
                if (child == Coverage.NODE_LIMIT) return finish(child);
                if (child == Coverage.UNPROJECTED_DEPENDENCY
                        || child == Coverage.MISSING_MATERIALS && candidateCoverage == Coverage.COVERED) {
                    candidateCoverage = child;
                }
                while (true) {
                    if (candidate != null) {
                        // 输入是 AND 分支：遇到缺料仍继续检查后面的类型依赖。
                        if (port < inputs.size()) {
                            IngredientRef input = inputs.get(port++);
                            return new IngredientFrame(input.withCount(scaledInputCount(input, batches)));
                        }
                        if (candidateCoverage == Coverage.COVERED) {
                            long remainingOutput = batches * candidate.outputCount() - (catalyst ? 0L : count);
                            if (remainingOutput > 0L) ledger.add(candidate.output(), remainingOutput);
                            return finish(Coverage.COVERED);
                        }
                        if (candidateCoverage == Coverage.MISSING_MATERIALS) {
                            if (!material.nbt().isEmpty() || "smithing".equals(candidate.modTypeId())) {
                                best = Coverage.MISSING_MATERIALS;
                            } else {
                                return finish(Coverage.MISSING_MATERIALS);
                            }
                        }
                        candidate = null;
                    }
                    if (cursor == candidates.size()) {
                        ledger.rollback(mark);
                        if (best == Coverage.UNPROJECTED_DEPENDENCY
                                && !pureIncompatibleOutputIds.contains(material.itemId())) {
                            best = Coverage.MISSING_MATERIALS;
                        }
                        return finish(best);
                    }
                    ledger.rollback(mark);
                    RecipeNode next = candidates.get(cursor++);
                    batches = (count + next.outputCount() - 1L) / next.outputCount();
                    if (batches > 0L && !isUnseededReverseConversion(next, material, batches)) {
                        candidate = next;
                        inputs = PureDemandNormalizer.mergeEquivalent(next.inputs());
                        port = 0;
                        candidateCoverage = Coverage.COVERED;
                    }
                }
            }

            @Override
            protected InspectionFrame finish(Coverage coverage) {
                if (visitingAdded) visiting.remove(material);
                return super.finish(coverage);
            }
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
            ordered.sort(Comparator.comparingDouble(this::inputStockCoverage).reversed());
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
                                                    long batches) {
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
                                    SelfAmplifyingRecipePolicy.isSelfInput(ingredient, wanted)))) {
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

        /** Mutable inventory with rollback checkpoints; keys and item buckets are immutable. */
        private static final class Ledger {
            private record Change(MaterialRef material, long previousCount) {}

            private final Map<MaterialRef, Long> stock = new HashMap<>();
            private final Map<ResourceLocation, List<MaterialRef>> byItem = new HashMap<>();
            private final Map<MaterialRef, Long> orderPositions = new HashMap<>();
            private final List<MaterialRef> order = new ArrayList<>();
            private final List<Change> changes = new ArrayList<>();

            private Ledger(Map<MaterialRef, Long> available) {
                available.forEach((material, count) -> {
                    if (material == null || count == null || count <= 0) return;
                    stock.put(material, count);
                    orderPositions.put(material, (long) order.size());
                    order.add(material);
                    byItem.computeIfAbsent(material.itemId(), ignored -> new ArrayList<>()).add(material);
                });
            }

            private int mark() {
                return changes.size();
            }

            private long count(MaterialRef material) {
                return stock.getOrDefault(material, 0L);
            }

            private long countAcrossAlternatives(IngredientRef ingredient) {
                long remaining = routingCount(ingredient.count());
                for (MaterialRef stocked : matching(ingredient)) {
                    long available = count(stocked);
                    if (available <= 0) continue;
                    remaining -= Math.min(available, remaining);
                    if (remaining == 0) break;
                }
                return routingCount(ingredient.count()) - remaining;
            }

            private Iterable<MaterialRef> matching(IngredientRef ingredient) {
                LinkedHashSet<MaterialRef> candidates = new LinkedHashSet<>();
                for (MaterialRef alternative : ingredient.alternatives()) {
                    candidates.addAll(byItem.getOrDefault(alternative.itemId(), List.of()));
                }
                candidates.removeIf(material -> !ImmutableRecipeGraphProjector.matchesIngredient(
                        material, ingredient));
                return candidates.stream()
                        .sorted(Comparator.comparingLong(material ->
                                orderPositions.getOrDefault(material, Long.MAX_VALUE)))
                        .toList();
            }

            private List<MaterialRef> byItem(ResourceLocation itemId) {
                return byItem.getOrDefault(itemId, List.of());
            }

            private List<MaterialRef> order() {
                return order;
            }

            private void set(MaterialRef material, long count) {
                long previous = stock.getOrDefault(material, 0L);
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

            private void add(MaterialRef material, long count) {
                if (count <= 0) return;
                long current = stock.getOrDefault(material, 0L);
                set(material, Long.MAX_VALUE - current < count
                        ? Long.MAX_VALUE : current + count);
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

    private static long routingCount(long count) {
        return Math.max(0L, count);
    }

    private static Map<MaterialRef, Long> longCounts(
            Map<MaterialRef, ? extends Number> supplied) {
        Map<MaterialRef, Long> counts = new LinkedHashMap<>();
        supplied.forEach((material, count) -> {
            if (material != null && count != null && count.longValue() > 0L) {
                counts.put(material, count.longValue());
            }
        });
        return counts;
    }
}
