package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanGraph;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.crafting.graph.InputDemand;

import java.util.Collection;

/** Selects the executor shape for a resolved crafting plan and records why fallback is required. */
final class GraphExecutionPolicy {

    enum Reason {
        GRAPH_COMPLETE,
        APPENDED_TERMINAL_NOT_IN_GRAPH,
        MOD_REQUIRES_FLAT_EXECUTION,
        STATEFUL_GENERIC_REUSE_REQUIRES_FLAT
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
        if (requiresFlatStatefulReuse(graph)) {
            return new Decision(false, Reason.STATEFUL_GENERIC_REUSE_REQUIRES_FLAT,
                    "generic catalyst or durability input requires ordered reuse");
        }
        return decide(false, (ModTypeConstraint) null);
    }

    static boolean requiresFlatStatefulReuse(CraftPlanGraph graph) {
        if (graph == null) return false;
        return graph.nodes().stream().anyMatch(node ->
                ModType.GENERIC.id().equals(node.modTypeId())
                        && node.inputs().stream().map(InputDemand::role)
                        .anyMatch(role -> role == DemandRole.CATALYST
                                || role == DemandRole.DURABILITY));
    }

    private static String constraintReason(ModType type) {
        if (type == null) return "unknown mod type";
        return type.graphExecutionAudit() == ModType.GraphExecutionAudit.GRAPH_SAFE
                ? null : type.graphExecutionAuditReason();
    }

    record ModTypeConstraint(boolean requiresFlatExecution, String reason) {}
}
