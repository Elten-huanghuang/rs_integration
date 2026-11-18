package com.huanghuang.rsintegration.crafting.plan;

import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.tree.IngredientKey;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import com.huanghuang.rsintegration.storage.StorageNetworkDescriptor;
import com.huanghuang.rsintegration.storage.StorageReference;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.AbstractList;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Full crafting plan sent from server to client.
 */
public record PlanResponse(
        boolean success,
        String targetName,
        ItemStack targetResult,
        List<PlanStep> steps,
        Map<IngredientKey, Availability> materials,
        List<String> missing,
        String recipeId,  // for confirm execution
        @Nullable String executionModTypeId,  // non-null → route to mod-specific handler on confirm
        @Nullable String executionDim,        // machine dimension for mod recipes (ResourceLocation string)
        int executionPosX,
        int executionPosY,
        int executionPosZ,
        // Mod-specific validation warnings (Goety research/structure, FA essences).
        // MUST stay unresolved Components: a dedicated server never loads
        // assets/rs_integration/lang/*.json, so rendering these to String
        // server-side yields raw translation keys on the client.
        List<Component> modWarnings,
        int repeatCount,
        // ── Embers Alchemy pedestal layout (null/missing when not applicable) ──
        @Nullable int[] embersCode,           // code[i] = aspect index for pedestal i
        @Nullable Component[] embersAspectNames, // aspect item names (per code index), resolved client-side
        @Nullable Component[] embersInputNames,  // input item names (per pedestal), resolved client-side
        long embersSeed,                      // world seed used for calculation (0 = not set)
        boolean embersCanInfer,               // true when a tablet is bound and Mode 1 is available
        boolean embersCodeFromCache,          // true when embersCode was loaded from KnownCodeSavedData (previously inferred)
        boolean executionMachineSupportsGui,  // true when the bound execution machine supports remote GUI
        @Nullable ItemStack baseItem,         // JEI-provided base item for FA ApplyModifierRecipe prefill
        Set<String> boundMachineTypes,        // v3.4 availability passport: modType ids of machines the player has bound
        Map<IngredientKey, Long> leftovers, // overproduction keyed by exact item+NBT
        @Nullable ItemStack clickedOutput,    // JEI ghost-output the player clicked (NBT-variant target, e.g. WR leveled book)
        @Nullable PlanGraphView graph,         // server-authored DAG view; null on legacy/fallback plans
        boolean executionBlocked,              // hard prerequisite failure, independent of material availability
        List<MachineCandidateView> machineCandidates,
        Map<ResourceLocation, StepIssue> stepIssues,
        @Nullable StorageReference storageReference,
        List<StorageNetworkDescriptor> storageNetworks,
        PlanOutcome outcome,
        List<IntegrationDiagnostic> diagnostics
) {
    public PlanResponse {
        if (steps instanceof GraphCards cards) {
            if (cards.graph != graph) throw new IllegalArgumentException("卡片投影与预览图不符");
        } else {
            steps = graph == null || graph.nodes().isEmpty() ? List.copyOf(steps) : new GraphCards(graph, steps);
        }
        machineCandidates = machineCandidates == null ? List.of() : List.copyOf(machineCandidates);
        stepIssues = stepIssues == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(stepIssues));
        storageNetworks = storageNetworks == null ? List.of() : List.copyOf(storageNetworks);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        if (outcome == null) {
            outcome = PlanOutcome.infer(success, materials, missing, executionBlocked);
        }
    }

    /** 预算耗尽时保留目标、重算参数、已完成的展示图和已结算材料。 */
    public static PlanResponse incomplete(ResourceLocation recipe, ItemStack output, int repeat,
                                          @Nullable ResourceLocation dimension, @Nullable BlockPos position,
                                          @Nullable ItemStack base, @Nullable ItemStack clicked,
                                          @Nullable StorageReference storage, @Nullable PlanGraphView graph,
                                          Map<StackKey, Long> snapshotStock,
                                          IntegrationDiagnostic diagnostic) {
        return new PlanResponse(false, "", output.copy(),
                List.of(new PlanStep(recipe, output.copy(), repeat, List.of())),
                PlanMaterialBill.completedAllocations(graph, snapshotStock), List.of(), recipe.toString(),
                null, dimension == null ? null : dimension.toString(),
                position == null ? 0 : position.getX(), position == null ? 0 : position.getY(),
                position == null ? 0 : position.getZ(), List.of(), repeat,
                null, null, null, 0L, false, false, false, base, Set.of(), Map.of(), clicked,
                graph, true, List.of(), Map.of(), storage, List.of(), PlanOutcome.BUDGET_EXHAUSTED,
                List.of(diagnostic));
    }

    /** 图内卡片按拓扑顺序投影；图外目标、额外阶段或无图轨迹才独立传输。 */
    public List<PlanStep> standaloneSteps() {
        return steps instanceof GraphCards cards ? cards.extra : steps;
    }

    private static final class GraphCards extends AbstractList<PlanStep> {
        private final PlanGraphView graph;
        private final List<PlanGraphView.NodeView> ordered;
        private final List<PlanStep> extra;

        private GraphCards(PlanGraphView graph, List<PlanStep> extra) {
            this.graph = graph;
            Map<Integer, PlanGraphView.NodeView> byId = new HashMap<>();
            for (var node : graph.nodes()) byId.put(node.nodeId(), node);
            ordered = graph.topologicalOrder().stream().map(id -> {
                var node = byId.remove(id);
                if (node == null) throw new IllegalArgumentException("拓扑顺序包含重复或不存在的节点：" + id);
                return node;
            }).toList();
            if (!byId.isEmpty() || ordered.size() != graph.nodes().size())
                throw new IllegalArgumentException("拓扑顺序与预览节点不符");
            this.extra = List.copyOf(extra);
        }

        @Override public int size() { return ordered.size() + extra.size(); }
        @Override public PlanStep get(int i) {
            return i < ordered.size() ? ordered.get(i).asPlanStep() : extra.get(i - ordered.size());
        }
    }

    /** 兼容旧调用：根据现有布尔字段和缺料信息推断终态。 */
    public PlanResponse(boolean success, String targetName, ItemStack targetResult,
                        List<PlanStep> steps, Map<IngredientKey, Availability> materials,
                        List<String> missing, String recipeId,
                        @Nullable String executionModTypeId, @Nullable String executionDim,
                        int executionPosX, int executionPosY, int executionPosZ,
                        List<Component> modWarnings, int repeatCount,
                        @Nullable int[] embersCode, @Nullable Component[] embersAspectNames,
                        @Nullable Component[] embersInputNames, long embersSeed,
                        boolean embersCanInfer, boolean embersCodeFromCache,
                        boolean executionMachineSupportsGui, @Nullable ItemStack baseItem,
                        Set<String> boundMachineTypes, Map<IngredientKey, Long> leftovers,
                        @Nullable ItemStack clickedOutput, @Nullable PlanGraphView graph,
                        boolean executionBlocked, List<MachineCandidateView> machineCandidates,
                        Map<ResourceLocation, StepIssue> stepIssues,
                        @Nullable StorageReference storageReference,
                        List<StorageNetworkDescriptor> storageNetworks) {
        this(success, targetName, targetResult, steps, materials, missing, recipeId,
                executionModTypeId, executionDim, executionPosX, executionPosY, executionPosZ,
                modWarnings, repeatCount, embersCode, embersAspectNames, embersInputNames,
                embersSeed, embersCanInfer, embersCodeFromCache, executionMachineSupportsGui,
                baseItem, boundMachineTypes, leftovers, clickedOutput, graph, executionBlocked,
                machineCandidates, stepIssues, storageReference, storageNetworks, null, List.of());
    }

    /** 错误响应和无目标计划的便捷构造。 */
    public PlanResponse(boolean success, String targetName, ItemStack targetResult,
                        List<PlanStep> steps, Map<IngredientKey, Availability> materials,
                        List<String> missing, String recipeId, List<Component> modWarnings,
                        int repeatCount, PlanOutcome outcome,
                        List<IntegrationDiagnostic> diagnostics) {
        this(success, targetName, targetResult, steps, materials, missing, recipeId,
                null, null, 0, 0, 0, modWarnings, repeatCount,
                null, null, null, 0L, false, false, false, null,
                Collections.emptySet(), Collections.emptyMap(), null, null,
                outcome == PlanOutcome.RUNTIME_UNAVAILABLE, List.of(), Map.of(), null,
                List.of(), outcome, diagnostics);
    }

    public record StepIssue(List<Component> warnings, boolean blocked) {
        public StepIssue {
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    /** 仅替换展示材料视图，保持路线、机器条件和原始求解结论不变。 */
    public PlanResponse withMaterialAvailability(Map<IngredientKey, Availability> updated) {
        return new PlanResponse(success, targetName, targetResult, steps, updated, missing,
                recipeId, executionModTypeId, executionDim, executionPosX, executionPosY,
                executionPosZ, modWarnings, repeatCount, embersCode, embersAspectNames,
                embersInputNames, embersSeed, embersCanInfer, embersCodeFromCache,
                executionMachineSupportsGui, baseItem, boundMachineTypes, leftovers, clickedOutput,
                graph, executionBlocked, machineCandidates, stepIssues, storageReference,
                storageNetworks, outcome, diagnostics);
    }

    /** 保留本次完整计算结果，明确告知实时库存仍在变化。 */
    public PlanResponse withInventoryChangeNotice() {
        List<Component> warnings = new ArrayList<>(modWarnings);
        warnings.add(Component.translatable("rsi.plan.warning.inventory_changing"));
        return new PlanResponse(success, targetName, targetResult, steps, materials, missing,
                recipeId, executionModTypeId, executionDim, executionPosX, executionPosY,
                executionPosZ, List.copyOf(warnings), repeatCount, embersCode, embersAspectNames,
                embersInputNames, embersSeed, embersCanInfer, embersCodeFromCache,
                executionMachineSupportsGui, baseItem, boundMachineTypes, leftovers, clickedOutput,
                graph, executionBlocked, machineCandidates, stepIssues, storageReference,
                storageNetworks, outcome, diagnostics);
    }

    /** Backward-compat: plans without per-step prerequisite diagnostics. */
    public PlanResponse(boolean success, String targetName, ItemStack targetResult,
                        List<PlanStep> steps, Map<IngredientKey, Availability> materials,
                        List<String> missing, String recipeId,
                        @Nullable String executionModTypeId, @Nullable String executionDim,
                        int executionPosX, int executionPosY, int executionPosZ,
                        List<Component> modWarnings, int repeatCount,
                        @Nullable int[] embersCode, @Nullable Component[] embersAspectNames,
                        @Nullable Component[] embersInputNames, long embersSeed,
                        boolean embersCanInfer, boolean embersCodeFromCache,
                        boolean executionMachineSupportsGui, @Nullable ItemStack baseItem,
                        Set<String> boundMachineTypes, Map<IngredientKey, Long> leftovers,
                        @Nullable ItemStack clickedOutput, @Nullable PlanGraphView graph,
                        boolean executionBlocked, List<MachineCandidateView> machineCandidates) {
        this(success, targetName, targetResult, steps, materials, missing, recipeId,
                executionModTypeId, executionDim, executionPosX, executionPosY, executionPosZ,
                modWarnings, repeatCount, embersCode, embersAspectNames, embersInputNames,
                embersSeed, embersCanInfer, embersCodeFromCache, executionMachineSupportsGui,
                baseItem, boundMachineTypes, leftovers, clickedOutput, graph, executionBlocked,
                machineCandidates, Map.of(), null, List.of());
    }

    /** Backward-compatible constructor for callers that already provide step issues. */
    public PlanResponse(boolean success, String targetName, ItemStack targetResult,
                        List<PlanStep> steps, Map<IngredientKey, Availability> materials,
                        List<String> missing, String recipeId,
                        @Nullable String executionModTypeId, @Nullable String executionDim,
                        int executionPosX, int executionPosY, int executionPosZ,
                        List<Component> modWarnings, int repeatCount,
                        @Nullable int[] embersCode, @Nullable Component[] embersAspectNames,
                        @Nullable Component[] embersInputNames, long embersSeed,
                        boolean embersCanInfer, boolean embersCodeFromCache,
                        boolean executionMachineSupportsGui, @Nullable ItemStack baseItem,
                        Set<String> boundMachineTypes, Map<IngredientKey, Long> leftovers,
                        @Nullable ItemStack clickedOutput, @Nullable PlanGraphView graph,
                        boolean executionBlocked, List<MachineCandidateView> machineCandidates,
                        Map<ResourceLocation, StepIssue> stepIssues) {
        this(success, targetName, targetResult, steps, materials, missing, recipeId,
                executionModTypeId, executionDim, executionPosX, executionPosY, executionPosZ,
                modWarnings, repeatCount, embersCode, embersAspectNames, embersInputNames,
                embersSeed, embersCanInfer, embersCodeFromCache, executionMachineSupportsGui,
                baseItem, boundMachineTypes, leftovers, clickedOutput, graph, executionBlocked,
                machineCandidates, stepIssues, null, List.of());
    }

    public Availability availability(ItemStack stack) {
        Availability exact = materials.get(IngredientKey.of(stack));
        if (exact != null || !stack.hasTag()) return exact;
        // Non-strict ingredients are represented by a tagless material card even
        // when the tree keeps a real stored variant for display.  Preserve the
        // lookup for that tree node without weakening strict-NBT cards.
        return materials.get(IngredientKey.of(new ItemStack(stack.getItem())));
    }

    public Availability availability(IngredientKey key) {
        Availability exact = materials.get(key);
        if (exact != null || !key.stack(1).hasTag()) return exact;
        return materials.get(IngredientKey.of(new ItemStack(key.item())));
    }

    /** Gross tree demand and real stock are separate from the net external shortage. */
    public record Availability(long needed, long available, long missingCount) {
        public Availability(long needed, long available) {
            this(needed, available, Math.max(0L, needed - available));
        }

        public Availability {
            if (missingCount < 0) throw new IllegalArgumentException("negative material shortage");
        }

        public boolean isEnough() { return available >= needed; }
        public boolean isPartial() { return available > 0 && available < needed; }
    }

    /** Backward-compat: plans without an explicit hard prerequisite gate. */
    public PlanResponse(boolean success, String targetName, ItemStack targetResult,
                        List<PlanStep> steps, Map<IngredientKey, Availability> materials,
                        List<String> missing, String recipeId,
                        @Nullable String executionModTypeId, @Nullable String executionDim,
                        int executionPosX, int executionPosY, int executionPosZ,
                        List<Component> modWarnings, int repeatCount,
                        @Nullable int[] embersCode, @Nullable Component[] embersAspectNames,
                        @Nullable Component[] embersInputNames, long embersSeed,
                        boolean embersCanInfer, boolean embersCodeFromCache,
                        boolean executionMachineSupportsGui, @Nullable ItemStack baseItem,
                        Set<String> boundMachineTypes, Map<IngredientKey, Long> leftovers,
                        @Nullable ItemStack clickedOutput, @Nullable PlanGraphView graph) {
        this(success, targetName, targetResult, steps, materials, missing, recipeId,
                executionModTypeId, executionDim, executionPosX, executionPosY, executionPosZ,
                modWarnings, repeatCount, embersCode, embersAspectNames, embersInputNames,
                embersSeed, embersCanInfer, embersCodeFromCache, executionMachineSupportsGui,
                baseItem, boundMachineTypes, leftovers, clickedOutput, graph, false, List.of(), Map.of(), null, List.of());
    }

    /** Backward-compat: plans without target-machine candidate snapshots. */
    public PlanResponse(boolean success, String targetName, ItemStack targetResult,
                        List<PlanStep> steps, Map<IngredientKey, Availability> materials,
                        List<String> missing, String recipeId,
                        @Nullable String executionModTypeId, @Nullable String executionDim,
                        int executionPosX, int executionPosY, int executionPosZ,
                        List<Component> modWarnings, int repeatCount,
                        @Nullable int[] embersCode, @Nullable Component[] embersAspectNames,
                        @Nullable Component[] embersInputNames, long embersSeed,
                        boolean embersCanInfer, boolean embersCodeFromCache,
                        boolean executionMachineSupportsGui, @Nullable ItemStack baseItem,
                        Set<String> boundMachineTypes, Map<IngredientKey, Long> leftovers,
                        @Nullable ItemStack clickedOutput, @Nullable PlanGraphView graph,
                        boolean executionBlocked) {
        this(success, targetName, targetResult, steps, materials, missing, recipeId,
                executionModTypeId, executionDim, executionPosX, executionPosY, executionPosZ,
                modWarnings, repeatCount, embersCode, embersAspectNames, embersInputNames,
                embersSeed, embersCanInfer, embersCodeFromCache, executionMachineSupportsGui,
                baseItem, boundMachineTypes, leftovers, clickedOutput, graph, executionBlocked,
                List.of(), Map.of(), null, List.of());
    }

    /** Backward-compat: no execution routing info (vanilla/generic path). */
    public PlanResponse(boolean success, String targetName, ItemStack targetResult,
                        List<PlanStep> steps, Map<IngredientKey, Availability> materials,
                        List<String> missing, String recipeId) {
        this(success, targetName, targetResult, steps, materials, missing, recipeId,
                null, null, 0, 0, 0, Collections.emptyList(), 1,
                null, null, null, 0, false, false, false, null, Collections.emptySet(),
                Collections.emptyMap(), null, null, false, List.of(), Map.of(), null, List.of());
    }

    /** Backward-compat: no mod warnings. */
    public PlanResponse(boolean success, String targetName, ItemStack targetResult,
                        List<PlanStep> steps, Map<IngredientKey, Availability> materials,
                        List<String> missing, String recipeId,
                        @Nullable String executionModTypeId,
                        @Nullable String executionDim,
                        int executionPosX, int executionPosY, int executionPosZ) {
        this(success, targetName, targetResult, steps, materials, missing, recipeId,
                executionModTypeId, executionDim, executionPosX, executionPosY, executionPosZ,
                Collections.emptyList(), 1,
                null, null, null, 0, false, false, false, null, Collections.emptySet(),
                Collections.emptyMap(), null, null, false, List.of(), Map.of(), null, List.of());
    }

    /** Backward-compat: no embers data. */
    public PlanResponse(boolean success, String targetName, ItemStack targetResult,
                        List<PlanStep> steps, Map<IngredientKey, Availability> materials,
                        List<String> missing, String recipeId,
                        @Nullable String executionModTypeId,
                        @Nullable String executionDim,
                        int executionPosX, int executionPosY, int executionPosZ,
                        List<Component> modWarnings, int repeatCount) {
        this(success, targetName, targetResult, steps, materials, missing, recipeId,
                executionModTypeId, executionDim, executionPosX, executionPosY, executionPosZ,
                modWarnings, repeatCount,
                null, null, null, 0, false, false, false, null, Collections.emptySet(),
                Collections.emptyMap(), null, null, false, List.of(), Map.of(), null, List.of());
    }
}
