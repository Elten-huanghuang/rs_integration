package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanGraph;
import net.minecraft.resources.ResourceLocation;

import java.util.Collection;

/** Selects the executor shape for a resolved crafting plan and records why fallback is required. */
final class GraphExecutionPolicy {

    enum Reason {
        GRAPH_COMPLETE,
        APPENDED_TERMINAL_NOT_IN_GRAPH,
        MOD_REQUIRES_FLAT_EXECUTION,
        SEQUENTIAL_MACHINE_BATCH_REQUIRES_FLAT
    }

    record Decision(boolean useGraphExecutor, Reason reason, String detail) {}

    private GraphExecutionPolicy() {}

    static boolean useGraphExecutor(boolean hasAppendedTerminalStep) {
        return decide(hasAppendedTerminalStep, (ModTypeConstraint) null).useGraphExecutor();
    }

    static Decision decide(boolean hasAppendedTerminalStep, ModTypeConstraint constraint) {
        if (hasAppendedTerminalStep) {
            return new Decision(false, Reason.APPENDED_TERMINAL_NOT_IN_GRAPH,
                    "terminal recipe is represented only by compatibilitySteps");
        }
        if (constraint != null && constraint.requiresFlatExecution()) {
            return new Decision(false, Reason.MOD_REQUIRES_FLAT_EXECUTION, constraint.reason());
        }
        return new Decision(true, Reason.GRAPH_COMPLETE, "graph contains every executable operation");
    }

    static Decision decide(boolean hasAppendedTerminalStep, ModType type) {
        String reason = constraintReason(type);
        return decide(hasAppendedTerminalStep,
                reason == null ? null : new ModTypeConstraint(true, type.id() + ": " + reason));
    }

    static Decision decide(boolean hasAppendedTerminalStep, Collection<ModType> types) {
        return decide(hasAppendedTerminalStep, types, null);
    }

    static Decision decide(boolean hasAppendedTerminalStep, Collection<ModType> types,
                           CraftPlanGraph graph) {
        if (hasAppendedTerminalStep) {
            return decide(true, (ModTypeConstraint) null);
        }
        if (types != null) {
            for (ModType type : types) {
                String reason = constraintReason(type);
                if (reason != null) {
                    return decide(false, new ModTypeConstraint(true,
                            type.id() + ": " + reason));
                }
            }
        }
        if (requiresFlatSequentialMachineBatch(graph)) {
            return new Decision(false, Reason.SEQUENTIAL_MACHINE_BATCH_REQUIRES_FLAT,
                    "Arcane Iterator executes repeated operations sequentially");
        }
        return decide(false, (ModTypeConstraint) null);
    }

    static boolean requiresFlatSequentialMachineBatch(CraftPlanGraph graph) {
        if (graph == null) return false;
        ResourceLocation iteratorType = new ResourceLocation("wizards_reborn", "arcane_iterator");
        for (var node : graph.nodes()) {
            if (node.executions() <= 1) continue;
            if (iteratorType.equals(node.recipeTypeId())
                    || isArcaneIteratorRecipe(node.recipeId())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isArcaneIteratorRecipe(ResourceLocation recipeId) {
        return recipeId != null && "wizards_reborn".equals(recipeId.getNamespace())
                && (recipeId.getPath().equals("arcane_iterator")
                || recipeId.getPath().startsWith("arcane_iterator/"));
    }

    private static String constraintReason(ModType type) {
        if (type == null) return "unknown mod type";
        return type.graphExecutionAudit() == ModType.GraphExecutionAudit.GRAPH_SAFE
                ? null : type.graphExecutionAuditReason();
    }

    record ModTypeConstraint(boolean requiresFlatExecution, String reason) {}
}
