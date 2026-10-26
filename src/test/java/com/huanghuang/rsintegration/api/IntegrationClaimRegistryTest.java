package com.huanghuang.rsintegration.api;

import com.huanghuang.rsintegration.api.diagnostic.IntegrationDiagnostic;
import com.huanghuang.rsintegration.api.diagnostic.IntegrationDiagnosticCode;
import com.huanghuang.rsintegration.api.execution.ExecutionClaim;
import com.huanghuang.rsintegration.api.execution.ExecutionPlanResult;
import com.huanghuang.rsintegration.api.execution.ExecutionResult;
import com.huanghuang.rsintegration.api.execution.OperationContext;
import com.huanghuang.rsintegration.api.execution.RecipeExecutionIntegration;
import com.huanghuang.rsintegration.api.execution.ExecutionRequestContext;
import com.huanghuang.rsintegration.api.execution.ExecutionPlan;
import com.huanghuang.rsintegration.api.machine.MachineClaim;
import com.huanghuang.rsintegration.api.machine.MachineBinding;
import com.huanghuang.rsintegration.api.machine.MachineContext;
import com.huanghuang.rsintegration.api.machine.MachineDescriptorResult;
import com.huanghuang.rsintegration.api.machine.MachineIntegration;
import com.huanghuang.rsintegration.api.recipe.RecipeFlags;
import com.huanghuang.rsintegration.api.recipe.RecipeContext;
import com.huanghuang.rsintegration.api.recipe.RecipeInspectionResult;
import com.huanghuang.rsintegration.api.recipe.RecipeIntegration;
import com.huanghuang.rsintegration.api.recipe.RecipeView;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class IntegrationClaimRegistryTest extends BootstrapTest {
    @Test
    void machineClaimUsesHighestPriorityAndRejectsTies() {
        DefaultRSIIntegrationApi api = new DefaultRSIIntegrationApi();
        BlockEntity blockEntity = mock(BlockEntity.class);
        StubMachine low = new StubMachine("low", true);
        StubMachine high = new StubMachine("high", true);
        StubMachine tied = new StubMachine("tied", true);

        api.beginRegistration();
        api.registerMachine(low, new RegistrationOptions(10, Map.of()));
        api.registerMachine(high, new RegistrationOptions(20, Map.of()));

        MachineClaim unambiguous = api.machines().claim(blockEntity);
        assertSame(high, unambiguous.winner());
        assertEquals(List.of(high), unambiguous.candidates());

        api.registerMachine(tied, new RegistrationOptions(20, Map.of()));
        MachineClaim ambiguous = api.machines().claim(blockEntity);
        assertNull(ambiguous.winner());
        assertTrue(ambiguous.ambiguous());
        assertEquals(
                IntegrationDiagnosticCode.MACHINE_CLAIM_AMBIGUOUS,
                ambiguous.diagnostics().get(0).code());
    }

    @Test
    void executionClaimUsesHighestPriorityAndRejectsTies() {
        DefaultRSIIntegrationApi api = new DefaultRSIIntegrationApi();
        StubExecution low = new StubExecution("low", true);
        StubExecution high = new StubExecution("high", true);
        StubExecution tied = new StubExecution("tied", true);

        api.beginRegistration();
        api.registerExecution(low, new RegistrationOptions(10, Map.of()));
        api.registerExecution(high, new RegistrationOptions(20, Map.of()));

        ExecutionClaim unambiguous = api.executions().claim(recipeView(), machineContext());
        assertSame(high, unambiguous.winner());
        assertEquals(List.of(high), unambiguous.candidates());

        api.registerExecution(tied, new RegistrationOptions(20, Map.of()));
        ExecutionClaim ambiguous = api.executions().claim(recipeView(), machineContext());
        assertNull(ambiguous.winner());
        assertTrue(ambiguous.ambiguous());
        assertEquals(
                IntegrationDiagnosticCode.EXECUTION_CLAIM_AMBIGUOUS,
                ambiguous.diagnostics().get(0).code());
    }

    @Test
    void failingCandidateDoesNotAbortMachineOrExecutionScan() {
        DefaultRSIIntegrationApi api = new DefaultRSIIntegrationApi();
        BlockEntity blockEntity = mock(BlockEntity.class);
        api.beginRegistration();
        api.registerMachine(
                new StubMachine("throwing_machine", true, true),
                new RegistrationOptions(100, Map.of())
        );
        StubMachine machine = new StubMachine("working_machine", true);
        api.registerMachine(machine);
        api.registerExecution(
                new StubExecution("throwing_execution", true, true),
                new RegistrationOptions(100, Map.of())
        );
        StubExecution execution = new StubExecution(
                "working_execution", true);
        api.registerExecution(execution);

        MachineClaim machineClaim = api.machines().claim(blockEntity);
        ExecutionClaim executionClaim = api.executions().claim(
                recipeView(), machineContext());

        assertSame(machine, machineClaim.winner());
        assertSame(execution, executionClaim.winner());
        assertEquals(IntegrationDiagnosticCode.MACHINE_UNSUPPORTED,
                machineClaim.diagnostics().get(0).code());
        assertEquals(IntegrationDiagnosticCode.EXECUTION_CONTRACT_VIOLATION,
                executionClaim.diagnostics().get(0).code());
    }

    @Test
    void failingRecipeCandidateDoesNotAbortClaimScan() {
        DefaultRSIIntegrationApi api = new DefaultRSIIntegrationApi();
        api.beginRegistration();
        api.registerRecipe(
                new StubRecipe("throwing_recipe", true),
                new RegistrationOptions(100, Map.of())
        );
        StubRecipe recipe = new StubRecipe("working_recipe", false);
        api.registerRecipe(recipe);

        var claim = api.recipes().claim(mock(Recipe.class));

        assertSame(recipe, claim.winner());
        assertEquals(IntegrationDiagnosticCode.RECIPE_INSPECTION_FAILED,
                claim.diagnostics().get(0).code());
    }

    private static RecipeView recipeView() {
        return new RecipeView(
                new ResourceLocation("test", "recipe"),
                new ResourceLocation("test", "process"),
                List.of(),
                List.of(),
                List.of(),
                new RecipeFlags(
                        false, false, false, false,
                        false, false, false)
        );
    }

    private static MachineContext machineContext() {
        return new MachineContext(
                new ResourceLocation("test", "machine"),
                mock(ServerLevel.class),
                BlockPos.ZERO,
                null
        );
    }

    private static final class StubMachine implements MachineIntegration {
        private final ResourceLocation id;
        private final boolean supports;
        private final boolean throwing;

        private StubMachine(String path, boolean supports) {
            this(path, supports, false);
        }

        private StubMachine(
                String path,
                boolean supports,
                boolean throwing
        ) {
            this.id = new ResourceLocation("test", path);
            this.supports = supports;
            this.throwing = throwing;
        }

        @Override
        public ResourceLocation id() {
            return id;
        }

        @Override
        public List<MachineBinding> bindings() {
            return List.of(MachineBinding.blocks(
                    Set.of(new ResourceLocation(
                            "test", "process")),
                    true,
                    new ResourceLocation("test", "machine")
            ));
        }

        @Override
        public boolean supports(BlockEntity blockEntity) {
            if (throwing) throw new IllegalStateException("machine failure");
            return supports;
        }

        @Override
        public MachineDescriptorResult describe(
                MachineContext context,
                BlockEntity blockEntity
        ) {
            return MachineDescriptorResult.rejected(IntegrationDiagnostic.error(
                    IntegrationDiagnosticCode.MACHINE_DESCRIPTOR_INVALID));
        }
    }

    private static final class StubRecipe
            implements RecipeIntegration<Recipe<?>> {
        private final ResourceLocation id;
        private final boolean throwing;

        private StubRecipe(String path, boolean throwing) {
            this.id = new ResourceLocation("test", path);
            this.throwing = throwing;
        }

        @Override
        public ResourceLocation id() {
            return id;
        }

        @Override
        public boolean supports(Recipe<?> recipe) {
            if (throwing) throw new IllegalStateException("recipe failure");
            return true;
        }

        @Override
        public RecipeInspectionResult inspect(
                RecipeContext context,
                Recipe<?> recipe
        ) {
            return RecipeInspectionResult.rejected(IntegrationDiagnostic.error(
                    IntegrationDiagnosticCode.RECIPE_INSPECTION_FAILED));
        }
    }

    private static final class StubExecution
            implements RecipeExecutionIntegration {
        private final ResourceLocation id;
        private final boolean supports;
        private final boolean throwing;

        private StubExecution(String path, boolean supports) {
            this(path, supports, false);
        }

        private StubExecution(
                String path,
                boolean supports,
                boolean throwing
        ) {
            this.id = new ResourceLocation("test", path);
            this.supports = supports;
            this.throwing = throwing;
        }

        @Override
        public ResourceLocation id() {
            return id;
        }

        @Override
        public boolean supports(RecipeView recipe, MachineContext machine) {
            if (throwing) throw new IllegalStateException("execution failure");
            return supports;
        }

        @Override
        public ExecutionPlanResult createPlan(ExecutionRequestContext context) {
            return ExecutionPlanResult.rejected(IntegrationDiagnostic.error(
                    IntegrationDiagnosticCode.EXECUTION_PREPARATION_FAILED));
        }

        @Override
        public ExecutionResult start(
                OperationContext context,
                ExecutionPlan plan
        ) {
            return ExecutionResult.rejected(IntegrationDiagnostic.error(
                    IntegrationDiagnosticCode.EXECUTION_START_REJECTED));
        }
    }
}
