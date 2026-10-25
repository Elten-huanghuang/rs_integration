package com.huanghuang.rsintegration.api.execution;

import com.huanghuang.rsintegration.api.diagnostic.IntegrationDiagnostic;
import com.huanghuang.rsintegration.api.diagnostic.IntegrationDiagnosticCode;
import com.huanghuang.rsintegration.api.diagnostic.IntegrationSeverity;
import com.huanghuang.rsintegration.api.machine.MachineContext;
import com.huanghuang.rsintegration.api.recipe.RecipeView;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;

public interface RecipeExecutionIntegration {
    ResourceLocation id();

    boolean supports(RecipeView recipe, MachineContext machine);

    ExecutionPlanResult createPlan(ExecutionRequestContext context);

    ExecutionResult start(OperationContext context, ExecutionPlan plan);

    default ExecutionResult resume(
            OperationContext context,
            ExecutionPlan plan,
            OperationSnapshot snapshot
    ) {
        return ExecutionResult.rejected(new IntegrationDiagnostic(
                IntegrationDiagnosticCode.EXECUTION_OPERATION_STALE,
                IntegrationSeverity.ERROR,
                id(),
                Map.of("reason", "resume_not_supported")
        ));
    }
}
