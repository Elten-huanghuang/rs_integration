package com.huanghuang.rsintegration.api;

import com.huanghuang.rsintegration.api.execution.RecipeExecutionIntegration;
import com.huanghuang.rsintegration.api.execution.ExecutionIntegrationRegistry;
import com.huanghuang.rsintegration.api.machine.MachineIntegration;
import com.huanghuang.rsintegration.api.machine.MachineIntegrationRegistry;
import com.huanghuang.rsintegration.api.recipe.RecipeIntegration;
import com.huanghuang.rsintegration.api.recipe.RecipeIntegrationRegistry;

import java.util.Optional;

public interface RSIIntegrationApi {
    int API_MAJOR = 1;
    int API_MINOR = 0;

    ApiVersion version();

    RegistrationPhase phase();

    RecipeIntegrationRegistry recipes();

    MachineIntegrationRegistry machines();

    ExecutionIntegrationRegistry executions();

    RegistrationResult registerRecipe(
            RecipeIntegration<?> integration,
            RegistrationOptions options
    );

    RegistrationResult registerMachine(
            MachineIntegration integration,
            RegistrationOptions options
    );

    RegistrationResult registerExecution(
            RecipeExecutionIntegration integration,
            RegistrationOptions options
    );

    default RegistrationResult registerRecipe(RecipeIntegration<?> integration) {
        return registerRecipe(integration, RegistrationOptions.defaults());
    }

    default RegistrationResult registerMachine(MachineIntegration integration) {
        return registerMachine(integration, RegistrationOptions.defaults());
    }

    default RegistrationResult registerExecution(RecipeExecutionIntegration integration) {
        return registerExecution(integration, RegistrationOptions.defaults());
    }

    static Optional<RSIIntegrationApi> get() {
        return RSIIntegrationServices.current();
    }
}
