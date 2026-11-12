package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.crafting.graph.CraftPlanGraph;
import com.huanghuang.rsintegration.crafting.graph.MaterialKey;
import com.huanghuang.rsintegration.crafting.graph.MaterialSource;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.ItemStack;
import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.consumption.InputConsumption;

import javax.annotation.Nullable;
import java.util.ArrayList;
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
        if (graph == null) return new InitialMaterialPlan(Map.of(), List.of());
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

    record Step(List<IngredientSpec> inputs, ItemStack output, int executions) {}

    /** 兼容只传步骤的旧入口：逐步扣除上游产物，不能把所有步骤投入直接相加。 */
    @Nullable
    static InitialMaterialPlan fromSteps(List<Step> steps, Map<StackKey, Long> available) {
        Map<MaterialKey, Long> external = new LinkedHashMap<>();
        available.forEach((key, quantity) -> external.put(MaterialKey.of(key.toStack()), quantity));
        Map<MaterialKey, Long> produced = new LinkedHashMap<>();
        Map<MaterialKey, Long> required = new LinkedHashMap<>();
        List<Ingredient> reusable = new ArrayList<>();
        for (Step step : steps) {
            Map<MaterialKey, Long> returned = new LinkedHashMap<>();
            for (IngredientSpec input : step.inputs()) {
                if (input.isEmpty()) continue;
                long needed = CraftPacketUtils.requiredCount(input, (long) step.executions());
                if (input.consumption().reusableWithinWorkerBatch()) reusable.add(input.ingredient());
                for (Map<MaterialKey, Long> pool : List.of(produced, external)) {
                    for (Map.Entry<MaterialKey, Long> candidate : pool.entrySet()) {
                        if (needed == 0) break;
                        if (!IngredientMatcher.test(input.ingredient(), candidate.getKey().toStack(1))) continue;
                        long take = Math.min(needed, candidate.getValue());
                        if (take <= 0) continue;
                        candidate.setValue(candidate.getValue() - take);
                        if (pool == external) required.merge(candidate.getKey(), take, Math::addExact);
                        if (input.consumption().kind() == InputConsumption.Kind.CATALYST) {
                            returned.merge(candidate.getKey(), take, Math::addExact);
                        }
                        needed -= take;
                    }
                }
                if (needed > 0) return null;
            }
            returned.forEach((key, amount) -> produced.merge(key, amount, Math::addExact));
            if (!step.output().isEmpty()) produced.merge(MaterialKey.of(step.output()),
                    Math.multiplyExact((long) step.output().getCount(), step.executions()), Math::addExact);
        }
        return new InitialMaterialPlan(required, reusable);
    }
}
