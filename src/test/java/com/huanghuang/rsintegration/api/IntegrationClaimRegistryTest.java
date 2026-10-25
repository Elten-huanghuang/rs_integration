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
import com.huanghuang.rsintegration.api.machine.MachineContext;
import com.huanghuang.rsintegration.api.machine.MachineDescriptorResult;
import com.huanghuang.rsintegration.api.machine.MachineIntegration;
import com.huanghuang.rsintegration.api.recipe.RecipeFlags;
import com.huanghuang.rsintegration.api.recipe.RecipeInspectionResult;
import com.huanghuang.rsintegration.api.recipe.RecipeIntegration;
import com.huanghuang.rsintegration.api.recipe.RecipeView;
import net.minecraft.core.BlockPos;
import net.minecraft.SharedConstants;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class IntegrationClaimRegistryTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

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

        private StubMachine(String path, boolean supports) {
            this.id = new ResourceLocation("test", path);
            this.supports = supports;
        }

        @Override
        public ResourceLocation id() {
            return id;
        }

        @Override
        public boolean supports(BlockEntity blockEntity) {
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

    private static final class StubExecution
            implements RecipeExecutionIntegration {
        private final ResourceLocation id;
        private final boolean supports;

        private StubExecution(String path, boolean supports) {
            this.id = new ResourceLocation("test", path);
            this.supports = supports;
        }

        @Override
        public ResourceLocation id() {
            return id;
        }

        @Override
        public boolean supports(RecipeView recipe, MachineContext machine) {
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
