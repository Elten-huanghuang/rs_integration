package com.huanghuang.rsintegration.crafting.graph;

import com.huanghuang.rsintegration.crafting.CraftingResolver;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Converts a resolved terminal-input graph into a self-contained executable graph. */
public final class TerminalGraphComposer {

    private TerminalGraphComposer() {}

    public static CraftPlanGraph compose(CraftPlanGraph base,
                                         CraftingResolver.ResolutionStep terminalStep,
                                         ItemStack terminalOutput) {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(terminalStep, "terminalStep");
        Objects.requireNonNull(terminalOutput, "terminalOutput");
        CraftPlanValidator.validate(base);
        if (terminalOutput.isEmpty()) {
            throw new IllegalArgumentException("terminal output must be known");
        }
        if (!base.unresolvedDemands().isEmpty()
                || base.rootDemands().stream().anyMatch(root -> root.unresolvedQuantity() > 0)) {
            throw new IllegalArgumentException("terminal input graph is unresolved");
        }
        if (base.rootDemands().isEmpty()) {
            throw new IllegalArgumentException("terminal input graph has no static inputs");
        }

        int nextNodeValue = base.nodes().stream()
                .mapToInt(node -> node.id().value()).max().orElse(-1) + 1;
        long nextAllocationValue = base.allocations().stream()
                .mapToLong(allocation -> allocation.id().value()).max().orElse(-1L) + 1L;
        NodeId terminalId = new NodeId(nextNodeValue);

        List<InputDemand> inputs = new ArrayList<>(base.rootDemands().size());
        List<MaterialAllocation> allocations = new ArrayList<>(base.allocations());
        for (int index = 0; index < base.rootDemands().size(); index++) {
            RootDemand root = base.rootDemands().get(index);
            InputPortId inputId = new InputPortId(terminalId, index);
            inputs.add(new InputDemand(inputId, root.ingredient(), root.quantity(),
                    DemandRole.CONSUMED, root.displayHint()));
            for (RootAllocation rootAllocation : root.allocations()) {
                allocations.add(new MaterialAllocation(new AllocationId(nextAllocationValue++),
                        inputId, rootAllocation.source(), rootAllocation.material(),
                        rootAllocation.quantity()));
            }
        }

        ItemStack totalOutput = terminalOutput.copyWithCount(Math.multiplyExact(
                terminalOutput.getCount(), terminalStep.executions()));
        OutputPortId outputId = new OutputPortId(terminalId, 0);
        OutputDeclaration output = new OutputDeclaration(outputId, MaterialKey.of(totalOutput),
                totalOutput.getCount(), OutputKind.PRIMARY);
        CraftNode terminal = new CraftNode(terminalId, terminalStep.recipeId(),
                terminalStep.modType().id(), terminalStep.recipeTypeId(), terminalStep.executions(),
                terminalStep.alternativeIds(), terminalStep.alternativeModTypes(),
                terminalStep.inferMode(), terminalStep.syntheticInput(), terminalStep.syntheticOutput(),
                inputs, List.of(output));

        List<CraftNode> nodes = new ArrayList<>(base.nodes());
        nodes.add(terminal);
        List<NodeId> order = new ArrayList<>(base.topologicalOrder());
        order.add(terminalId);
        MaterialSource terminalSource = new MaterialSource.ProducerOutput(outputId);
        RootDemand finalDemand = new RootDemand(Ingredient.of(totalOutput), totalOutput.getCount(), 0,
                totalOutput, List.of(new RootAllocation(terminalSource,
                MaterialKey.of(totalOutput), totalOutput.getCount())));

        CraftPlanGraph result = new CraftPlanGraph(base.version(), nodes, allocations,
                List.of(finalDemand), List.of(), order, base.planningRevision());
        CraftPlanValidator.validate(result);
        return result;
    }
}
