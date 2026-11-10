package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.IngredientRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.MaterialRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.NbtMatchMode;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.RecipeNode;
import com.huanghuang.rsintegration.crafting.planning.PureRecipePlanner.FlowEvent;
import com.huanghuang.rsintegration.crafting.planning.PureRecipePlanner.MaterialUse;
import com.huanghuang.rsintegration.crafting.planning.PureRecipePlanner.PlannedStep;
import com.huanghuang.rsintegration.crafting.planning.PureRecipePlanner.Production;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 对唯一生产者的确定性 DAG 聚合需求；不能证明安全时交回通用搜索。
 * 拓扑发现、需求传播和材料事件生成都不使用递归，也不按产物件数展开。
 */
final class DeterministicRecipePlanner {
    private DeterministicRecipePlanner() {}

    /** 探测额度用完只停用加速，不能抢占通用搜索判定缺料或可行性的额度。 */
    static final class ProbeLimitReached extends RuntimeException {}

    record Plan(List<PlannedStep> steps, List<FlowEvent> flow,
                Map<MaterialRef, Long> remaining, int expandedStates) {}

    @Nullable
    static Plan tryPlan(ImmutableRecipeGraph graph, Map<MaterialRef, Long> stock,
                        List<IngredientRef> roots, Set<MaterialRef> activeOutputs,
                        Map<ResourceLocation, ResourceLocation> forced,
                        PlanningPreferences preferences, int maxSteps, int maxStates,
                        Runnable checkBudget) {
        return tryPlan(graph, stock, roots, activeOutputs, forced, preferences,
                maxSteps, maxStates, checkBudget, new HashSet<>());
    }

    /** 同一次搜索复用失败探测，避免复杂长链在每一层重复扫描相同前缀。 */
    @Nullable
    static Plan tryPlan(ImmutableRecipeGraph graph, Map<MaterialRef, Long> stock,
                        List<IngredientRef> roots, Set<MaterialRef> activeOutputs,
                        Map<ResourceLocation, ResourceLocation> forced,
                        PlanningPreferences preferences, int maxSteps, int maxStates,
                        Runnable checkBudget, Set<MaterialRef> skippedSegments) {
        if (graph.recipesById().isEmpty() || roots.stream().anyMatch(input -> !supported(input))) {
            return null;
        }
        if (roots.stream().anyMatch(input -> skippedSegments.contains(input.alternatives().get(0)))) return null;
        try {
            return new Builder(graph, stock, activeOutputs, forced, preferences,
                    maxSteps, maxStates, checkBudget, skippedSegments).build(roots);
        } catch (ArithmeticException | ProbeLimitReached overflow) {
            // 溢出或探测额度耗尽均回退，不能提交半成品计划。
            return null;
        }
    }

    static boolean supported(IngredientRef input) {
        if (input.alternatives().size() != 1
                || input.nbtMatchMode() != NbtMatchMode.EXACT && input.nbtMatchMode() != NbtMatchMode.ANY
                || input.role() != DemandRole.CONSUMED || !input.recursive()) return false;
        MaterialRef material = input.alternatives().get(0);
        return material.nbt().isEmpty() && !material.runtimeNbt() && !material.constrainedNbt();
    }

    private static final class Node {
        private final int id;
        private final MaterialRef material;
        private RecipeNode recipe;
        private List<IngredientRef> inputs = List.of();
        private final List<Node> children = new ArrayList<>();
        private int cursor;
        private boolean complete;

        private Node(int id, MaterialRef material) {
            this.id = id;
            this.material = material;
        }
    }

    private static final class Builder {
        private final ImmutableRecipeGraph graph;
        private final Map<MaterialRef, Long> initial;
        private final Set<MaterialRef> activeOutputs;
        private final Map<ResourceLocation, ResourceLocation> forced;
        private final PlanningPreferences preferences;
        private final int maxSteps;
        private final int maxStates;
        private final Runnable checkBudget;
        private final Map<MaterialRef, Node> nodes = new HashMap<>();
        private final Map<MaterialRef, List<MaterialRef>> variants = new HashMap<>();
        private final Map<MaterialRef, Long> familyStock = new HashMap<>();
        private final Set<MaterialRef> skippedSegments;
        private final List<Node> ordered = new ArrayList<>();
        private int work;
        private boolean unsupported;

        private Builder(ImmutableRecipeGraph graph, Map<MaterialRef, Long> initial,
                        Set<MaterialRef> activeOutputs, Map<ResourceLocation, ResourceLocation> forced,
                        PlanningPreferences preferences, int maxSteps, int maxStates, Runnable checkBudget,
                        Set<MaterialRef> skippedSegments) {
            this.graph = graph;
            this.initial = initial;
            this.activeOutputs = activeOutputs;
            this.forced = forced;
            this.preferences = preferences == null ? PlanningPreferences.empty() : preferences;
            this.maxSteps = Math.max(1, maxSteps);
            this.maxStates = Math.max(1, maxStates);
            this.checkBudget = checkBudget;
            this.skippedSegments = skippedSegments;
            initial.forEach((material, amount) -> {
                checkBudget.run();
                if (amount == null || amount <= 0L) return;
                MaterialRef family = family(material);
                variants.computeIfAbsent(family, ignored -> new ArrayList<>()).add(material);
                familyStock.merge(family, amount, Math::addExact);
            });
            // 先使用带标签库存，给同物品的精确无标签需求留下唯一可用的库存。
            variants.values().forEach(items -> items.sort(Comparator
                    .comparing((MaterialRef material) -> material.nbt().isEmpty())
                    .thenComparing(MaterialRef::nbt)));
        }

        private boolean tick() {
            checkBudget.run();
            return ++work <= maxStates;
        }

        private Node node(MaterialRef material) {
            Node node = new Node(nodes.size(), material);
            nodes.put(material, node);
            if (skippedSegments.contains(material)
                    || variants.getOrDefault(material, List.of()).stream()
                    .anyMatch(variant -> variant.runtimeNbt() || variant.constrainedNbt())) {
                unsupported = true;
                return node;
            }
            if (preferences.isNoRecipe(material) || activeOutputs.contains(material)) return node;
            ResourceLocation forcedId = ForcedRecipeGraphProjector.forcedRecipeFor(material, forced);
            // ANY 可能还有带标签或动态产出的生产者，不能只查无标签键后误认唯一生产者。
            for (RecipeNode recipe : ImmutableRecipeGraphProjector.candidates(graph, material, NbtMatchMode.ANY)) {
                if (preferences.excludes(recipe.recipeId())
                        || forcedId != null && !forcedId.equals(recipe.recipeId())) continue;
                if (node.recipe != null || !recipe.output().equals(material)
                        || recipe.dynamicIdentityTargetable() || recipe.hasFluidOutputs()
                        || recipe.inputs().stream().anyMatch(input -> !supported(input))) {
                    unsupported = true;
                    return node;
                }
                node.recipe = recipe;
                node.inputs = PureDemandNormalizer.mergeEquivalent(recipe.inputs());
            }
            if (node.recipe == null && familyStock.getOrDefault(material, 0L) == 0L) {
                unsupported = true;
            }
            return node;
        }

        @Nullable
        private Plan build(List<IngredientRef> roots) {
            // 同一节点只展开一次；灰色节点表示环，不能套用 DAG 的批量公式。
            for (IngredientRef root : roots) {
                MaterialRef material = root.alternatives().get(0);
                if (nodes.containsKey(material)) continue;
                ArrayDeque<Node> stack = new ArrayDeque<>();
                stack.push(node(material));
                while (!stack.isEmpty()) {
                    if (!tick()) return null;
                    if (unsupported) {
                        // 仅禁用通往复杂边界的祖先；边界另一侧的简单片段仍可聚合。
                        for (Node ancestor : stack) skippedSegments.add(ancestor.material);
                        return null;
                    }
                    Node current = stack.peek();
                    if (current.cursor == current.inputs.size()) {
                        current.complete = true;
                        ordered.add(current);
                        stack.pop();
                        continue;
                    }
                    MaterialRef childMaterial = current.inputs.get(current.cursor++).alternatives().get(0);
                    Node child = nodes.get(childMaterial);
                    if (child != null && !child.complete) {
                        for (Node ancestor : stack) skippedSegments.add(ancestor.material);
                        return null;
                    }
                    if (child == null) {
                        child = node(childMaterial);
                        stack.push(child);
                    }
                    current.children.add(child);
                }
            }
            long[] demand = new long[nodes.size()];
            long[] exactDemand = new long[nodes.size()];
            long[] batches = new long[nodes.size()];
            for (IngredientRef root : roots) {
                int id = nodes.get(root.alternatives().get(0)).id;
                demand[id] = Math.addExact(demand[id], root.count());
                if (root.nbtMatchMode() == NbtMatchMode.EXACT) {
                    exactDemand[id] = Math.addExact(exactDemand[id], root.count());
                }
            }
            int steps = 0;
            // 先收齐所有消费者的需求，再展开其生产者，避免分支间重复取整和展开。
            for (int index = ordered.size() - 1; index >= 0; index--) {
                if (!tick()) return null;
                Node node = ordered.get(index);
                long missing = Math.max(0L, Math.max(
                        demand[node.id] - familyStock.getOrDefault(node.material, 0L),
                        exactDemand[node.id] - initial.getOrDefault(node.material, 0L)));
                if (missing == 0L) continue;
                if (node.recipe == null || ++steps > maxSteps) {
                    // 这里只禁用加速尝试，通用搜索仍保留全部候选和缺料说明。
                    skippedSegments.addAll(nodes.keySet());
                    return null;
                }
                long output = node.recipe.outputCount();
                long count = missing / output + (missing % output == 0L ? 0L : 1L);
                batches[node.id] = count;
                for (int port = 0; port < node.inputs.size(); port++) {
                    int child = node.children.get(port).id;
                    demand[child] = Math.addExact(demand[child], Math.multiplyExact(node.inputs.get(port).count(), count));
                    if (node.inputs.get(port).nbtMatchMode() == NbtMatchMode.EXACT) {
                        exactDemand[child] = Math.addExact(exactDemand[child],
                                Math.multiplyExact(node.inputs.get(port).count(), count));
                    }
                }
            }
            if (steps == 0) return null;
            Map<MaterialRef, Long> remaining = new HashMap<>(initial);
            List<PlannedStep> plan = new ArrayList<>(steps);
            List<FlowEvent> flow = new ArrayList<>();
            for (Node node : ordered) {
                if (!tick()) return null;
                long count = batches[node.id];
                if (count == 0L) continue;
                for (int port = 0; port < node.inputs.size(); port++) {
                    IngredientRef input = node.inputs.get(port);
                    long amount = Math.multiplyExact(input.count(), count);
                    flow.add(new MaterialUse(node.id, port, input.withCount(amount),
                            consume(remaining, input, amount)));
                }
                long produced = Math.multiplyExact(node.recipe.outputCount(), count);
                remaining.merge(node.material, produced, Math::addExact);
                plan.add(new PlannedStep(node.recipe.recipeId(), count));
                flow.add(new Production(node.id, node.recipe, count, node.material, null));
            }
            for (int port = 0; port < roots.size(); port++) {
                if (!tick()) return null;
                IngredientRef root = roots.get(port);
                flow.add(new MaterialUse(-1, port, root, consume(remaining, root, root.count())));
            }
            return new Plan(plan, flow, remaining, work);
        }

        private Map<MaterialRef, Long> consume(Map<MaterialRef, Long> stock, IngredientRef input, long amount) {
            MaterialRef material = input.alternatives().get(0);
            Map<MaterialRef, Long> used = new LinkedHashMap<>();
            if (input.nbtMatchMode() == NbtMatchMode.ANY) {
                for (MaterialRef variant : variants.getOrDefault(material, List.of())) {
                    if (variant.equals(material)) continue;
                    long take = Math.min(amount, stock.getOrDefault(variant, 0L));
                    if (take <= 0L) continue;
                    consumeExact(stock, variant, take);
                    used.put(variant, take);
                    amount -= take;
                    if (amount == 0L) return used;
                }
            }
            consumeExact(stock, material, amount);
            used.put(material, amount);
            return used;
        }

        private static MaterialRef family(MaterialRef material) {
            return new MaterialRef(material.kind(), material.resourceId(), "", false, false);
        }

        private static void consumeExact(Map<MaterialRef, Long> stock, MaterialRef material, long amount) {
            long remaining = Math.subtractExact(stock.getOrDefault(material, 0L), amount);
            if (remaining < 0L) throw new IllegalStateException("聚合计划的供料不足");
            if (remaining == 0L) stock.remove(material);
            else stock.put(material, remaining);
        }
    }
}
