package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.IngredientRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.MaterialRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.NbtMatchMode;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.RecipeNode;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 在本次纯图及其完整初始库存中寻找一条供给上界为零的必经依赖。 */
public final class PureSupplyProof {
    private PureSupplyProof() {}

    public record Port(ResourceLocation recipe, int slot, MaterialRef material, NbtMatchMode match) {}
    public record Witness(List<Port> path) {
        public Witness { path = List.copyOf(path); }
    }

    private record Visit(RecipeNode recipe, @Nullable Path parent) {}
    private record Path(Port port, boolean reliesOnSelection, @Nullable Path parent) {}
    private record Source(@Nullable RecipeNode recipe, boolean reliesOnSelection, boolean uncertain) {
        private static final Source UNKNOWN = new Source(null, false, true);
    }

    static PurePlanningCoverage.Probe check(PurePlanningCoverage.Probe coverage, ImmutableRecipeGraph graph,
            ResourceLocation targetId, Map<MaterialRef, Long> stock, Map<ResourceLocation, ResourceLocation> forced,
            PlanningPreferences preferences, Set<ResourceLocation> catalystOutputs, Set<ResourceLocation> catalystRecipes,
            int work, long deadline) {
        if (coverage.status() != PurePlanningCoverage.Status.GAP || coverage.uncovered() == null
                || !preferences.ingredientChoices().isEmpty()) return coverage;
        RecipeNode target = graph.recipesById().get(targetId);
        // 有其他终端生产者时，反向换算策略可能改为直接规划终端产物。
        if (target == null || graph.recipesByOutput().getOrDefault(target.output(), List.of()).size() != 1
                || !fixed(target) || catalystRecipes.contains(targetId)) return coverage;
        var budget = new Budget(work, deadline);
        try {
            // 根复制配方按净增量规划，不能把其输入当作必经根槽。
            for (var input : target.inputs()) {
                budget.tick();
                for (var alt : input.alternatives()) {
                    budget.tick();
                    if (alt.resourceId().equals(target.output().resourceId())) return coverage;
                }
            }
            var outputs = index(graph.recipesByOutput().keySet(), budget);
            var inventory = index(stock.keySet(), budget);
            var pending = new ArrayDeque<Visit>();
            var visited = new HashSet<ResourceLocation>();
            pending.add(new Visit(target, null));
            visited.add(targetId);
            while (!pending.isEmpty()) {
                Visit visit = pending.removeFirst();
                var ports = visit.recipe.inputs();
                for (int i = 0; i < ports.size(); i++) {
                    budget.tick();
                    IngredientRef input = ports.get(i);
                    if (input.role() != DemandRole.CONSUMED || !input.recursive() || input.alternatives().size() != 1) continue;
                    MaterialRef wanted = input.alternatives().get(0);
                    if (wanted.runtimeNbt() || wanted.constrainedNbt() || catalystOutputs.contains(wanted.resourceId())
                            || supplied(input, wanted, stock, inventory, graph, budget)) continue;
                    var source = source(graph, input, wanted, outputs.getOrDefault(wanted.resourceId(), List.of()),
                            ForcedRecipeGraphProjector.forcedRecipeFor(wanted, forced), preferences, budget);
                    if (source.uncertain()) continue;
                    var only = source.recipe();
                    var path = new Path(new Port(visit.recipe.recipeId(), i, wanted, input.nbtMatchMode()),
                            source.reliesOnSelection(), visit.parent);
                    if (only == null && wanted.kind() == coverage.uncovered().kind()
                            && wanted.resourceId().equals(coverage.uncovered().resourceId())) {
                        var witness = verifyPath(graph, path, budget);
                        if (witness != null) return new PurePlanningCoverage.Probe(coverage.uncovered(), coverage.status(), witness);
                    }
                    if (only != null && !preferences.isNoRecipe(wanted) && !catalystRecipes.contains(only.recipeId())
                            && visited.add(only.recipeId())) pending.addLast(new Visit(only, path));
                }
            }
        } catch (Limit ignored) {
            // 证据不完整时继续既有有界搜索。
        }
        return coverage;
    }

    private static Source source(ImmutableRecipeGraph graph, IngredientRef input, MaterialRef wanted,
            List<MaterialRef> variants, @Nullable ResourceLocation forced, PlanningPreferences preferences, Budget budget) {
        RecipeNode only = null;
        boolean usedSelection = false;
        for (var output : variants) {
            budget.tick();
            if (output.kind() != wanted.kind()) continue;
            boolean matches = ImmutableRecipeGraphProjector.matchesAlternative(output, wanted, input.nbtMatchMode());
            for (var recipe : graph.recipesByOutput().get(output)) {
                budget.tick();
                if (preferences.excludes(recipe.recipeId())) continue;
                // 动态专化可以改变输出 NBT 及输入。
                if (!fixed(recipe) || !recipe.output().equals(output)) return Source.UNKNOWN;
                if (!matches) continue;
                if (forced != null && output.equals(wanted) && !recipe.recipeId().equals(forced)) {
                    usedSelection = true;
                    continue;
                }
                if (only != null) return Source.UNKNOWN;
                only = recipe;
            }
        }
        return new Source(only, usedSelection, false);
    }

    @Nullable
    private static Witness verifyPath(ImmutableRecipeGraph graph, Path path, Budget budget) {
        var ports = new ArrayDeque<Port>();
        var restricted = new HashMap<ResourceLocation, MaterialRef>();
        for (Path step = path; step != null; step = step.parent) {
            budget.tick();
            ports.addFirst(step.port);
            if (step.reliesOnSelection) {
                var material = step.port.material();
                var previous = restricted.put(material.resourceId(), material);
                if (previous != null && !previous.equals(material)) return null;
            }
        }
        // 其他变体的槽位可能按另一强制键执行同物品配方，留下可共享的主产物余量。
        if (!restricted.isEmpty()) {
            for (var candidates : graph.recipesByOutput().values()) {
                budget.tick();
                for (var recipe : candidates) {
                    budget.tick();
                    for (var input : recipe.inputs()) {
                        budget.tick();
                        for (var alternative : input.alternatives()) {
                            budget.tick();
                            var wanted = restricted.get(alternative.resourceId());
                            if (wanted != null && (!alternative.equals(wanted) || !fixed(recipe))) return null;
                        }
                    }
                }
            }
        }
        return new Witness(List.copyOf(ports));
    }

    private static boolean fixed(RecipeNode recipe) {
        return !recipe.dynamicIdentityTargetable() && !recipe.output().runtimeNbt()
                && !recipe.output().constrainedNbt() && !"smithing".equals(recipe.modTypeId());
    }

    private static Map<ResourceLocation, List<MaterialRef>> index(Set<MaterialRef> materials, Budget budget) {
        var result = new HashMap<ResourceLocation, List<MaterialRef>>();
        for (var material : materials) {
            budget.tick();
            result.computeIfAbsent(material.resourceId(), ignored -> new ArrayList<>()).add(material);
        }
        return result;
    }

    private static boolean supplied(IngredientRef input, MaterialRef wanted, Map<MaterialRef, Long> stock,
            Map<ResourceLocation, List<MaterialRef>> inventory, ImmutableRecipeGraph graph, Budget budget) {
        for (var material : inventory.getOrDefault(wanted.resourceId(), List.of())) {
            budget.tick();
            if (stock.get(material) > 0 && possible(material, wanted, input.nbtMatchMode())) return true;
        }
        // 高估可用副产物，即使产生它的主路线未必被使用。
        for (var output : graph.coProductsByItem().getOrDefault(wanted.resourceId(), Set.of())) {
            budget.tick();
            if (possible(output, wanted, input.nbtMatchMode())) return true;
        }
        return false;
    }

    private static boolean possible(MaterialRef supply, MaterialRef wanted, NbtMatchMode mode) {
        return supply.kind() == wanted.kind() && (supply.runtimeNbt() || supply.constrainedNbt()
                || ImmutableRecipeGraphProjector.matchesAlternative(supply, wanted, mode));
    }

    private static final class Budget {
        private int work;
        private final long deadline;
        Budget(int work, long deadline) { this.work = work; this.deadline = deadline; }
        void tick() {
            PlanningThreadContext.throwIfCancelled();
            if (--work < 0 || System.nanoTime() >= deadline) throw Limit.INSTANCE;
        }
    }

    private static final class Limit extends RuntimeException {
        static final Limit INSTANCE = new Limit();
        private Limit() { super(null, null, false, false); }
    }
}
