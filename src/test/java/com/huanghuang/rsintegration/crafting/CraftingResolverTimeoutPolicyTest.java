package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.crafting.graph.InputPortId;
import com.huanghuang.rsintegration.crafting.graph.NodeId;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CraftingResolverTimeoutPolicyTest extends BootstrapTest {

    @Test
    void onlyStrictPlanningAbortsOnTimeout() {
        assertTrue(CraftingResolver.shouldAbortOnTimeout(false));
        assertFalse(CraftingResolver.shouldAbortOnTimeout(true));
    }

    @Test
    void timedOutBestEffortResolutionRecordsTheBlockedInputInsteadOfThrowing() {
        ResolutionContext context = new ResolutionContext(
                null, Map.of(), Map.of(), null, null, null, null,
                true, new ArrayList<>(), System.nanoTime() - 1L, false);
        InputPortId consumer = new InputPortId(new NodeId(0), 0);

        assertTrue(context.timedOut());
        assertTrue(CraftingResolver.recordBestEffortUnresolved(
                context, consumer, Ingredient.of(Items.STICK), 4, "timeout-test"));
        assertEquals(1, context.graphUnresolved.size());
        assertEquals(4, context.graphUnresolved.get(0).quantity());
    }

    @Test
    void strictTimedOutResolutionStillThrows() {
        ResolutionContext context = new ResolutionContext(
                null, Map.of(), Map.of(), null, null, null, null,
                false, new ArrayList<>(), System.nanoTime() - 1L, true);

        assertThrows(CraftingPlanningTimeoutException.class, context::timedOut);
    }

    @Test
    void strictFailureKeepsNestedMissingInsteadOfReportingItsParent() {
        ArrayList<String> missing = new ArrayList<>();
        ResolutionContext context = new ResolutionContext(
                null, Map.of(), Map.of(), null, null, null, null,
                false, missing, Long.MAX_VALUE, false);
        context.addMissing("item.test.nested_missing");

        CraftingResolver.recordFailureMissing(
                context, Ingredient.of(Items.STICK), 0);

        assertEquals(List.of("item.test.nested_missing"), missing);
    }

    @Test
    void failedAlternativeMissingIsRemovedAfterSuccessfulResolution() {
        ArrayList<String> missing = new ArrayList<>();
        ResolutionContext context = new ResolutionContext(
                null, Map.of(), Map.of(), null, null, null, null,
                false, missing, Long.MAX_VALUE, false);

        CraftingResolver.recordFailureMissing(
                context, Ingredient.of(Items.STICK), context.missingCount());
        assertEquals(1, missing.size());

        context.truncateMissing(0);
        assertTrue(missing.isEmpty());
    }
}
