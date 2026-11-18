package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.crafting.graph.CraftNode;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanGraph;
import com.huanghuang.rsintegration.crafting.graph.MaterialKey;
import com.huanghuang.rsintegration.crafting.graph.MaterialSource;
import com.huanghuang.rsintegration.crafting.graph.NodeId;
import com.huanghuang.rsintegration.crafting.graph.OutputDeclaration;
import com.huanghuang.rsintegration.crafting.graph.OutputKind;
import com.huanghuang.rsintegration.crafting.graph.OutputPortId;
import com.huanghuang.rsintegration.crafting.graph.RootAllocation;
import com.huanghuang.rsintegration.crafting.graph.RootDemand;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.List;

final class TestCraftGraphs {
    static CraftPlanGraph empty() {
        return new CraftPlanGraph(1, List.of(), List.of(), List.of(), List.of(), List.of());
    }

    static CraftPlanGraph terminal(CraftingResolver.ResolutionStep step) {
        NodeId id = new NodeId(0);
        List<OutputDeclaration> outputs = List.of();
        List<RootDemand> roots = List.of();
        if (step.productionTarget() != null) {
            var target = step.productionTarget();
            var material = MaterialKey.of(target.displayStack());
            OutputPortId port = new OutputPortId(id, 0);
            outputs = List.of(OutputDeclaration.item(port, material, target.quantity(), OutputKind.PRIMARY));
            roots = List.of(new RootDemand(Ingredient.of(target.displayStack()), target.quantity(), 0,
                    target.displayStack(), List.of(new RootAllocation(new MaterialSource.ProducerOutput(port),
                    material, target.quantity()))));
        }
        var node = new CraftNode(id, step.recipeId(), step.modType().id(), step.recipeTypeId(),
                step.executions(), step.alternativeIds(), step.alternativeModTypes(), step.inferMode(),
                step.syntheticInput(), step.syntheticOutput(), List.of(), outputs);
        return new CraftPlanGraph(1, List.of(node), List.of(), roots, List.of(), List.of(id));
    }
}
