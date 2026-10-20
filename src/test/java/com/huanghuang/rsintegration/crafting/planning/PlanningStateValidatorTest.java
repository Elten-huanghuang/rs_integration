package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.plan.PlanGraphView;
import com.huanghuang.rsintegration.crafting.plan.PlanResponse;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanningStateValidatorTest extends BootstrapTest {
    @Test
    void cachedGraphIgnoresUnrelatedInventoryChurn() {
        PlanningSnapshot cached = snapshot(Map.of(
                new StackKey(Items.COAL, null), 1L,
                new StackKey(Items.DIAMOND, null), 64L), "network:old");
        PlanningSnapshot current = snapshot(Map.of(
                new StackKey(Items.COAL, null), 1L,
                new StackKey(Items.EMERALD, null), 12L), "network:new");

        assertTrue(PlanningStateValidator.sameRelevantState(cached, current,
                planRequiring(new ItemStack(Items.COAL), 1)));
    }

    @Test
    void cachedGraphRejectsMissingPlannedSupply() {
        PlanningSnapshot cached = snapshot(Map.of(
                new StackKey(Items.COAL, null), 1L), "network:old");
        PlanningSnapshot current = snapshot(Map.of(
                new StackKey(Items.DIAMOND, null), 64L), "network:new");

        assertFalse(PlanningStateValidator.sameRelevantState(cached, current,
                planRequiring(new ItemStack(Items.COAL), 1)));
    }

    @Test
    void cachedGraphRejectsDifferentNetworkIdentity() {
        PlanningSnapshot cached = snapshot(Map.of(
                new StackKey(Items.COAL, null), 1L), "first:inventory");
        PlanningSnapshot current = snapshot(Map.of(
                new StackKey(Items.COAL, null), 1L), "second:inventory");

        assertFalse(PlanningStateValidator.sameRelevantState(cached, current,
                planRequiring(new ItemStack(Items.COAL), 1)));
    }

    @Test
    void completeStateRejectsChangedInventoryQuantity() {
        PlanningSnapshot cached = snapshot(Map.of(
                new StackKey(Items.COAL, null), 8L), "network:inventory");
        PlanningSnapshot current = snapshot(Map.of(
                new StackKey(Items.COAL, null), 3L), "network:inventory");

        assertFalse(PlanningStateValidator.sameState(cached, current));
    }

    @Test
    void failedPreviewStateIgnoresOnlyUnrelatedItemTypes() {
        Map<StackKey, Long> previous = Map.of(
                new StackKey(Items.COAL, null), 2L,
                new StackKey(Items.DIAMOND, null), 64L);
        Map<StackKey, Long> unrelatedChange = Map.of(
                new StackKey(Items.COAL, null), 2L,
                new StackKey(Items.EMERALD, null), 12L);
        Map<StackKey, Long> relevantChange = Map.of(
                new StackKey(Items.COAL, null), 1L,
                new StackKey(Items.DIAMOND, null), 64L);

        assertTrue(PlanningStateValidator.sameRelevantInventory(
                previous, unrelatedChange, Set.of(Items.COAL)));
        assertFalse(PlanningStateValidator.sameRelevantInventory(
                previous, relevantChange, Set.of(Items.COAL)));
    }

    private static PlanningSnapshot snapshot(Map<StackKey, Long> available,
                                             String networkFingerprint) {
        return new PlanningSnapshot(UUID.fromString("00000000-0000-0000-0000-000000000001"),
                1L, 7L, new ResourceLocation("test", "target"), available, Map.of(),
                new ImmutableRecipeGraph(Map.of()), networkFingerprint, "binding", false);
    }

    private static PlanResponse planRequiring(ItemStack stack, int quantity) {
        PlanGraphView graph = new PlanGraphView(1, List.of(), List.of(
                new PlanGraphView.EdgeView(0, 0,
                        new PlanGraphView.SourceView(true, -1, -1), stack, quantity)),
                List.of(), List.of(), List.of());
        return new PlanResponse(true, "", ItemStack.EMPTY, List.of(), Map.of(), List.of(),
                "test:target", null, null, 0, 0, 0, List.of(), 1,
                null, null, null, 0L, false, false, false, null, Set.of(), Map.of(),
                null, graph);
    }
}
