package com.huanghuang.rsintegration.api.execution;

import com.huanghuang.rsintegration.api.IntegrationResourceKey;
import com.huanghuang.rsintegration.api.diagnostic.IntegrationDiagnostic;
import com.huanghuang.rsintegration.api.diagnostic.IntegrationDiagnosticCode;
import com.huanghuang.rsintegration.api.diagnostic.IntegrationSeverity;
import com.huanghuang.rsintegration.api.machine.ConcurrencyCapabilities;
import com.huanghuang.rsintegration.api.machine.MachineContext;
import com.huanghuang.rsintegration.api.machine.SupportScope;
import com.huanghuang.rsintegration.api.recipe.InputConsumption;
import com.huanghuang.rsintegration.api.recipe.OutputAmount;
import com.huanghuang.rsintegration.api.recipe.OutputKind;
import com.huanghuang.rsintegration.api.recipe.OutputProbability;
import com.huanghuang.rsintegration.api.recipe.RecipeView;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionContractTest {
    @Test
    void executionResultEnforcesStartRetryAndRejectionShapes() {
        RunningOperation operation = new StubOperation();
        ExecutionResult started = ExecutionResult.started(operation);

        assertEquals(ExecutionStartStatus.STARTED, started.status());
        assertEquals(operation, started.operation());
        assertEquals(ExecutionStartStatus.RETRY,
                ExecutionResult.retry(0).status());
        assertThrows(IllegalArgumentException.class, () -> new ExecutionResult(
                ExecutionStartStatus.STARTED, null, 0, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ExecutionResult(
                ExecutionStartStatus.STARTED, operation, 1, List.of()));
        assertThrows(IllegalArgumentException.class,
                ExecutionResult::rejected);
    }

    @Test
    void defaultResumeRejectsStaleOperationWithStableDiagnostic() {
        StubExecutionIntegration integration = new StubExecutionIntegration();

        ExecutionResult result = integration.resume(null, null, null);

        assertEquals(ExecutionStartStatus.REJECTED, result.status());
        assertEquals(IntegrationDiagnosticCode.EXECUTION_OPERATION_STALE,
                result.diagnostics().get(0).code());
    }

    @Test
    void operationSnapshotDefensivelyCopiesState() {
        CompoundTag tag = new CompoundTag();
        tag.putString("stage", "working");
        OperationSnapshot snapshot = new OperationSnapshot(
                LifecyclePhase.WORKING, 1L, tag);
        tag.putString("mutated", "true");

        assertFalse(snapshot.state().contains("mutated"));
        CompoundTag returned = snapshot.state();
        returned.putString("caller", "mutation");
        assertFalse(snapshot.state().contains("caller"));
    }

    @Test
    void cleanupCannotBeCompleteWithUncertainMaterials() {
        ReservedMaterial reserved = new ReservedMaterial(
                "input",
                new IntegrationResourceKey(
                        IntegrationResourceKey.ResourceKind.ITEM,
                        new ResourceLocation("test", "input"),
                        new CompoundTag()),
                1L,
                InputConsumption.consumed());
        MaterialDisposition uncertain = new MaterialDisposition(
                reserved, MaterialOutcome.UNCERTAIN);

        assertThrows(IllegalArgumentException.class,
                () -> new OperationCleanupResult(
                        true, List.of(uncertain), List.of(), List.of()));
        assertFalse(new OperationCleanupResult(
                false, List.of(uncertain), List.of(), List.of()).cleanupComplete());
    }

    @Test
    void executionPlanRejectsDuplicateRoutesAndOutputs() {
        InputRoute input = new InputRoute(
                "input", 0, "Ingredients", 1L);
        OutputPort output = outputPort("output");

        assertEquals("ingredients", input.inputGroup());
        assertThrows(IllegalArgumentException.class, () -> plan(
                List.of(input, input), List.of(), List.of(output)));
        assertThrows(IllegalArgumentException.class, () -> plan(
                List.of(input), List.of(), List.of(output, output)));
    }

    @Test
    void energyRoutesRemainExplicitButSeparateFromMaterials() {
        EnergyRoute energy = new EnergyRoute("forge:energy", 100L);
        ExecutionPlan plan = plan(List.of(), List.of(energy), List.of());

        assertEquals(1, plan.energyInputs().size());
        assertTrue(plan.itemInputs().isEmpty());
        assertTrue(plan.fluidInputs().isEmpty());
    }

    private static ExecutionPlan plan(
            List<InputRoute> itemInputs,
            List<EnergyRoute> energyInputs,
            List<OutputPort> outputs
    ) {
        return new ExecutionPlan(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                new ResourceLocation("test", "recipe"),
                new ResourceLocation("test", "machine"),
                new ResourceLocation("test", "recipe_integration"),
                new ResourceLocation("test", "machine_integration"),
                new ResourceLocation("test", "execution_integration"),
                1L,
                1L,
                "v1:recipe",
                "machine-v1:descriptor",
                itemInputs,
                List.of(),
                energyInputs,
                20,
                outputs,
                new SupportScope(List.of(BlockPos.ZERO), List.of(), false),
                ConcurrencyCapabilities.machineSlot()
        );
    }

    private static OutputPort outputPort(String path) {
        return new OutputPort(
                new ResourceLocation("test", path),
                "output",
                new IntegrationResourceKey(
                        IntegrationResourceKey.ResourceKind.ITEM,
                        new ResourceLocation("test", "result"),
                        new CompoundTag()),
                OutputAmount.exactly(1L),
                OutputKind.PRIMARY,
                OutputProbability.guaranteed(),
                false,
                false,
                null
        );
    }

    private static final class StubExecutionIntegration
            implements RecipeExecutionIntegration {
        @Override
        public ResourceLocation id() {
            return new ResourceLocation("test", "execution");
        }

        @Override
        public boolean supports(
                RecipeView recipe,
                MachineContext machine
        ) {
            return false;
        }

        @Override
        public ExecutionPlanResult createPlan(ExecutionRequestContext context) {
            return ExecutionPlanResult.rejected(new IntegrationDiagnostic(
                    IntegrationDiagnosticCode.EXECUTION_PREPARATION_FAILED,
                    IntegrationSeverity.ERROR,
                    id(),
                    Map.of()));
        }

        @Override
        public ExecutionResult start(OperationContext context, ExecutionPlan plan) {
            return ExecutionResult.rejected(new IntegrationDiagnostic(
                    IntegrationDiagnosticCode.EXECUTION_START_REJECTED,
                    IntegrationSeverity.ERROR,
                    id(),
                    Map.of()));
        }
    }

    private static final class StubOperation implements RunningOperation {
        @Override
        public CraftObservation observe(ServerLevel level) {
            return new CraftObservation(
                    LifecyclePhase.WORKING, 0, 20, 1L, null, List.of());
        }

        @Override
        public OperationCollection collect(
                ServerPlayer player) {
            return new OperationCollection(1L, List.of(), List.of(), false, List.of());
        }

        @Override
        public OperationSnapshot snapshot() {
            return new OperationSnapshot(LifecyclePhase.WORKING, 0L, new CompoundTag());
        }

        @Override
        public OperationCleanupResult onFailure(
                FailureReason reason,
                ServerPlayer player) {
            return new OperationCleanupResult(true, List.of(), List.of(), List.of());
        }

        @Override
        public OperationCleanupResult onCancel(
                CancelReason reason,
                ServerPlayer player) {
            return new OperationCleanupResult(true, List.of(), List.of(), List.of());
        }

        @Override
        public void onSettled(ServerPlayer player) {}
    }
}
