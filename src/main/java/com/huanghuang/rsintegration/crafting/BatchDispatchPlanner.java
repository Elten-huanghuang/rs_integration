package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure batch-window calculations shared by flat and graph execution.
 *
 * <p>This class deliberately does not inspect machines, storage, or ledgers.
 * Those side effects stay in {@link AsyncCraftChain}; keeping the arithmetic
 * here makes later input-buffer planning independently testable.</p>
 */
final class BatchDispatchPlanner {
    private BatchDispatchPlanner() {}

    static int flatDispatchWindow(int remainingOperations, int dispatchLimit) {
        return Math.min(Math.max(0, remainingOperations), Math.max(1, dispatchLimit));
    }

    static int remainingAfterFlatBatch(int remainingOperations, int completedOperations) {
        if (completedOperations <= 0 || completedOperations > remainingOperations) {
            throw new IllegalArgumentException(
                    "Completed batch exceeds remaining operations or made no progress");
        }
        return remainingOperations - completedOperations;
    }

    static int completedFlatWindowOperations(int totalOperations, int remainingOperations,
                                             int completedInWindow) {
        long completed = (long) completedFlatOperations(totalOperations, remainingOperations)
                + Math.max(0, completedInWindow);
        return (int) Math.min(Math.max(1, totalOperations), completed);
    }

    static AsyncCraftChain.VanillaBatchSlice planVanillaBatchSlice(
            List<CraftingResolver.ResolutionStep> sourceSteps,
            int startIdx, int currentRemaining, int operationBudget) {
        int budget = Math.max(1, operationBudget);
        int i = Math.max(0, startIdx);
        int remaining = Math.max(0, currentRemaining);
        List<CraftingResolver.ResolutionStep> sliceSteps = new ArrayList<>();
        while (i < sourceSteps.size() && budget > 0) {
            CraftingResolver.ResolutionStep step = sourceSteps.get(i);
            if (step.modType() != ModType.GENERIC
                    || step.recipeId().equals(CraftingResolver.TAINT_EARTH_HEART_STEP)) {
                break;
            }
            int stepExecutions = i == startIdx && remaining > 0
                    ? remaining : step.executions();
            int sliceExecutions = Math.min(stepExecutions, budget);
            sliceSteps.add(copyWithExecutions(step, sliceExecutions));
            budget -= sliceExecutions;
            stepExecutions -= sliceExecutions;
            if (stepExecutions > 0) {
                return new AsyncCraftChain.VanillaBatchSlice(
                        List.copyOf(sliceSteps), i, stepExecutions);
            }
            remaining = 0;
            i++;
        }
        return new AsyncCraftChain.VanillaBatchSlice(List.copyOf(sliceSteps), i, 0);
    }

    static boolean requiresFlatExecutionForOversizedNode(
            List<CraftingResolver.ResolutionStep> steps, int vanillaOperationLimit,
            int dispatchOperationLimit) {
        int vanillaLimit = Math.max(1, vanillaOperationLimit);
        int machineLimit = Math.max(1, dispatchOperationLimit);
        return steps.stream().anyMatch(step -> step.executions()
                > (step.modType() == ModType.GENERIC ? vanillaLimit : machineLimit));
    }

    static int configuredAtomicVanillaGraphLimit() {
        try {
            return Math.max(1, Math.min(
                    RSIntegrationConfig.CRAFTING_VANILLA_OPERATIONS_PER_TICK.get(),
                    RSIntegrationConfig.CRAFTING_GLOBAL_VANILLA_OPERATIONS_PER_TICK.get()));
        } catch (Exception ignored) {
            return Math.min(RSIntegrationConfig.DEFAULT_CRAFTING_VANILLA_OPERATIONS_PER_TICK,
                    RSIntegrationConfig.DEFAULT_CRAFTING_GLOBAL_VANILLA_OPERATIONS_PER_TICK);
        }
    }

    static int configuredOperationsPerDispatch() {
        try {
            return Math.max(1, RSIntegrationConfig.CRAFTING_OPERATIONS_PER_DISPATCH.get());
        } catch (Exception ignored) {
            return RSIntegrationConfig.DEFAULT_CRAFTING_OPERATIONS_PER_DISPATCH;
        }
    }

    private static int completedFlatOperations(int totalOperations, int remainingOperations) {
        return Math.max(0, totalOperations - Math.max(0, remainingOperations));
    }

    private static CraftingResolver.ResolutionStep copyWithExecutions(
            CraftingResolver.ResolutionStep step, int executions) {
        return new CraftingResolver.ResolutionStep(
                step.recipeId(), step.modType(), step.recipeTypeId(),
                step.alternativeIds(), step.alternativeModTypes(), step.inferMode(),
                executions, step.syntheticInput(), step.syntheticOutput());
    }
}
