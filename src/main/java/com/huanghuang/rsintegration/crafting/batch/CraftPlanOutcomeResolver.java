package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.crafting.plan.IntegrationDiagnosticCode;
import com.huanghuang.rsintegration.crafting.plan.PlanOutcome;

/** 根据互斥的执行前置条件选择计划终态和诊断码。 */
final class CraftPlanOutcomeResolver {
    private CraftPlanOutcomeResolver() {}

    record Resolution(PlanOutcome outcome, IntegrationDiagnosticCode diagnostic) {}

    static Resolution resolve(
            boolean feasible,
            boolean timedOutMissingRoute,
            boolean allExecutionMachinesLeased,
            boolean missingExecutionBinding,
            boolean missingExecutionMachineType,
            boolean noReadyMachine,
            boolean cyclicFailure,
            boolean nbtMismatch,
            boolean materialShortage,
            boolean blockingPrerequisiteFailure) {
        if (feasible && !blockingPrerequisiteFailure && !noReadyMachine
                && !allExecutionMachinesLeased
                && !missingExecutionBinding && !missingExecutionMachineType) {
            return diagnostic(PlanOutcome.READY);
        }
        if (timedOutMissingRoute) {
            return diagnostic(PlanOutcome.BUDGET_EXHAUSTED,
                    IntegrationDiagnosticCode.COMPLEXITY_LIMIT);
        }
        if (allExecutionMachinesLeased) {
            return diagnostic(PlanOutcome.RUNTIME_UNAVAILABLE,
                    IntegrationDiagnosticCode.MACHINES_LEASED);
        }
        if (missingExecutionBinding || missingExecutionMachineType) {
            return diagnostic(PlanOutcome.RUNTIME_UNAVAILABLE,
                    IntegrationDiagnosticCode.NO_BOUND_MACHINE);
        }
        if (noReadyMachine) {
            return diagnostic(PlanOutcome.RUNTIME_UNAVAILABLE,
                    IntegrationDiagnosticCode.NO_READY_MACHINE);
        }
        if (cyclicFailure) {
            return diagnostic(PlanOutcome.CYCLE,
                    IntegrationDiagnosticCode.CYCLIC_DEPENDENCY);
        }
        if (nbtMismatch) {
            return diagnostic(PlanOutcome.MISSING_INPUTS,
                    IntegrationDiagnosticCode.NBT_MISMATCH);
        }
        if (materialShortage) {
            return diagnostic(PlanOutcome.MISSING_INPUTS,
                    IntegrationDiagnosticCode.MISSING_MATERIALS);
        }
        if (blockingPrerequisiteFailure) {
            return diagnostic(PlanOutcome.RUNTIME_UNAVAILABLE,
                    IntegrationDiagnosticCode.PREREQUISITE_UNAVAILABLE);
        }
        return diagnostic(PlanOutcome.RUNTIME_UNAVAILABLE,
                IntegrationDiagnosticCode.INTERNAL_ERROR);
    }

    private static Resolution diagnostic(PlanOutcome outcome) {
        return new Resolution(outcome, null);
    }

    private static Resolution diagnostic(
            PlanOutcome outcome, IntegrationDiagnosticCode diagnostic) {
        return new Resolution(outcome, diagnostic);
    }
}
