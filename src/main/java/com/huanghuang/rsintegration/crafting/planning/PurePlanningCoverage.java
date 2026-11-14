package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.MaterialRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.RecipeNode;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** 有界探测纯图的目录覆盖缺口；不确定时保守交给类型解析器。 */
public final class PurePlanningCoverage {
    private PurePlanningCoverage() {}

    public enum Status { NOT_CHECKED, COMPLETE, GAP, UNCERTAIN }

    /** 未完成探测的前沿材料不是已证实缺口，更不是缺料结论。 */
    public record Probe(@Nullable MaterialRef uncovered, Status status) {
        public static Probe notChecked() {
            return new Probe(null, Status.NOT_CHECKED);
        }
    }

    static Probe unprojectedDependency(ImmutableRecipeGraph graph, ResourceLocation targetId,
                                             Set<ResourceLocation> incompleteOutputs,
                                             Map<ResourceLocation, ResourceLocation> forcedRecipes,
                                             PlanningPreferences preferences, long deadlineNanos) {
        if (incompleteOutputs.isEmpty()) return new Probe(null, Status.COMPLETE);
        RecipeNode target = graph.recipesById().get(targetId);
        if (target == null) return Probe.notChecked();
        ArrayDeque<RecipeNode> pending = new ArrayDeque<>();
        Set<ResourceLocation> visited = new HashSet<>();
        pending.add(target);
        visited.add(target.recipeId());
        int checkedMaterials = 0;
        while (!pending.isEmpty()) {
            PlanningThreadContext.throwIfCancelled();
            RecipeNode recipe = pending.removeFirst();
            if (preferences.excludes(recipe.recipeId())) continue;
            for (var input : recipe.inputs()) {
                if (!input.recursive()) continue;
                for (MaterialRef material : input.alternatives()) {
                    if (++checkedMaterials > 4096 || System.nanoTime() >= deadlineNanos) return new Probe(material, Status.UNCERTAIN);
                    ResourceLocation forced = ForcedRecipeGraphProjector.forcedRecipeFor(material, forcedRecipes);
                    if (forced == null && preferences.isNoRecipe(material)) continue;
                    if (incompleteOutputs.contains(material.itemId())
                            && (forced == null || !graph.recipesById().containsKey(forced))) return new Probe(material, Status.GAP);
                    for (RecipeNode producer : ImmutableRecipeGraphProjector.candidates(
                            graph, material, input.nbtMatchMode())) {
                        if ((forced == null || forced.equals(producer.recipeId()))
                                && !preferences.excludes(producer.recipeId())
                                && visited.add(producer.recipeId())) pending.addLast(producer);
                    }
                }
            }
        }
        return new Probe(null, Status.COMPLETE);
    }
}
