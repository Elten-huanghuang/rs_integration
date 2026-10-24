package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.crafting.CraftingResolver;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.MaterialRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.RecipeNode;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 将玩家在计划树中选择的配方投影成纯规划图。
 *
 * <p>强制配方仍然必须经过同一套递归搜索和循环检测。若配方无法在不可变图中
 * 完整投影，则返回 {@code null}，由调用方回退到 typed 解析器。</p>
 */
public final class ForcedRecipeGraphProjector {
    private ForcedRecipeGraphProjector() {}

    @Nullable
    public static ImmutableRecipeGraph apply(
            ImmutableRecipeGraph graph,
            Map<ResourceLocation, ResourceLocation> forcedRecipes) {
        if (forcedRecipes.isEmpty()) return graph;

        Map<MaterialRef, List<RecipeNode>> projected =
                new LinkedHashMap<>(graph.recipesByOutput());
        Map<ResourceLocation, List<MaterialRef>> outputsByPreference = new LinkedHashMap<>();
        for (MaterialRef output : graph.recipesByOutput().keySet()) {
            outputsByPreference.computeIfAbsent(preferenceKey(output),
                    ignored -> new ArrayList<>()).add(output);
        }
        boolean changed = false;
        for (Map.Entry<ResourceLocation, ResourceLocation> forced : forcedRecipes.entrySet()) {
            ResourceLocation preferenceKey = forced.getKey();
            List<MaterialRef> matchingOutputs = outputsByPreference.get(preferenceKey);
            // 客户端可能保留其他配方树的显式选择。只处理当前依赖范围内存在的输出，
            // 否则一个无关的旧偏好会把普通工作台链整体推回 typed 解析器。
            if (matchingOutputs == null || matchingOutputs.isEmpty()) continue;

            RecipeNode forcedNode = graph.recipesById().get(forced.getValue());
            if (forcedNode == null
                    || !preferenceKey(forcedNode.output()).equals(preferenceKey)) {
                return null;
            }

            boolean applied = false;
            for (MaterialRef output : matchingOutputs) {
                // 强制选择只影响执行优先级，不删除其他候选；否则响应里的配方树
                // 会只剩当前配方，玩家无法从 B 再切回 A。
                List<RecipeNode> candidates = new ArrayList<>(
                        projected.getOrDefault(output, List.of()));
                if (!candidates.isEmpty()
                        && candidates.get(0).recipeId().equals(forcedNode.recipeId())) {
                    applied = true;
                    continue;
                }
                candidates.removeIf(candidate ->
                        candidate.recipeId().equals(forcedNode.recipeId()));
                candidates.add(0, forcedNode);
                projected.put(output, candidates);
                applied = true;
                changed = true;
            }
            if (!applied) return null;
        }
        return changed ? new ImmutableRecipeGraph(projected) : graph;
    }

    /** 返回该输出上玩家显式选择的配方；未选择时返回 null。 */
    @Nullable
    public static ResourceLocation forcedRecipeFor(
            MaterialRef output, Map<ResourceLocation, ResourceLocation> forcedRecipes) {
        return forcedRecipes.get(preferenceKey(output));
    }

    public static ResourceLocation preferenceKey(MaterialRef output) {
        return output.nbt().isEmpty()
                ? output.itemId() : stackPreferenceKey(output.itemId(), output.nbt());
    }

    private static ResourceLocation stackPreferenceKey(ResourceLocation itemId, String nbt) {
        String identity = itemId + "|" + nbt;
        UUID hash = UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
        return new ResourceLocation("rs_integration",
                "stack/" + hash.toString().replace("-", ""));
    }
}
