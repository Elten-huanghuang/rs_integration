package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.ModType;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BatchDispatchPlannerStableBatchTest {

    @Test
    void stableBatchStepConsumesOneBudgetUnitButKeepsFullExecutionCount() {
        var step = step("compressed", 1000);

        AsyncCraftChain.VanillaBatchSlice slice =
                BatchDispatchPlanner.planVanillaBatchSlice(
                        List.of(step), 0, 0, 8, ignored -> true);

        assertEquals(1, slice.steps().size());
        assertEquals(1000, slice.steps().get(0).executions());
        assertEquals(1, slice.nextStepIndex());
    }

    @Test
    void nonBatchableStepStillUsesPerOperationBudget() {
        var step = step("dynamic", 1000);

        AsyncCraftChain.VanillaBatchSlice slice =
                BatchDispatchPlanner.planVanillaBatchSlice(
                        List.of(step), 0, 0, 8, ignored -> false);

        assertEquals(1, slice.steps().size());
        assertEquals(8, slice.steps().get(0).executions());
        assertEquals(0, slice.nextStepIndex());
        assertEquals(992, slice.remainingExecutions());
    }

    @Test
    void consecutiveStableBatchStepsEachCostOneBudgetUnit() {
        var first = step("first", 1000);
        var second = step("second", 2000);
        var third = step("third", 3000);

        AsyncCraftChain.VanillaBatchSlice slice =
                BatchDispatchPlanner.planVanillaBatchSlice(
                        List.of(first, second, third), 0, 0, 2, ignored -> true);

        assertEquals(2, slice.steps().size());
        assertEquals(1000, slice.steps().get(0).executions());
        assertEquals(2000, slice.steps().get(1).executions());
        assertEquals(2, slice.nextStepIndex());
    }

    private static CraftingResolver.ResolutionStep step(String path, int executions) {
        return new CraftingResolver.ResolutionStep(
                new ResourceLocation("test", path), ModType.GENERIC,
                new ResourceLocation("minecraft", "crafting"),
                List.of(), List.of(), false, executions);
    }
}
