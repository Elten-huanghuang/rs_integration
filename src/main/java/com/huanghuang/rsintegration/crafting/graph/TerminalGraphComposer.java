package com.huanghuang.rsintegration.crafting.graph;

import com.huanghuang.rsintegration.crafting.CraftingResolver;
import com.huanghuang.rsintegration.crafting.quantity.SaturatingLongMath;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nullable;

/** Converts a resolved terminal-input graph into a self-contained executable graph. */
public final class TerminalGraphComposer {

    private TerminalGraphComposer() {}

    public record TerminalOutput(
            MaterialKey material,
            int perOperation,
            int minimumPerOperation,
            int maximumPerOperation,
            OutputKind kind,
            OutputProbability probability,
            boolean dynamicIdentity,
            @Nullable Integer sourceInputIndex
    ) {
        public TerminalOutput {
            if (material == null || maximumPerOperation <= 0) {
                throw new IllegalArgumentException("terminal output must be non-empty");
            }
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(probability, "probability");
            if (perOperation < 0
                    || minimumPerOperation < 0
                    || maximumPerOperation < minimumPerOperation
                    || perOperation < minimumPerOperation
                    || perOperation > maximumPerOperation) {
                throw new IllegalArgumentException(
                        "invalid terminal output range");
            }
            if (sourceInputIndex != null && sourceInputIndex < 0) {
                throw new IllegalArgumentException(
                        "invalid source input index");
            }
        }

        public TerminalOutput(
                MaterialKey material,
                int perOperation,
                OutputKind kind,
                OutputProbability probability,
                boolean dynamicIdentity
        ) {
            this(
                    material,
                    perOperation,
                    probability.isGuaranteed() ? perOperation : 0,
                    perOperation,
                    kind,
                    probability,
                    dynamicIdentity,
                    null
            );
        }

        public TerminalOutput(MaterialKey material, int perOperation, OutputKind kind) {
            this(material, perOperation, kind, OutputProbability.guaranteed(), false);
        }
    }

    public static CraftPlanGraph compose(CraftPlanGraph base,
                                         CraftingResolver.ResolutionStep terminalStep,
                                         ItemStack terminalOutput) {
        if (terminalOutput == null || terminalOutput.isEmpty()) {
            throw new IllegalArgumentException("terminal output must be known");
        }
        return compose(base, terminalStep, List.of(new TerminalOutput(
                MaterialKey.of(terminalOutput), terminalOutput.getCount(), OutputKind.PRIMARY)));
    }

    public static CraftPlanGraph compose(CraftPlanGraph base,
                                         CraftingResolver.ResolutionStep terminalStep,
                                         List<TerminalOutput> terminalOutputs) {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(terminalStep, "terminalStep");
        Objects.requireNonNull(terminalOutputs, "terminalOutputs");
        CraftPlanValidator.validate(base);
        if (terminalOutputs.isEmpty()
                || terminalOutputs.stream().noneMatch(output -> output.kind() == OutputKind.PRIMARY)) {
            throw new IllegalArgumentException("terminal output must include a primary output");
        }
        if (!base.unresolvedDemands().isEmpty()
                || base.rootDemands().stream().anyMatch(root -> root.unresolvedQuantity() > 0)) {
            throw new IllegalArgumentException("terminal input graph is unresolved");
        }
        boolean hasSyntheticInput = terminalStep.syntheticInput() != null
                && !terminalStep.syntheticInput().isEmpty();
        if (base.rootDemands().isEmpty() && !hasSyntheticInput && !terminalStep.inferMode()) {
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
                    root.role(), root.displayHint()));
            for (RootAllocation rootAllocation : root.allocations()) {
                allocations.add(new MaterialAllocation(new AllocationId(nextAllocationValue++),
                        inputId, rootAllocation.source(), rootAllocation.material(),
                        rootAllocation.quantity()));
            }
        }

        List<OutputDeclaration> outputs = new ArrayList<>(terminalOutputs.size());
        OutputDeclaration primary = null;
        for (int index = 0; index < terminalOutputs.size(); index++) {
            TerminalOutput output = terminalOutputs.get(index);
            long expectedTotal = Math.multiplyExact(
                    (long) output.perOperation(), terminalStep.executions());
            long minimumTotal = Math.multiplyExact(
                    (long) output.minimumPerOperation(),
                    terminalStep.executions()
            );
            long maximumTotal = Math.multiplyExact(
                    (long) output.maximumPerOperation(),
                    terminalStep.executions()
            );
            InputPortId sourceInput = null;
            if (output.sourceInputIndex() != null) {
                if (output.sourceInputIndex() >= inputs.size()) {
                    throw new IllegalArgumentException(
                            "terminal output source input is out of range");
                }
                sourceInput = inputs.get(output.sourceInputIndex()).id();
            }
            OutputDeclaration declaration = new OutputDeclaration(
                    new OutputPortId(terminalId, index),
                    output.material(),
                    expectedTotal,
                    output.kind(),
                    minimumTotal,
                    maximumTotal,
                    output.probability(),
                    output.dynamicIdentity(),
                    sourceInput
            );
            outputs.add(declaration);
            if (primary == null && output.kind() == OutputKind.PRIMARY) primary = declaration;
        }
        if (primary == null) {
            throw new IllegalArgumentException("terminal output must include a primary output");
        }
        CraftNode terminal = new CraftNode(terminalId, terminalStep.recipeId(),
                terminalStep.modType().id(), terminalStep.recipeTypeId(), terminalStep.executions(),
                terminalStep.alternativeIds(), terminalStep.alternativeModTypes(),
                terminalStep.inferMode(), terminalStep.syntheticInput(), terminalStep.syntheticOutput(),
                inputs, outputs, terminalStep.magicCosts());

        List<CraftNode> nodes = new ArrayList<>(base.nodes());
        nodes.add(terminal);
        List<NodeId> order = new ArrayList<>(base.topologicalOrder());
        order.add(terminalId);
        ItemStack finalOutput = primary.material().toStack(
                SaturatingLongMath.clampToInt(primary.quantity()));
        MaterialSource terminalSource = new MaterialSource.ProducerOutput(primary.id());
        RootDemand finalDemand = new RootDemand(Ingredient.of(finalOutput), primary.quantity(), 0,
                finalOutput, List.of(new RootAllocation(terminalSource,
                primary.material(), primary.quantity())), DemandRole.CONSUMED);

        CraftPlanGraph result = new CraftPlanGraph(base.version(), nodes, allocations,
                List.of(finalDemand), List.of(), order, base.planningRevision());
        CraftPlanValidator.validate(result);
        return result;
    }
}
