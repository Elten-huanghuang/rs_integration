package com.huanghuang.rsintegration.api.recipe;

public record RecipeFlags(
        boolean recursivePlanning,
        boolean targetProduction,
        boolean deterministicOutput,
        boolean runtimeDependentIdentity,
        boolean requiresFuel,
        boolean supportsBatching,
        boolean orderedInputs
) {}
