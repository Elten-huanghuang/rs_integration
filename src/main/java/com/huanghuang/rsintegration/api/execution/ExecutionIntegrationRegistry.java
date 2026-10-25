package com.huanghuang.rsintegration.api.execution;

import com.huanghuang.rsintegration.api.machine.MachineContext;
import com.huanghuang.rsintegration.api.recipe.RecipeView;

import java.util.List;
import java.util.Optional;

public interface ExecutionIntegrationRegistry {
    List<RecipeExecutionIntegration> findAll(
            RecipeView recipe,
            MachineContext machine
    );

    ExecutionClaim claim(RecipeView recipe, MachineContext machine);

    default Optional<RecipeExecutionIntegration> winnerFor(
            RecipeView recipe,
            MachineContext machine
    ) {
        return Optional.ofNullable(claim(recipe, machine).winner());
    }
}
