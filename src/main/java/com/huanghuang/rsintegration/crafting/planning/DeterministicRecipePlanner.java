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
 * 对唯一生产者的确定性片段聚合需求；标签及多生产者边界由通用搜索补足。
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
        Prepared prepared = new CompilationCache(graph, forced, preferences, maxStates)
                .prepare(stock, roots, activeOutputs, maxSteps, maxStates, checkBudget);
        return prepared == null ? null : prepared.plan();
    }

    sealed interface SegmentStep permits Boundary, RecipeBatch {}
    /** 只补足库存，真正消费仍由后续配方端口记录，不能伪造一条材料流。 */
    record Boundary(IngredientRef ingredient) implements SegmentStep {}
    record RecipeBatch(RecipeNode recipe, List<IngredientRef> inputs, long batches) implements SegmentStep {}
    record Prepared(@Nullable Plan plan, List<SegmentStep> schedule, int expandedStates) {}

    private record Definition(MaterialRef material, @Nullable RecipeNode recipe,
                              List<IngredientRef> inputs) {}
    private record Program(Map<MaterialRef, Node> nodes, List<Node> ordered, Set<MaterialRef> stockLeaves) {}

    /**
     * 仅属于一次求解，图、强制路线和偏好固定；库存切点可重新展开，不保留数量或失败结论。
     * 缓存的结构单元数受现有搜索状态上限约束，不另引入固定阈值，也不跨配方重载保留。
     */
    static final class CompilationCache {
        private final ImmutableRecipeGraph graph;
        private final Map<ResourceLocation, ResourceLocation> forced;
        private final PlanningPreferences preferences;
        private final int maxUnits;
        private final Map<MaterialRef, Definition> definitions = new HashMap<>();
        private final Map<List<MaterialRef>, Program> programs = new HashMap<>();
        private final Set<MaterialRef> cyclicSegments = new HashSet<>();
        private int retainedUnits;
        private int compilationCount;

        CompilationCache(ImmutableRecipeGraph graph, Map<ResourceLocation, ResourceLocation> forced,
                         PlanningPreferences preferences, int maxUnits) {
            this.graph = graph;
            this.forced = Map.copyOf(forced);
            this.preferences = preferences == null ? PlanningPreferences.empty() : preferences;
            this.maxUnits = Math.max(0, maxUnits);
        }

        int compilationCount() { return compilationCount; }

        @Nullable
        Prepared prepare(Map<MaterialRef, Long> stock, List<IngredientRef> roots,
                         Set<MaterialRef> activeOutputs, int maxSteps, int maxStates,
                         Runnable checkBudget) {
            if (graph.recipesById().isEmpty() || roots.isEmpty()
                    || roots.stream().anyMatch(input -> !supported(input)
                    || cyclicSegments.contains(input.alternatives().get(0)))) return null;
            try {
                Budget budget = new Budget(maxStates, checkBudget);
                List<MaterialRef> key = roots.stream().map(input -> input.alternatives().get(0))
                        .distinct().toList();
                Program program = programs.get(key);
                Set<MaterialRef> stockBoundaries = null;
                if (program == null) {
                    stockBoundaries = new HashSet<>();
                    for (var entry : stock.entrySet()) {
                        checkBudget.run();
                        if (entry.getValue() != null && entry.getValue() > 0L) {
                            stockBoundaries.add(Evaluation.family(entry.getKey()));
                        }
                    }
                }
                boolean changed = program == null;
                while (true) {
                    if (program == null) program = compile(key, budget, stockBoundaries);
                    if (program == null) return null;
                    Evaluation evaluation = new Evaluation(program, stock, activeOutputs, maxSteps, budget);
                    Prepared result = evaluation.build(roots);
                    if (evaluation.expandStockLeaves.isEmpty()) {
                        if (changed) {
                            Program previous = programs.remove(key);
                            if (previous != null) retainedUnits -= programUnits(key, previous);
                            if (retain(programUnits(key, program))) programs.put(key, program);
                        }
                        return result;
                    }
                    // 先按库存截住生产链；汇总所有消费者后仍有缺口，才展开对应叶子。
                    // 这些叶子是可重新展开的边界，绝不缓存“库存不足”的结论。
                    // 缺口下的确定性片段一次编译，避免长链每层都有少量库存时逐层重编译。
                    // 已证明足量的其他库存叶子仍保留，后续评估继续核对共享需求。
                    stockBoundaries = new HashSet<>(program.stockLeaves());
                    stockBoundaries.removeAll(evaluation.expandStockLeaves);
                    program = null;
                    changed = true;
                }
            } catch (ArithmeticException | ProbeLimitReached overflow) {
                // 溢出或探测预算耗尽均交回通用搜索，不能提交半成品计划。
                return null;
            }
        }

        private static int programUnits(List<MaterialRef> key, Program program) {
            int units = Math.addExact(key.size(), program.nodes().size());
            units = Math.addExact(units, program.stockLeaves().size());
            for (Node node : program.ordered()) units = Math.addExact(units, node.children.size());
            return units;
        }

        private boolean retain(int units) {
            if (units > maxUnits - retainedUnits) return false;
            retainedUnits += units;
            return true;
        }

        private Definition definition(MaterialRef material, Budget budget) {
            Definition cached = definitions.get(material);
            if (cached != null) return cached;
            RecipeNode selected = null;
            if (!preferences.isNoRecipe(material)) {
                ResourceLocation forcedId = ForcedRecipeGraphProjector.forcedRecipeFor(material, forced);
                // ANY 还可能匹配带 NBT 的生产者，必须检查整个物品族。
                for (RecipeNode recipe : ImmutableRecipeGraphProjector.candidates(graph, material, NbtMatchMode.ANY)) {
                    budget.tick();
                    if (preferences.excludes(recipe.recipeId())
                            || forcedId != null && !forcedId.equals(recipe.recipeId())) continue;
                    if (selected != null || !recipe.output().equals(material)
                            || recipe.dynamicIdentityTargetable() || recipe.hasFluidOutputs()
                            || recipe.inputs().stream().anyMatch(input -> !supported(input))) {
                        selected = null;
                        break;
                    }
                    selected = recipe;
                }
            }
            Definition result = new Definition(material, selected,
                    selected == null ? List.of() : PureDemandNormalizer.mergeEquivalent(selected.inputs()));
            if (retain(Math.addExact(1, result.inputs().size()))) definitions.put(material, result);
            return result;
        }

        @Nullable
        private Program compile(List<MaterialRef> roots, Budget budget, Set<MaterialRef> stockBoundaries) {
            compilationCount++;
            Map<MaterialRef, Node> nodes = new HashMap<>();
            List<Node> ordered = new ArrayList<>();
            Set<MaterialRef> stockLeaves = new HashSet<>();
            for (MaterialRef root : roots) {
                if (nodes.containsKey(root)) continue;
                Node first = new Node(nodes.size(), definition(root, budget, stockBoundaries, stockLeaves));
                nodes.put(root, first);
                ArrayDeque<Node> stack = new ArrayDeque<>();
                stack.push(first);
                while (!stack.isEmpty()) {
                    budget.tick();
                    Node current = stack.peek();
                    if (current.cursor == current.inputs.size()) {
                        current.complete = true;
                        ordered.add(current);
                        stack.pop();
                        continue;
                    }
                    MaterialRef material = current.inputs.get(current.cursor++).alternatives().get(0);
                    Node child = nodes.get(material);
                    // 确定性环仍由原搜索处理；标签、多生产者在定义处已经截成边界。
                    if ((child != null && !child.complete) || cyclicSegments.contains(material)) {
                        for (Node ancestor : stack) {
                            if (!cyclicSegments.contains(ancestor.material) && retain(1)) {
                                cyclicSegments.add(ancestor.material);
                            }
                        }
                        return null;
                    }
                    if (child == null) {
                        child = new Node(nodes.size(), definition(material, budget, stockBoundaries, stockLeaves));
                        nodes.put(material, child);
                        stack.push(child);
                    }
                    current.children.add(child);
                }
            }
            return new Program(Map.copyOf(nodes), List.copyOf(ordered), Set.copyOf(stockLeaves));
        }

        private Definition definition(MaterialRef material, Budget budget,
                                      Set<MaterialRef> stockBoundaries, Set<MaterialRef> stockLeaves) {
            if (stockBoundaries.contains(material)) {
                stockLeaves.add(material);
                return new Definition(material, null, List.of());
            }
            return definition(material, budget);
        }
    }

    private static final class Budget {
        private final int maxStates;
        private final Runnable checkBudget;
        private int work;

        private Budget(int maxStates, Runnable checkBudget) {
            this.maxStates = maxStates;
            this.checkBudget = checkBudget;
        }

        private void tick() {
            checkBudget.run();
            if (++work > maxStates) throw new ProbeLimitReached();
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
        private final RecipeNode recipe;
        private final List<IngredientRef> inputs;
        private final List<Node> children = new ArrayList<>();
        private int cursor;
        private boolean complete;

        private Node(int id, Definition definition) {
            this.id = id;
            this.material = definition.material();
            this.recipe = definition.recipe();
            this.inputs = definition.inputs();
        }
    }

    private static final class Evaluation {
        private final Program program;
        private final Map<MaterialRef, Long> initial;
        private final Set<MaterialRef> activeOutputs;
        private final int maxSteps;
        private final Budget budget;
        private final Map<MaterialRef, List<MaterialRef>> variants = new HashMap<>();
        private final Map<MaterialRef, Long> familyStock = new HashMap<>();
        private final Set<MaterialRef> expandStockLeaves = new HashSet<>();

        private Evaluation(Program program, Map<MaterialRef, Long> initial,
                           Set<MaterialRef> activeOutputs, int maxSteps, Budget budget) {
            this.program = program;
            this.initial = initial;
            this.activeOutputs = activeOutputs;
            this.maxSteps = Math.max(1, maxSteps);
            this.budget = budget;
            initial.forEach((material, amount) -> {
                budget.checkBudget.run();
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

        @Nullable
        private Prepared build(List<IngredientRef> roots) {
            Map<MaterialRef, Node> nodes = program.nodes();
            List<Node> ordered = program.ordered();
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
            boolean needsBoundary = false;
            // 先收齐所有消费者的需求，再展开其生产者，避免分支间重复取整和展开。
            for (int index = ordered.size() - 1; index >= 0; index--) {
                budget.tick();
                Node node = ordered.get(index);
                if (demand[node.id] > 0L && variants.getOrDefault(node.material, List.of()).stream()
                        .anyMatch(variant -> variant.runtimeNbt() || variant.constrainedNbt())) return null;
                long missing = Math.max(0L, Math.max(
                        demand[node.id] - familyStock.getOrDefault(node.material, 0L),
                        exactDemand[node.id] - initial.getOrDefault(node.material, 0L)));
                if (missing == 0L) continue;
                if (program.stockLeaves().contains(node.material) && !activeOutputs.contains(node.material)) {
                    expandStockLeaves.add(node.material);
                }
                if (node.recipe == null || activeOutputs.contains(node.material)) {
                    needsBoundary = true;
                    continue;
                }
                if (++steps > maxSteps) return null;
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
            if (needsBoundary) {
                List<SegmentStep> schedule = new ArrayList<>();
                for (Node node : ordered) {
                    budget.tick();
                    long count = batches[node.id];
                    if (count > 0L) {
                        schedule.add(new RecipeBatch(node.recipe, node.inputs, count));
                    } else if (node.recipe == null || activeOutputs.contains(node.material)) {
                        if (exactDemand[node.id] > initial.getOrDefault(node.material, 0L)) {
                            schedule.add(new Boundary(new IngredientRef(List.of(node.material),
                                    exactDemand[node.id], NbtMatchMode.EXACT)));
                        }
                        if (demand[node.id] > familyStock.getOrDefault(node.material, 0L)
                                && demand[node.id] > exactDemand[node.id]) {
                            schedule.add(new Boundary(new IngredientRef(List.of(node.material),
                                    demand[node.id], NbtMatchMode.ANY)));
                        }
                    }
                }
                return new Prepared(null, List.copyOf(schedule), budget.work);
            }
            Map<MaterialRef, Long> remaining = new HashMap<>(initial);
            List<PlannedStep> plan = new ArrayList<>(steps);
            List<FlowEvent> flow = new ArrayList<>();
            for (Node node : ordered) {
                budget.tick();
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
                budget.tick();
                IngredientRef root = roots.get(port);
                flow.add(new MaterialUse(-1, port, root, consume(remaining, root, root.count())));
            }
            return new Prepared(new Plan(plan, flow, remaining, budget.work), List.of(), budget.work);
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
