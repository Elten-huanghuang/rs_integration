package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.MaterialRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.RecipeNode;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** 纯规划失败后检查目录覆盖缺口；成功方案无需为其他机器路线回退。 */
final class PurePlanningCoverage {
    private PurePlanningCoverage() {}

    @Nullable
    static MaterialRef unprojectedDependency(ImmutableRecipeGraph graph, ResourceLocation targetId,
                                             Set<ResourceLocation> incompleteOutputs,
                                             Map<ResourceLocation, ResourceLocation> forcedRecipes,
                                             PlanningPreferences preferences) {
        if (incompleteOutputs.isEmpty()) return null;
        RecipeNode target = graph.recipesById().get(targetId);
        if (target == null) return null;
        ArrayDeque<RecipeNode> pending = new ArrayDeque<>();
        Set<ResourceLocation> visited = new HashSet<>();
        pending.add(target);
        while (!pending.isEmpty()) {
            PlanningThreadContext.throwIfCancelled();
            RecipeNode recipe = pending.removeFirst();
            if (!visited.add(recipe.recipeId()) || preferences.excludes(recipe.recipeId())) continue;
            for (var input : recipe.inputs()) {
                if (!input.recursive()) continue;
                for (MaterialRef material : input.alternatives()) {
                    ResourceLocation forced = ForcedRecipeGraphProjector.forcedRecipeFor(material, forcedRecipes);
                    if (forced == null && preferences.isNoRecipe(material)) continue;
                    if (incompleteOutputs.contains(material.itemId())
                            && (forced == null || !graph.recipesById().containsKey(forced))) return material;
                    for (RecipeNode producer : ImmutableRecipeGraphProjector.candidates(
                            graph, material, input.nbtMatchMode())) {
                        if (forced == null || forced.equals(producer.recipeId())) pending.addLast(producer);
                    }
                }
            }
        }
        return null;
    }
}
