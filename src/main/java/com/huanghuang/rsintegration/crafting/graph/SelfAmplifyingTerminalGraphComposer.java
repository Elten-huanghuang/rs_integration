package com.huanghuang.rsintegration.crafting.graph;

import com.huanghuang.rsintegration.api.IntegrationResourceKey;
import com.huanghuang.rsintegration.crafting.CraftingResolver.ResolutionStep;
import com.huanghuang.rsintegration.crafting.ProductionTarget;
import com.huanghuang.rsintegration.crafting.SelfAmplifyingRecipePolicy;
import com.huanghuang.rsintegration.crafting.graph.TerminalGraphComposer.TerminalOutput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** 将只预备一份种子的复制终端拆成有真实产物回流边的阶段。 */
public final class SelfAmplifyingTerminalGraphComposer {
    private SelfAmplifyingTerminalGraphComposer() {}

    public record Composition(CraftPlanGraph graph, List<ResolutionStep> stages, List<TerminalOutput> outputs) {
        public Composition {
            stages = List.copyOf(stages);
            outputs = List.copyOf(outputs);
        }
    }

    @Nullable
    public static Composition tryCompose(CraftPlanGraph base, ResolutionStep terminal,
                                         List<TerminalOutput> outputs, @Nullable Integer nbtSourceInput) {
        if (terminal.executions() <= 1) return null;
        TerminalOutput primary = outputs.stream()
                .filter(output -> output.kind() == OutputKind.PRIMARY).findFirst().orElse(null);
        if (primary == null) return null;
        if (primary.dynamicIdentity() && nbtSourceInput != null) {
            TerminalOutput resolved = resolveInheritedSeedNbt(base, primary, nbtSourceInput);
            if (resolved != null) {
                List<TerminalOutput> refined = new ArrayList<>(outputs);
                refined.set(outputs.indexOf(primary), resolved);
                outputs = List.copyOf(refined);
                primary = resolved;
            }
        }
        ItemStack result = primary.material().toStack(1);
        List<Boolean> selfInputs = new ArrayList<>();
        long seedPerOperation = 0L;
        for (RootDemand root : base.rootDemands()) {
            ItemStack selfProbe = result;
            if (primary.dynamicIdentity() && !root.allocations().isEmpty()
                    && root.allocations().get(0).material().item() == result.getItem()) {
                // 尚不能确定 NBT 时也要识别潜在回流，避免退回一个无法整单备料的节点。
                selfProbe = root.allocations().get(0).material().toStack(1);
            }
            boolean self = root.role() != DemandRole.CATALYST
                    && SelfAmplifyingRecipePolicy.isSelfInput(root.ingredient(), selfProbe);
            selfInputs.add(self);
            if (self) seedPerOperation = Math.addExact(seedPerOperation, root.quantity());
        }
        if (seedPerOperation == 0L || seedPerOperation >= primary.perOperation()) return null;
        // 已知输入的直接 NBT 继承可以确定身份，其他动态输出仍不能承诺为种子。
        if (!primary.probability().isGuaranteed() || primary.dynamicIdentity()
                || primary.minimumPerOperation() != primary.perOperation()
                || primary.maximumPerOperation() != primary.perOperation()) {
            throw new IllegalArgumentException("copy terminal requires deterministic seed output");
        }
        if (terminal.productionTarget() != null && terminal.productionTarget().quantity()
                != Math.multiplyExact((long) primary.perOperation(), terminal.executions())) {
            throw new IllegalArgumentException("copy terminal requires a fixed operation count");
        }
        CraftPlanValidator.validate(base);
        Deque<RootAllocation> seeds = new ArrayDeque<>();
        List<Deque<RootAllocation>> supplies = new ArrayList<>();
        for (int i = 0; i < base.rootDemands().size(); i++) {
            RootDemand root = base.rootDemands().get(i);
            supplies.add(new ArrayDeque<>(root.allocations()));
            if (selfInputs.get(i)) {
                for (RootAllocation allocation : root.allocations()) {
                    if (!allocation.material().equals(primary.material())) {
                        throw new IllegalArgumentException("copy seed identity differs from output");
                    }
                }
                seeds.addAll(root.allocations());
            } else if (root.role() != DemandRole.CATALYST
                    && root.quantity() % terminal.executions() != 0L) {
                throw new IllegalArgumentException("copy input cannot be split across operations");
            }
        }

        CraftPlanGraph graph = base;
        List<ResolutionStep> stages = new ArrayList<>();
        int remaining = terminal.executions();
        long availableSeeds = seedPerOperation;
        while (remaining > 0) {
            int operations = (int) Math.min(remaining, availableSeeds / seedPerOperation);
            List<RootDemand> stageInputs = new ArrayList<>();
            for (int i = 0; i < base.rootDemands().size(); i++) {
                RootDemand root = base.rootDemands().get(i);
                long quantity = selfInputs.get(i) ? Math.multiplyExact(root.quantity(), operations)
                        : root.role() == DemandRole.CATALYST ? root.quantity()
                        : Math.multiplyExact(root.quantity() / terminal.executions(), operations);
                List<RootAllocation> supply = take(selfInputs.get(i) ? seeds : supplies.get(i), quantity);
                stageInputs.add(new RootDemand(root.ingredient(), quantity, 0L,
                        root.displayHint(), supply, root.role()));
            }
            ResolutionStep stage = stage(terminal, primary, operations);
            CraftPlanGraph stageBase = new CraftPlanGraph(base.version(), graph.nodes(), graph.allocations(),
                    stageInputs, List.of(), graph.topologicalOrder(), base.planningRevision());
            graph = TerminalGraphComposer.compose(stageBase, stage, outputs);
            NodeId id = graph.topologicalOrder().get(graph.topologicalOrder().size() - 1);
            CraftNode node = graph.nodesById().get(id);
            List<OutputDeclaration> stageOutputs = new ArrayList<>(node.outputs());
            for (int i = 0; i < stageInputs.size(); i++) {
                RootDemand input = stageInputs.get(i);
                if (input.role() != DemandRole.CATALYST) continue;
                InputPortId port = new InputPortId(id, i);
                // 催化剂同样沿返回边交给下一阶段，不能重复承诺最初的同一份库存。
                stageOutputs.removeIf(output -> output.kind() == OutputKind.CATALYST_RETURN
                        && port.equals(output.sourceInput()));
                for (RootAllocation borrowed : input.allocations()) {
                    int nextIndex = stageOutputs.stream().mapToInt(output -> output.id().index())
                            .max().orElse(-1) + 1;
                    OutputPortId returned = new OutputPortId(id, nextIndex);
                    stageOutputs.add(OutputDeclaration.item(returned, borrowed.material(), borrowed.quantity(),
                            OutputKind.CATALYST_RETURN, borrowed.quantity(), borrowed.quantity(),
                            OutputProbability.guaranteed(), false, port));
                    supplies.get(i).addLast(new RootAllocation(new MaterialSource.ProducerOutput(returned),
                            borrowed.material(), borrowed.quantity()));
                }
            }
            List<CraftNode> nodes = new ArrayList<>(graph.nodes());
            nodes.set(nodes.size() - 1, new CraftNode(node.id(), node.recipeId(), node.modTypeId(),
                    node.recipeTypeId(), node.executions(), node.alternativeIds(), node.alternativeModTypeIds(),
                    node.inferMode(), node.syntheticInput(), node.syntheticOutput(), node.inputs(),
                    stageOutputs, node.magicCosts()));
            OutputDeclaration produced = stageOutputs.stream()
                    .filter(output -> output.kind() == OutputKind.PRIMARY).findFirst().orElseThrow();
            seeds.addLast(new RootAllocation(new MaterialSource.ProducerOutput(produced.id()),
                    produced.material(), produced.quantity()));
            availableSeeds = Math.addExact(availableSeeds,
                    Math.multiplyExact(primary.perOperation() - seedPerOperation, operations));
            // 交付的数量是剩余种子加净增量；中途已再次消耗的毛产出不能重复交付。
            RootDemand finalOutput = new RootDemand(Ingredient.of(result), availableSeeds, 0L, result,
                    List.copyOf(seeds));
            graph = new CraftPlanGraph(base.version(), nodes, graph.allocations(), List.of(finalOutput),
                    List.of(), graph.topologicalOrder(), base.planningRevision());
            stages.add(stage);
            remaining -= operations;
        }
        CraftPlanValidator.validate(graph);
        return new Composition(graph, stages, outputs);
    }

    @Nullable
    private static TerminalOutput resolveInheritedSeedNbt(CraftPlanGraph base, TerminalOutput primary,
                                                          int sourceInput) {
        if (sourceInput < 0 || sourceInput >= base.rootDemands().size()) return null;
        RootDemand source = base.rootDemands().get(sourceInput);
        if (source.allocations().isEmpty()) return null;
        MaterialKey seed = source.allocations().get(0).material();
        if (seed.item() != primary.material().item()) return null;
        ItemStack output = primary.material().toStack(1);
        if (seed.tag() != null) output.setTag(seed.toStack(1).getTag().copy());
        if (!SelfAmplifyingRecipePolicy.isSelfInput(source.ingredient(), output)) return null;
        // 只有来源的精确身份已经确定时才能冻结 NBT，不能把未知的上游产物当成现货。
        for (RootAllocation allocation : source.allocations()) {
            if (!seed.equals(allocation.material())) return null;
            if (allocation.source() instanceof MaterialSource.ProducerOutput producer) {
                CraftNode node = base.nodesById().get(producer.outputPort().nodeId());
                if (node == null || node.outputs().stream().noneMatch(declared ->
                        declared.id().equals(producer.outputPort()) && !declared.dynamicIdentity())) return null;
            }
        }
        MaterialKey material = MaterialKey.of(output);
        return new TerminalOutput(material, primary.perOperation(), primary.minimumPerOperation(),
                primary.maximumPerOperation(), primary.kind(), primary.probability(), false,
                primary.sourceInputIndex(), IntegrationResourceKey.item(output));
    }

    private static ResolutionStep stage(ResolutionStep terminal, TerminalOutput primary, int operations) {
        ProductionTarget target = terminal.productionTarget() == null ? null : ProductionTarget.of(
                primary.resource(), Math.multiplyExact((long) primary.perOperation(), operations));
        return new ResolutionStep(terminal.recipeId(), terminal.modType(), terminal.recipeTypeId(),
                terminal.alternativeIds(), terminal.alternativeModTypes(), terminal.inferMode(), operations,
                terminal.syntheticInput(), terminal.syntheticOutput(), target, terminal.magicCosts());
    }

    private static List<RootAllocation> take(Deque<RootAllocation> supply, long quantity) {
        List<RootAllocation> result = new ArrayList<>();
        while (quantity > 0L) {
            RootAllocation lot = supply.pollFirst();
            if (lot == null) throw new IllegalArgumentException("copy stage has insufficient exact material");
            long taken = Math.min(quantity, lot.quantity());
            result.add(new RootAllocation(lot.source(), lot.material(), taken));
            if (taken < lot.quantity()) supply.addFirst(new RootAllocation(
                    lot.source(), lot.material(), lot.quantity() - taken));
            quantity -= taken;
        }
        return List.copyOf(result);
    }
}
