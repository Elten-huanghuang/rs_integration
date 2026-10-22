package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.crafting.graph.InputPortId;
import com.huanghuang.rsintegration.crafting.graph.NodeId;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
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
}
