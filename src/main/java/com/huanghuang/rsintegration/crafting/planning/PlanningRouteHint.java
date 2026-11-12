package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.MaterialRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.RecipeNode;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** 旧预览只提供候选配方，新请求必须重新分配库存并生成完整材料流。 */
public record PlanningRouteHint(PlanningSnapshot previous, List<RecipeNode> recipes) {
    public PlanningRouteHint {
        recipes = List.copyOf(recipes);
    }

    @Nullable
    public static PlanningRouteHint from(@Nullable PlanCache.Entry entry) {
        if (entry == null || entry.snapshot().mainThreadOnly()) return null;
        var ids = new LinkedHashSet<ResourceLocation>();
        ids.add(entry.snapshot().recipeId());
        if (entry.purePlan() != null) {
            entry.purePlan().steps().forEach(step -> ids.add(step.recipeId()));
        } else if (entry.plan().graph() != null) {
            entry.plan().graph().nodes().forEach(node -> ids.add(node.recipeId()));
        } else {
            // 缺料纯预览没有可执行图，缓存也不保留其失败 Result；服务端展示步骤仍保留路线。
            entry.plan().steps().forEach(step -> ids.add(step.recipeId()));
        }
        List<RecipeNode> recipes = new ArrayList<>();
        for (ResourceLocation id : ids) {
            RecipeNode recipe = entry.snapshot().recipeGraph().recipesById().get(id);
            if (recipe == null) return null;
            recipes.add(recipe);
        }
        return new PlanningRouteHint(entry.snapshot(), recipes);
    }

    @Nullable
    ImmutableRecipeGraph graphFor(PlanningSnapshot current) {
        PlanningThreadContext.throwIfCancelled();
        if (previous.mainThreadOnly() || current.mainThreadOnly()
                || !previous.playerId().equals(current.playerId())
                || previous.recipeRevision() != current.recipeRevision()
                || !previous.recipeId().equals(current.recipeId())
                || !previous.forcedRecipes().equals(current.forcedRecipes())
                || !previous.preferences().equals(current.preferences())
                || !previous.bindingFingerprint().equals(current.bindingFingerprint())
                || !previous.bindingBlockedOutputIds().equals(current.bindingBlockedOutputIds())
                || !PlanningStateValidator.networkIdentity(previous.networkFingerprint()).equals(
                        PlanningStateValidator.networkIdentity(current.networkFingerprint()))) return null;
        Map<MaterialRef, List<RecipeNode>> outputs = new LinkedHashMap<>();
        for (RecipeNode recipe : recipes) {
            PlanningThreadContext.throwIfCancelled();
            // 即使重载代际漏变，也不能使用内容已经变更或删除的旧配方。
            if (!recipe.equals(current.recipeGraph().recipesById().get(recipe.recipeId()))) return null;
            outputs.computeIfAbsent(recipe.output(), ignored -> new ArrayList<>()).add(recipe);
        }
        ImmutableRecipeGraph graph = new ImmutableRecipeGraph(outputs);
        return graph.recipesById().containsKey(current.recipeId()) ? graph : null;
    }
}
