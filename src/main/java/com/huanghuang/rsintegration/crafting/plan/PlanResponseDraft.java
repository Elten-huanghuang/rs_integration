package com.huanghuang.rsintegration.crafting.plan;

import com.huanghuang.rsintegration.crafting.tree.IngredientKey;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable boundary between live server-state collection and the wire response.
 * Mutable Minecraft values are detached here so cached plans cannot be altered by
 * later recipe, GUI, or caller mutations.
 */
public record PlanResponseDraft(
        boolean success,
        String targetName,
        ItemStack targetResult,
        List<PlanStep> steps,
        Map<IngredientKey, PlanResponse.Availability> materials,
        List<String> missing,
        String recipeId,
        @Nullable String executionModTypeId,
        @Nullable String executionDim,
        int executionPosX,
        int executionPosY,
        int executionPosZ,
        List<Component> modWarnings,
        int repeatCount,
        @Nullable int[] embersCode,
        @Nullable Component[] embersAspectNames,
        @Nullable Component[] embersInputNames,
        long embersSeed,
        boolean embersCanInfer,
        boolean embersCodeFromCache,
        boolean executionMachineSupportsGui,
        @Nullable ItemStack baseItem,
        Set<String> boundMachineTypes,
        Map<IngredientKey, Integer> leftovers,
        @Nullable ItemStack clickedOutput,
        @Nullable PlanGraphView graph
) {
    public PlanResponseDraft {
        targetName = targetName == null ? "" : targetName;
        targetResult = copy(targetResult);
        steps = steps.stream().map(PlanResponseDraft::copyStep).toList();
        materials = Collections.unmodifiableMap(new LinkedHashMap<>(materials));
        missing = List.copyOf(missing);
        recipeId = recipeId == null ? "" : recipeId;
        modWarnings = List.copyOf(modWarnings);
        repeatCount = Math.max(1, repeatCount);
        embersCode = embersCode == null ? null : embersCode.clone();
        embersAspectNames = embersAspectNames == null ? null : embersAspectNames.clone();
        embersInputNames = embersInputNames == null ? null : embersInputNames.clone();
        baseItem = copyNullable(baseItem);
        boundMachineTypes = Collections.unmodifiableSet(new LinkedHashSet<>(boundMachineTypes));
        leftovers = Collections.unmodifiableMap(new LinkedHashMap<>(leftovers));
        clickedOutput = copyNullable(clickedOutput);
        graph = graph == null ? null : copyGraph(graph);
    }

    @Override
    public ItemStack targetResult() {
        return targetResult.copy();
    }

    @Override
    public List<PlanStep> steps() {
        return steps.stream().map(PlanResponseDraft::copyStep).toList();
    }

    @Override
    @Nullable
    public int[] embersCode() {
        return embersCode == null ? null : embersCode.clone();
    }

    @Override
    @Nullable
    public Component[] embersAspectNames() {
        return embersAspectNames == null ? null : embersAspectNames.clone();
    }

    @Override
    @Nullable
    public Component[] embersInputNames() {
        return embersInputNames == null ? null : embersInputNames.clone();
    }

    @Override
    @Nullable
    public ItemStack baseItem() {
        return copyNullable(baseItem);
    }

    @Override
    @Nullable
    public ItemStack clickedOutput() {
        return copyNullable(clickedOutput);
    }

    @Override
    @Nullable
    public PlanGraphView graph() {
        return graph == null ? null : copyGraph(graph);
    }

    public PlanResponse toResponse() {
        return new PlanResponse(success, targetName, targetResult.copy(),
                steps.stream().map(PlanResponseDraft::copyStep).toList(),
                materials, missing, recipeId, executionModTypeId, executionDim,
                executionPosX, executionPosY, executionPosZ, modWarnings, repeatCount,
                embersCode == null ? null : embersCode.clone(),
                embersAspectNames == null ? null : embersAspectNames.clone(),
                embersInputNames == null ? null : embersInputNames.clone(),
                embersSeed, embersCanInfer, embersCodeFromCache,
                executionMachineSupportsGui, copyNullable(baseItem), boundMachineTypes,
                leftovers, copyNullable(clickedOutput), graph == null ? null : copyGraph(graph));
    }

    private static PlanStep copyStep(PlanStep step) {
        return new PlanStep(step.recipeId(), step.output().copy(), step.batches(),
                step.inputs().stream().map(ItemStack::copy).toList(),
                step.alternatives(), step.modType(), step.depth(), step.hasOrSiblings(),
                step.recipeWidth(), step.recipeHeight(), step.alternativeModTypes());
    }

    private static PlanGraphView copyGraph(PlanGraphView source) {
        List<PlanGraphView.NodeView> nodes = source.nodes().stream()
                .map(node -> new PlanGraphView.NodeView(node.nodeId(), node.recipeId(),
                        node.modTypeId(), node.executions(), node.primaryOutput().copy(),
                        node.alternativeIds(), node.alternativeModTypeIds(),
                        node.inputs().stream().map(input -> new PlanGraphView.InputView(
                                input.portIndex(), input.display().copy(), input.quantity(),
                                input.roleOrdinal())).toList(),
                        node.outputs().stream().map(output -> new PlanGraphView.OutputView(
                                output.portIndex(), output.display().copy(), output.quantity(),
                                output.kindOrdinal())).toList()))
                .toList();
        List<PlanGraphView.EdgeView> edges = source.edges().stream()
                .map(edge -> new PlanGraphView.EdgeView(edge.consumerNodeId(),
                        edge.consumerPortIndex(), edge.source(), edge.material().copy(),
                        edge.quantity())).toList();
        List<PlanGraphView.RootView> roots = source.roots().stream()
                .map(root -> new PlanGraphView.RootView(root.display().copy(), root.quantity(),
                        root.unresolvedQuantity(), root.allocations().stream()
                        .map(allocation -> new PlanGraphView.RootEdgeView(allocation.source(),
                                allocation.material().copy(), allocation.quantity())).toList()))
                .toList();
        List<PlanGraphView.UnresolvedView> unresolved = source.unresolved().stream()
                .map(item -> new PlanGraphView.UnresolvedView(item.consumerNodeId(),
                        item.consumerPortIndex(), item.display().copy(), item.quantity())).toList();
        return new PlanGraphView(source.version(), nodes, edges, roots, unresolved,
                source.topologicalOrder());
    }

    private static ItemStack copy(ItemStack stack) {
        return stack == null ? ItemStack.EMPTY : stack.copy();
    }

    @Nullable
    private static ItemStack copyNullable(@Nullable ItemStack stack) {
        return stack == null ? null : stack.copy();
    }
}
