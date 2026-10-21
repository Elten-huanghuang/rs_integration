package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.crafting.plan.IntegrationDiagnosticCode;
import com.huanghuang.rsintegration.crafting.plan.PlanOutcome;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CraftPlanOutcomeResolverTest {
    @Test
    void readyRequiresEveryExecutionPrerequisite() {
        CraftPlanOutcomeResolver.Resolution ready = resolve(
                true, false, false, false, false, false, false, false, false, false);

        assertEquals(PlanOutcome.READY, ready.outcome());
        assertNull(ready.diagnostic());

        assertEquals(PlanOutcome.MISSING_INPUTS,
                resolve(false, false, false, false, false, false, false, false, true, false)
                        .outcome());
        assertEquals(PlanOutcome.RUNTIME_UNAVAILABLE,
                resolve(false, false, false, false, false, false, false, false, false, true)
                        .outcome());
    }

    @Test
    void timeoutPrecedesCycleAndMaterialClassification() {
        CraftPlanOutcomeResolver.Resolution resolution = resolve(
                false, true, true, true, true, true, true, true, true, true);

        assertEquals(PlanOutcome.BUDGET_EXHAUSTED, resolution.outcome());
        assertEquals(IntegrationDiagnosticCode.COMPLEXITY_LIMIT,
                resolution.diagnostic());
    }

    @Test
    void cycleIsReportedInsteadOfHidingItAsMissingInputs() {
        CraftPlanOutcomeResolver.Resolution resolution = resolve(
                false, false, false, false, false, false, true, false, true, false);

        assertEquals(PlanOutcome.CYCLE, resolution.outcome());
        assertEquals(IntegrationDiagnosticCode.CYCLIC_DEPENDENCY,
                resolution.diagnostic());
    }

    @Test
    void bindingAndMachineReadinessKeepTheirOwnDiagnostics() {
        CraftPlanOutcomeResolver.Resolution binding = resolve(
                false, false, false, false, true, true, true, true, true, true);
        assertEquals(IntegrationDiagnosticCode.NO_BOUND_MACHINE, binding.diagnostic());

        CraftPlanOutcomeResolver.Resolution readiness = resolve(
                false, false, false, false, false, true, true, true, true, true);
        assertEquals(IntegrationDiagnosticCode.NO_READY_MACHINE,
                readiness.diagnostic());
    }

    @Test
    void ordinaryShortageAndNbtShortageRemainDistinct() {
        assertEquals(IntegrationDiagnosticCode.NBT_MISMATCH,
                resolve(false, false, false, false, false, false, false, true, true, false)
                        .diagnostic());
        assertEquals(IntegrationDiagnosticCode.MISSING_MATERIALS,
                resolve(false, false, false, false, false, false, false, false, true, false)
                        .diagnostic());
    }

    private static CraftPlanOutcomeResolver.Resolution resolve(
            boolean feasible, boolean timedOutMissingRoute,
            boolean allExecutionMachinesLeased, boolean missingExecutionBinding,
            boolean missingExecutionMachineType, boolean noReadyMachine,
            boolean cyclicFailure, boolean nbtMismatch, boolean materialShortage,
            boolean blockingPrerequisiteFailure) {
        return CraftPlanOutcomeResolver.resolve(
                feasible, timedOutMissingRoute, allExecutionMachinesLeased,
                missingExecutionBinding, missingExecutionMachineType, noReadyMachine,
                cyclicFailure, nbtMismatch, materialShortage,
                blockingPrerequisiteFailure);
    }
}
