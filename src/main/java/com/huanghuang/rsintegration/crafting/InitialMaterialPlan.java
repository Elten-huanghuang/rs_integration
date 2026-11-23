package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.crafting.graph.CraftPlanGraph;
import com.huanghuang.rsintegration.crafting.graph.MaterialKey;
import com.huanghuang.rsintegration.crafting.graph.MaterialSource;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 开工前需要从公共来源取得的材料，不包含图内上游生产的材料。 */
record InitialMaterialPlan(Map<MaterialKey, Long> materials, List<Ingredient> reusable,
                           Map<MaterialKey, Map<ResourceLocation, Long>> sourceShares) {
    InitialMaterialPlan(Map<MaterialKey, Long> materials, List<Ingredient> reusable) {
        this(materials, reusable, Map.of());
    }

    InitialMaterialPlan {
        materials = Collections.unmodifiableMap(new LinkedHashMap<>(materials));
        reusable = List.copyOf(reusable);
        var shares = new LinkedHashMap<MaterialKey, Map<ResourceLocation, Long>>();
        sourceShares.forEach((key, sources) ->
                shares.put(key, Collections.unmodifiableMap(new LinkedHashMap<>(sources))));
        sourceShares = Collections.unmodifiableMap(shares);
    }

    static InitialMaterialPlan from(CraftPlanGraph graph) {
        Map<MaterialKey, Long> required = new LinkedHashMap<>();
        Map<MaterialKey, Map<ResourceLocation, Long>> shares = new LinkedHashMap<>();
        graph.allocations().forEach(a -> collect(a.source(), a.material(), a.quantity(), required, shares));
        graph.rootDemands().forEach(root -> root.allocations().forEach(a ->
                collect(a.source(), a.material(), a.quantity(), required, shares)));
        List<Ingredient> reusable = graph.nodes().stream().flatMap(node -> node.inputs().stream())
                .filter(input -> input.consumption().reusableWithinWorkerBatch())
                .map(input -> input.ingredient()).toList();
        return new InitialMaterialPlan(required, reusable, shares);
    }

    private static void collect(MaterialSource source, MaterialKey material, long count,
                                Map<MaterialKey, Long> required, Map<MaterialKey, Map<ResourceLocation, Long>> shares) {
        if (!(source instanceof MaterialSource.InitialPool initial)) return;
        required.merge(material, count, Math::addExact);
        if (initial.extensionId() != null) {
            shares.computeIfAbsent(material, ignored -> new LinkedHashMap<>())
                    .merge(initial.extensionId(), count, Math::addExact);
        }
    }
}
