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
import com.huanghuang.rsintegration.crafting.planning.PureRecipePlanner.SeededReachability;
import com.huanghuang.rsintegration.crafting.planning.PureRecipePlanner.SeededReachability;
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
import java.util.function.Function;

/**
 * 聚合确定性片段、分支内已选路线及不同路线的共同供料；其余边界由通用搜索补足。
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
                              List<IngredientRef> inputs, List<RecipeNode> alternatives,
                              boolean routeSpecificProduction, List<IngredientRef> routeSpecificInputs) {
        private Definition(MaterialRef material, RecipeNode recipe, List<IngredientRef> inputs) {
            this(material, recipe, inputs, List.of(), false, List.of());
        }
        private Definition(MaterialRef material, @Nullable RecipeNode recipe, List<IngredientRef> inputs,
                           List<RecipeNode> alternatives, boolean routeSpecificProduction) {
            this(material, recipe, inputs, alternatives, routeSpecificProduction, List.of());
        }
    }
    private record Program(Map<MaterialRef, Node> nodes, List<Node> ordered, Set<MaterialRef> stockLeaves,
                           Set<IngredientRef> routeSpecificInputs) {}

    /**
     * 只属于一个 SearchFrame 的路线游标。每次尝试用新编译上下文，不能把库存相关选择
     * 写入公共结构缓存。失败后改变一处选择并丢弃后缀；非等价路线从不当成等价路线折叠。
     */
    static final class RouteSearch {
        @FunctionalInterface
        interface ReachabilityProvider {
            SeededReachability get(Runnable budgetCheck);
        }

        private final CompilationCache base;
        private final Function<MaterialRef, List<RecipeNode>> order;
        private final ReachabilityProvider reachabilityProvider;
        private final List<RouteChoice> choices = new ArrayList<>();
        private final Map<MaterialRef, RouteChoice> byMaterial = new HashMap<>();
        private SeededReachability reachability;
        private Map<MaterialRef, Long> branchStock;
        private final Set<MaterialRef> stockedFamilies = new HashSet<>();
        private boolean started;

        private RouteSearch(CompilationCache base, Function<MaterialRef, List<RecipeNode>> order,
                            ReachabilityProvider reachabilityProvider) {
            this.base = base;
            this.order = order;
            this.reachabilityProvider = reachabilityProvider;
        }

        @Nullable
        Prepared prepare(Map<MaterialRef, Long> stock, List<IngredientRef> roots,
                         Set<MaterialRef> activeOutputs, int maxSteps, int maxStates, Runnable checkBudget) {
            try {
                // 本游标每次返回时库存均由父帧恢复；分析只属于该帧，绝不进入公共结构缓存。
                branchStock = stock;
                stockedFamilies.clear();
                for (var entry : stock.entrySet()) {
                    checkBudget.run();
                    if (entry.getValue() > 0L) stockedFamilies.add(Evaluation.family(entry.getKey()));
                }
                while (!started || advance(checkBudget)) {
                    started = true;
                    CompilationCache trial = new CompilationCache(base.graph, base.forced, base.preferences, 0);
                    trial.routes = this;
                    Prepared prepared = trial.prepare(stock, roots, activeOutputs, maxSteps, maxStates, checkBudget);
                    if (choices.isEmpty() || !trial.sharedDependencyFound) return null;
                    if (prepared != null) return prepared;
                }
            } catch (ProbeLimitReached exhausted) {
                // 原搜索仍可尝试部分批次与其他复杂语义；不留下任何试探库存。
            }
            return null;
        }

        private RecipeNode select(MaterialRef material, List<RecipeNode> allowed, Budget budget) {
            RouteChoice choice = byMaterial.get(material);
            if (choice == null) {
                Set<RecipeNode> candidates = new HashSet<>(allowed);
                List<RecipeNode> ordered = new ArrayList<>();
                for (RecipeNode candidate : order.apply(material)) {
                    budget.tick();
                    if (candidates.remove(candidate)) ordered.add(candidate);
                }
                // 排序提示不能删除候选；完整性和分摊仍由原搜索负责。
                for (RecipeNode candidate : allowed) {
                    budget.tick();
                    if (candidates.remove(candidate)) ordered.add(candidate);
                }
                // 复用迭代种子传播，识别任意层缺料、替代生产者及保证副产物。
                // 只调整本帧顺序，不删除候选；数量、动态 NBT 和后续库存变化仍由实际搜索裁决。
                List<RecipeNode> unavailable = new ArrayList<>();
                List<RecipeNode> supplied = new ArrayList<>();
                ResourceLocation preferred = base.preferences.preferredRecipe(material);
                for (RecipeNode candidate : ordered) {
                    boolean missingLeaf = false;
                    for (IngredientRef input : candidate.inputs()) {
                        budget.tick();
                        MaterialRef dependency = input.alternatives().get(0);
                        if (!stockedFamilies.contains(dependency)
                                && ImmutableRecipeGraphProjector.candidates(base.graph, dependency, NbtMatchMode.ANY).isEmpty()
                                && base.graph.coProductsByItem().getOrDefault(dependency.itemId(), Set.of()).isEmpty()) missingLeaf = true;
                    }
                    if (missingLeaf && !candidate.recipeId().equals(preferred)) unavailable.add(candidate);
                    else supplied.add(candidate);
                }
                // 直接叶子提示已留下唯一优先路线时，不为简单图支付整图传播费用。
                if (supplied.size() > 1) {
                    // 结果只用于排序，并复用本请求初始库存的乐观种子闭包。
                    // 实际可行性仍由分支搜索确认；不跨请求缓存库存相关结论。
                    if (reachability == null) reachability = reachabilityProvider.get(budget::tick);
                    List<RecipeNode> reachable = new ArrayList<>();
                    for (RecipeNode candidate : supplied) {
                        budget.tick();
                        if (reachability.canReach(candidate) || candidate.recipeId().equals(preferred)) reachable.add(candidate);
                        else unavailable.add(candidate);
                    }
                    supplied = reachable;
                }
                supplied.addAll(unavailable);
                choice = new RouteChoice(material, List.copyOf(supplied));
                choices.add(choice);
                byMaterial.put(material, choice);
            }
            return choice.candidates.get(choice.cursor);
        }

        private boolean advance(Runnable checkBudget) {
            while (!choices.isEmpty()) {
                checkBudget.run();
                RouteChoice last = choices.get(choices.size() - 1);
                if (++last.cursor < last.candidates.size()) return true;
                choices.remove(choices.size() - 1);
                byMaterial.remove(last.material);
            }
            return false;
        }

        private static final class RouteChoice {
            private final MaterialRef material;
            private final List<RecipeNode> candidates;
            private int cursor;

            private RouteChoice(MaterialRef material, List<RecipeNode> candidates) {
                this.material = material;
                this.candidates = candidates;
            }
        }
    }

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
        private boolean routeChoicesFound;
        private boolean sharedRouteInputsFound;
        private boolean sharedRouteProductionFound;
        private boolean routeSpecificProductionFound;
        private boolean sharedDependencyFound;
        private RouteSearch routes;
        private Set<IngredientRef> activeRouteSpecificInputs = Set.of();

        CompilationCache(ImmutableRecipeGraph graph, Map<ResourceLocation, ResourceLocation> forced,
                         PlanningPreferences preferences, int maxUnits) {
            this.graph = graph;
            this.forced = Map.copyOf(forced);
            this.preferences = preferences == null ? PlanningPreferences.empty() : preferences;
            this.maxUnits = Math.max(0, maxUnits);
        }

        int compilationCount() { return compilationCount; }

        boolean hasRouteChoices() { return routeChoicesFound; }

        boolean hasSharedDependency() { return sharedDependencyFound; }

        /** 共同供料覆盖全部生产依赖时优先原生分摊；否则先尝试汇总候选特有依赖，失败仍回此调度。 */
        boolean prefersCommonSupply() { return sharedRouteProductionFound && !routeSpecificProductionFound; }

        /**
         * 全部候选专属输入都能由现有库存或一步静态配方供给时，完整路线探测收益很低。
         * 让原搜索处理这些库存竞争，避免在简单但很大的共享图上先遍历整张候选图。
         */
        boolean needsTransitiveRouteProbe(Map<MaterialRef, Long> stock, Runnable chargeWork) {
            for (IngredientRef input : activeRouteSpecificInputs) {
                chargeWork.run();
                if (stockCanSatisfy(stock, input, input.count(), chargeWork)) continue;
                if (!hasOneStepStockSource(stock, input, chargeWork)) return true;
            }
            return false;
        }

        private boolean hasOneStepStockSource(Map<MaterialRef, Long> stock, IngredientRef demand,
                                              Runnable chargeWork) {
            for (MaterialRef alternative : demand.alternatives()) {
                chargeWork.run();
                for (RecipeNode producer : ImmutableRecipeGraphProjector.candidates(
                        graph, alternative, demand.nbtMatchMode())) {
                    chargeWork.run();
                    if (producer.dynamicIdentityTargetable() || producer.hasFluidOutputs()
                            || producer.guaranteedOutputs().size() != 1
                            || !producer.guaranteedOutputs().containsKey(alternative)) continue;
                    long output = producer.guaranteedOutputs().get(alternative);
                    if (output <= 0L) continue;
                    long batches = demand.count() / output + (demand.count() % output == 0L ? 0L : 1L);
                    boolean supplied = true;
                    for (IngredientRef input : PureDemandNormalizer.mergeEquivalent(producer.inputs())) {
                        chargeWork.run();
                        if (input.role() != DemandRole.CONSUMED || !supported(input)) {
                            supplied = false;
                            break;
                        }
                        long needed;
                        try {
                            needed = Math.multiplyExact(input.count(), batches);
                        } catch (ArithmeticException overflow) {
                            supplied = false;
                            break;
                        }
                        if (!stockCanSatisfy(stock, input, needed, chargeWork)) {
                            supplied = false;
                            break;
                        }
                    }
                    if (supplied) return true;
                }
            }
            return false;
        }

        private static boolean stockCanSatisfy(Map<MaterialRef, Long> stock, IngredientRef demand,
                                               long required, Runnable chargeWork) {
            long available = 0L;
            for (Map.Entry<MaterialRef, Long> entry : stock.entrySet()) {
                chargeWork.run();
                Long amount = entry.getValue();
                if (amount == null || amount <= 0L
                        || !ImmutableRecipeGraphProjector.matchesIngredient(entry.getKey(), demand)) continue;
                if (Long.MAX_VALUE - available < amount) {
                    available = Long.MAX_VALUE;
                    break;
                }
                available += amount;
            }
            return available >= required;
        }

        RouteSearch routes(Function<MaterialRef, List<RecipeNode>> order,
                           RouteSearch.ReachabilityProvider reachabilityProvider) {
            return new RouteSearch(this, order, reachabilityProvider);
        }

        @Nullable
        Prepared prepare(Map<MaterialRef, Long> stock, List<IngredientRef> roots,
                         Set<MaterialRef> activeOutputs, int maxSteps, int maxStates,
                         Runnable checkBudget) {
            routeChoicesFound = false;
            sharedRouteInputsFound = false;
            sharedRouteProductionFound = false;
            routeSpecificProductionFound = false;
            sharedDependencyFound = false;
            activeRouteSpecificInputs = Set.of();
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
                    activeRouteSpecificInputs = program.routeSpecificInputs();
                    Evaluation evaluation = new Evaluation(program, stock, activeOutputs, maxSteps, budget);
                    Prepared result = evaluation.build(roots, this);
                    if (evaluation.expandStockLeaves.isEmpty()) {
                        if (changed) {
                            Program previous = programs.remove(key);
                            if (previous != null) retainedUnits -= programUnits(key, previous);
                            if (retain(programUnits(key, program))) programs.put(key, program);
                        }
                        Set<MaterialRef> boundaries = new HashSet<>();
                        while (!evaluation.isolateCoProducts.isEmpty()) {
                            // 只隔离边界依赖闭包能够接触的联产节点；每次隔离都减少确定性节点。
                            // 这是本次评估的调度选择，不覆盖可复用的原始编译结构。
                            boundaries.addAll(evaluation.isolateCoProducts);
                            Program isolated = compile(key, budget, program.stockLeaves(), boundaries);
                            if (isolated == null) return null;
                            evaluation = new Evaluation(isolated, stock, activeOutputs, maxSteps, budget);
                            result = evaluation.build(roots, this);
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
            if (cached != null) {
                routeChoicesFound |= !cached.alternatives().isEmpty();
                return cached;
            }
            RecipeNode selected = null;
            List<RecipeNode> alternatives = new ArrayList<>();
            boolean same = true;
            boolean branchable = true;
            if (!preferences.isNoRecipe(material)) {
                ResourceLocation forcedId = ForcedRecipeGraphProjector.forcedRecipeFor(material, forced);
                // ANY 还可能匹配带 NBT 的生产者，必须检查整个物品族。
                for (RecipeNode recipe : ImmutableRecipeGraphProjector.candidates(graph, material, NbtMatchMode.ANY)) {
                    budget.tick();
                    if (preferences.excludes(recipe.recipeId())
                            || forcedId != null && !forcedId.equals(recipe.recipeId())) continue;
                    if (!recipe.output().equals(material)
                            || recipe.dynamicIdentityTargetable() || recipe.hasFluidOutputs()
                            || recipe.inputs().stream().anyMatch(input -> !supported(input))) {
                        selected = null;
                        alternatives.clear();
                        break;
                    }
                    if (selected != null && !equivalent(selected, recipe, budget)) same = false;
                    // 分支选路暂仅覆盖静态单输出；联产的既有安全隔离保持原样。
                    branchable &= recipe.guaranteedOutputs().size() == 1;
                    alternatives.add(recipe);
                    ResourceLocation preferred = preferences.preferredRecipe(material);
                    if (selected == null || recipe.recipeId().equals(preferred)
                            || !selected.recipeId().equals(preferred)
                            && recipe.recipeId().toString().compareTo(selected.recipeId().toString()) < 0) {
                        selected = recipe;
                    }
                }
            }
            if (!same) {
                if (!branchable) alternatives.clear();
                routeChoicesFound |= !alternatives.isEmpty();
                selected = routes != null && !alternatives.isEmpty()
                        ? routes.select(material, alternatives, budget) : null;
            } else {
                alternatives.clear();
            }
            Definition result = selected == null ? boundaryDefinition(material, alternatives, budget)
                    : new Definition(material, selected, PureDemandNormalizer.mergeEquivalent(selected.inputs()),
                            List.copyOf(alternatives), false);
            if (retain(Math.addExact(1, Math.addExact(result.inputs().size(), result.alternatives().size())))) {
                definitions.put(material, result);
            }
            return result;
        }

        /**
         * 单输出路线中，每批都必需的相同消耗端口可以先汇总供料。
         * 不同产量按最大产量计算必需批次的下界；实际选路仍可追加供料，不能据此登记产物。
         * 仍不选择配方、不扣除这些输入；后续 Boundary 按真实路线消费并可拆分批次。
         */
        private Definition boundaryDefinition(MaterialRef material, List<RecipeNode> alternatives, Budget budget) {
            if (alternatives.isEmpty()) return new Definition(material, null, List.of());
            RecipeNode first = alternatives.get(0);
            List<IngredientRef> common = new ArrayList<>(PureDemandNormalizer.mergeEquivalent(first.inputs()));
            Set<IngredientRef> allInputs = new HashSet<>();
            for (RecipeNode candidate : alternatives) {
                budget.tick();
                Set<IngredientRef> inputs = new HashSet<>();
                for (IngredientRef input : PureDemandNormalizer.mergeEquivalent(candidate.inputs())) {
                    budget.tick();
                    inputs.add(input);
                    allInputs.add(input);
                }
                common.removeIf(input -> !inputs.contains(input));
            }
            allInputs.removeAll(new HashSet<>(common));
            boolean specificProduction = false;
            for (IngredientRef input : allInputs) {
                budget.tick();
                if (!ImmutableRecipeGraphProjector.candidates(graph, input.alternatives().get(0),
                        NbtMatchMode.ANY).isEmpty()) specificProduction = true;
            }
            List<IngredientRef> routeSpecificInputs = List.copyOf(allInputs);
            return new Definition(material, null, List.copyOf(common), List.copyOf(alternatives), specificProduction,
                    routeSpecificInputs);
        }

        /**
         * 仅折叠逐批供需和执行属性完全相同的路线，不按物品 ID 合并，也不缓存库存选择。
         * 输入顺序保留端口语义；不同原料、产量、成本或副产物仍是可回退的搜索边界。
         */
        private static boolean equivalent(RecipeNode left, RecipeNode right, Budget budget) {
            if (left.outputCount() != right.outputCount()
                    || !left.modTypeId().equals(right.modTypeId())
                    || !left.recipeTypeId().equals(right.recipeTypeId())
                    || !left.magicCosts().equals(right.magicCosts())
                    || left.inputs().size() != right.inputs().size()
                    || left.guaranteedOutputs().size() != right.guaranteedOutputs().size()) return false;
            for (int port = 0; port < left.inputs().size(); port++) {
                budget.tick();
                if (!left.inputs().get(port).equals(right.inputs().get(port))) return false;
            }
            for (var output : left.guaranteedOutputs().entrySet()) {
                budget.tick();
                if (!output.getValue().equals(right.guaranteedOutputs().get(output.getKey()))) return false;
            }
            return true;
        }

        /**
         * 不依赖库存地遍历边界的所有候选和输入，包括当前缺料的路线。
         * 副产物与这个闭包不相交时，边界不可能提前消费它，外围 DAG 可以继续聚合。
         * 按资源类型和物品族保守匹配 NBT；遇到动态身份则不做局部安全假设。
         */
        private Set<MaterialRef> affectedCoProducts(Program program, Set<MaterialRef> missing,
                                                   Budget budget) {
            Set<MaterialRef> touched = new HashSet<>();
            Set<MaterialRef> visited = new HashSet<>();
            ArrayDeque<MaterialRef> pending = new ArrayDeque<>(missing);
            boolean dynamic = false;
            while (!pending.isEmpty()) {
                budget.tick();
                MaterialRef material = Evaluation.family(pending.removeFirst());
                touched.add(material);
                if (!visited.add(material)) continue;
                // 安全分析宁可包含被偏好或强制规则排除的路线，也不漏掉变体生产者。
                for (RecipeNode recipe : ImmutableRecipeGraphProjector.candidates(graph, material, NbtMatchMode.ANY)) {
                    budget.tick();
                    dynamic |= recipe.dynamicIdentityTargetable();
                    for (MaterialRef output : recipe.guaranteedOutputs().keySet()) {
                        budget.tick();
                        touched.add(Evaluation.family(output));
                        dynamic |= output.runtimeNbt() || output.constrainedNbt();
                    }
                    for (IngredientRef input : recipe.inputs()) {
                        for (MaterialRef alternative : input.alternatives()) {
                            budget.tick();
                            touched.add(Evaluation.family(alternative));
                            if (input.recursive()) pending.addLast(alternative);
                        }
                    }
                }
            }
            Set<MaterialRef> affected = new HashSet<>();
            for (Node node : program.ordered()) {
                budget.tick();
                if (node.recipe == null || node.recipe.guaranteedOutputs().size() == 1) continue;
                for (MaterialRef output : node.recipe.guaranteedOutputs().keySet()) {
                    budget.tick();
                    if (!output.equals(node.material) && (dynamic || touched.contains(Evaluation.family(output)))) {
                        affected.add(node.material);
                    }
                }
            }
            return affected;
        }

        @Nullable
        private Program compile(List<MaterialRef> roots, Budget budget, Set<MaterialRef> stockBoundaries) {
            return compile(roots, budget, stockBoundaries, Set.of());
        }

        @Nullable
        private Program compile(List<MaterialRef> roots, Budget budget, Set<MaterialRef> stockBoundaries,
                                Set<MaterialRef> coProductBoundaries) {
            compilationCount++;
            Map<MaterialRef, Node> nodes = new HashMap<>();
            List<Node> ordered = new ArrayList<>();
            Set<MaterialRef> stockLeaves = new HashSet<>();
            Set<IngredientRef> routeSpecificInputs = new HashSet<>();
            for (MaterialRef root : roots) {
                if (nodes.containsKey(root)) continue;
                Node first = new Node(nodes.size(), coProductBoundaries.contains(root)
                        ? new Definition(root, null, List.of()) : definition(root, budget, stockBoundaries, stockLeaves), budget);
                routeSpecificInputs.addAll(first.routeSpecificInputs);
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
                    if (child != null && child.complete) sharedDependencyFound = true;
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
                        child = new Node(nodes.size(), coProductBoundaries.contains(material)
                                ? new Definition(material, null, List.of())
                                : definition(material, budget, stockBoundaries, stockLeaves), budget);
                        routeSpecificInputs.addAll(child.routeSpecificInputs);
                        nodes.put(material, child);
                        stack.push(child);
                    }
                    current.children.add(child);
                }
            }
            // 只允许不参与本段供需的旁路产物。按资源类型和 NBT 物品族检查，
            // 防止 ANY 输入、另一投影或库存叶子与副产物相交时重复安排生产。
            // 交叉联产交给原搜索，不能将它误判成确定性环或永久缓存失败。
            Set<MaterialRef> intersecting = new HashSet<>();
            for (Node node : ordered) {
                if (node.recipe == null || node.recipe.guaranteedOutputs().size() == 1) continue;
                for (MaterialRef output : node.recipe.guaranteedOutputs().keySet()) {
                    budget.tick();
                    if (!output.equals(node.material) && nodes.containsKey(Evaluation.family(output))) {
                        intersecting.add(node.material);
                    }
                }
            }
            // 一次截住所有交叉节点；重编译只会缩小图，不会再发现新的交叉。
            if (!intersecting.isEmpty()) {
                intersecting.addAll(coProductBoundaries);
                return compile(roots, budget, stockBoundaries, intersecting);
            }
            return new Program(Map.copyOf(nodes), List.copyOf(ordered), Set.copyOf(stockLeaves),
                    Set.copyOf(routeSpecificInputs));
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
        private final boolean routeChoice;
        private final boolean routeSpecificProduction;
        private final List<IngredientRef> routeSpecificInputs;
        private final long commonOutputCount;
        private final List<Node> children = new ArrayList<>();
        private int cursor;
        private boolean complete;

        private Node(int id, Definition definition, Budget budget) {
            this.id = id;
            this.material = definition.material();
            this.recipe = definition.recipe();
            this.inputs = definition.inputs();
            this.routeChoice = !definition.alternatives().isEmpty();
            this.routeSpecificProduction = definition.routeSpecificProduction();
            this.routeSpecificInputs = definition.routeSpecificInputs();
            long maximumOutput = 0L;
            if (recipe == null && routeChoice && !inputs.isEmpty()) {
                for (RecipeNode candidate : definition.alternatives()) {
                    budget.tick();
                    maximumOutput = Math.max(maximumOutput, candidate.outputCount());
                }
            }
            this.commonOutputCount = maximumOutput;
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
        private Set<MaterialRef> isolateCoProducts = Set.of();

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
        private Prepared build(List<IngredientRef> roots, CompilationCache compilation) {
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
            boolean producesCoProducts = false;
            Set<MaterialRef> missingBoundaries = new HashSet<>();
            // 先收齐所有消费者的需求，再展开其生产者，避免分支间重复取整和展开。
            for (int index = ordered.size() - 1; index >= 0; index--) {
                budget.tick();
                Node node = ordered.get(index);
                compilation.routeChoicesFound |= node.routeChoice;
                compilation.sharedRouteInputsFound |= node.commonOutputCount > 0L;
                if (demand[node.id] > 0L && variants.getOrDefault(node.material, List.of()).stream()
                        .anyMatch(variant -> variant.runtimeNbt() || variant.constrainedNbt())) return null;
                long missing = Math.max(0L, Math.max(
                        demand[node.id] - familyStock.getOrDefault(node.material, 0L),
                        exactDemand[node.id] - initial.getOrDefault(node.material, 0L)));
                if (missing == 0L) continue;
                compilation.routeSpecificProductionFound |= node.routeSpecificProduction;
                if (program.stockLeaves().contains(node.material) && !activeOutputs.contains(node.material)) {
                    expandStockLeaves.add(node.material);
                }
                if (node.recipe == null || activeOutputs.contains(node.material)) {
                    needsBoundary = true;
                    missingBoundaries.add(node.material);
                    if (node.commonOutputCount > 0L && !activeOutputs.contains(node.material)) {
                        long count = missing / node.commonOutputCount
                                + (missing % node.commonOutputCount == 0L ? 0L : 1L);
                        for (int port = 0; port < node.inputs.size(); port++) {
                            budget.tick();
                            Node dependency = node.children.get(port);
                            // 只有共同输入确实展开生产，才优先保留原生分摊调度；库存辅助料不截断主链探测。
                            compilation.sharedRouteProductionFound |= dependency.recipe != null || dependency.routeChoice;
                            int child = dependency.id;
                            long amount = Math.multiplyExact(node.inputs.get(port).count(), count);
                            demand[child] = Math.addExact(demand[child], amount);
                            if (node.inputs.get(port).nbtMatchMode() == NbtMatchMode.EXACT) {
                                exactDemand[child] = Math.addExact(exactDemand[child], amount);
                            }
                        }
                    }
                    continue;
                }
                if (++steps > maxSteps) return null;
                if (node.recipe.guaranteedOutputs().size() > 1) {
                    producesCoProducts = true;
                    if (!activeOutputs.isEmpty()) {
                        for (MaterialRef output : node.recipe.guaranteedOutputs().keySet()) {
                            budget.tick();
                            if (activeOutputs.contains(output) || activeOutputs.contains(family(output))) return null;
                        }
                    }
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
            // 库存切点不足时先展开再分析；它们不是未知语义边界，不能重复扫描整条闭包。
            if (!expandStockLeaves.isEmpty()) return null;
            // 整段都由可选路线组成时也能先汇总共同供料，不要求人为添加唯一生产者根节点。
            // 没有共同供料的纯边界仍交回原搜索，避免生成无效的补料包装。
            if (steps == 0 && !compilation.sharedRouteInputsFound) return null;
            if (needsBoundary) {
                if (producesCoProducts) {
                    isolateCoProducts = compilation.affectedCoProducts(program, missingBoundaries, budget);
                    if (!isolateCoProducts.isEmpty()) return null;
                }
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
                for (var output : node.recipe.guaranteedOutputs().entrySet()) {
                    if (node.recipe.guaranteedOutputs().size() > 1) budget.tick();
                    remaining.merge(output.getKey(), Math.multiplyExact(output.getValue(), count), Math::addExact);
                }
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
