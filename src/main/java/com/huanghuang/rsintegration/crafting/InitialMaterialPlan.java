package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.crafting.graph.CraftPlanGraph;
import com.huanghuang.rsintegration.crafting.graph.MaterialKey;
import com.huanghuang.rsintegration.crafting.graph.MaterialSource;
import net.minecraft.world.item.crafting.Ingredient;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 开工前需要从公共来源取得的材料，不包含图内上游生产的材料。 */
record InitialMaterialPlan(Map<MaterialKey, Long> materials, List<Ingredient> reusable) {
    InitialMaterialPlan {
        materials = Map.copyOf(materials);
        reusable = List.copyOf(reusable);
    }

    static InitialMaterialPlan from(CraftPlanGraph graph) {
        Map<MaterialKey, Long> required = new LinkedHashMap<>();
        graph.allocations().forEach(allocation -> {
            if (allocation.source() instanceof MaterialSource.InitialPool) {
                required.merge(allocation.material(), allocation.quantity(), Math::addExact);
            }
        });
        graph.rootDemands().forEach(root -> root.allocations().forEach(allocation -> {
            if (allocation.source() instanceof MaterialSource.InitialPool) {
                required.merge(allocation.material(), allocation.quantity(), Math::addExact);
            }
        }));
        List<Ingredient> reusable = graph.nodes().stream().flatMap(node -> node.inputs().stream())
                .filter(input -> input.consumption().reusableWithinWorkerBatch())
                .map(input -> input.ingredient()).toList();
        return new InitialMaterialPlan(required, reusable);
    }

}
