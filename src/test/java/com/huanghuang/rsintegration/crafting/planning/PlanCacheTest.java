package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.crafting.graph.CraftPlanGraph;
import com.huanghuang.rsintegration.crafting.plan.PlanResponse;
import com.huanghuang.rsintegration.crafting.plan.PlanGraphView;
import com.huanghuang.rsintegration.crafting.plan.RecipeChoice;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanCacheTest extends BootstrapTest {
    @Test
    void materialLocksPartitionPreviewCacheKeys() {
        UUID player = UUID.randomUUID();
        ResourceLocation recipe = new ResourceLocation("test", "planks");
        PlanCache.Key oak = new PlanCache.Key(player, recipe, Map.of(), 1, "",
                Map.of("test:planks#tag", "minecraft:oak_planks"));
        PlanCache.Key birch = new PlanCache.Key(player, recipe, Map.of(), 1, "",
                Map.of("test:planks#tag", "minecraft:birch_planks"));

        assertNotEquals(oak, birch);
    }

    @Test
    void planningPreferencesPartitionPreviewCacheKeys() {
        UUID player = UUID.randomUUID();
        ResourceLocation recipe = new ResourceLocation("test", "crystal_matrix_ingot");
        PlanningPreferences preferences = new PlanningPreferences(
                Map.of("avaritia:crystal_matrix_ingot",
                        RecipeChoice.NoRecipe.INSTANCE),
                Map.of(), Set.of());
        PlanCache.Key automatic = new PlanCache.Key(
                player, recipe, Map.of(), 1, "", Map.of(), PlanningPreferences.empty());
        PlanCache.Key stockOnly = new PlanCache.Key(
                player, recipe, Map.of(), 1, "", Map.of(), preferences);

        assertNotEquals(automatic, stockOnly);
    }

    @Test
    void evictsOldestEntryAtConfiguredCapacity() {
        PlanCache cache = new PlanCache(1_000, 2);
        PlanResponse plan = new PlanResponse(false, "", ItemStack.EMPTY, java.util.List.of(),
                Map.of(), java.util.List.of(), "", null, null, 0, 0, 0,
                java.util.List.of(), 1);
        PlanCache.Key first = null;
        for (int index = 0; index < 3; index++) {
            PlanCache.Key key = new PlanCache.Key(UUID.randomUUID(),
                    new ResourceLocation("test", "capacity_" + index), Map.of(), 1, "");
            if (index == 0) first = key;
            PlanningSnapshot snapshot = new PlanningSnapshot(key.playerId(), 1, key.recipeRevision(),
                    key.recipeId(), Map.of(), Map.of(), new ImmutableRecipeGraph(Map.of()),
                    "", "", false);
            cache.put(key, plan, snapshot, index);
        }

        assertEquals(2, cache.size());
        assertNull(cache.get(first, 3));
    }

    @Test
    void evictsByGraphNodeBudgetBeforeEntryCapacity() {
        PlanCache cache = new PlanCache(1_000, 10, 3);
        PlanCache.Key first = key("weighted_first");
        PlanCache.Key second = key("weighted_second");

        cache.put(first, planWithGraph(2), snapshot(first), 1);
        cache.put(second, planWithGraph(2), snapshot(second), 2);

        assertEquals(1, cache.size());
        assertNull(cache.get(first, 3));
        assertNotNull(cache.get(second, 3));
    }

    @Test
    void expiresEntriesAndRemovesPlayerEntries() {
        PlanCache cache = new PlanCache(10);
        PlanCache.Key key = new PlanCache.Key(UUID.randomUUID(), new ResourceLocation("test", "r"), Map.of(), 1, "");
        PlanResponse plan = new PlanResponse(false, "", ItemStack.EMPTY, java.util.List.of(), Map.of(), java.util.List.of(), "", null, null, 0, 0, 0, java.util.List.of(), 1);
        PlanningSnapshot snapshot = new PlanningSnapshot(key.playerId(), 1, key.recipeRevision(),
                key.recipeId(), Map.of(), Map.of(), new ImmutableRecipeGraph(Map.of()),
                "", "", false);
        cache.put(key, plan, snapshot, 100);
        assertNotNull(cache.get(key, 105));
        assertNull(cache.get(key, 111));
        cache.put(key, plan, snapshot, 100);
        cache.removePlayer(key.playerId());
        assertNull(cache.get(key, 100));
        assertNull(cache.takeForExecution(key));
    }

    @Test
    void displayedPlanRemainsExecutableAfterPreviewDeduplicationExpires() {
        PlanCache cache = new PlanCache(10);
        PlanCache.Key key = key("visible_plan");
        PlanResponse plan = plan();
        PlanningSnapshot snapshot = snapshot(key);
        CraftPlanGraph typedGraph = new CraftPlanGraph(
                CraftPlanGraph.CURRENT_VERSION, java.util.List.of(), java.util.List.of(),
                java.util.List.of(), java.util.List.of(), java.util.List.of());

        cache.put(key, plan, snapshot, null, typedGraph, 100);

        assertNull(cache.get(key, 111));
        PlanCache.Entry leased = cache.takeForExecution(key);
        assertNotNull(leased);
        assertSame(typedGraph, leased.resolvedGraph());
        assertNull(cache.takeForExecution(key));
    }

    @Test
    void previewLookupDoesNotRenewAConsumedExecutionLease() {
        PlanCache cache = new PlanCache(10);
        PlanCache.Key key = key("consumed_lease");
        cache.put(key, plan(), snapshot(key), 100);

        assertNotNull(cache.takeForExecution(key));
        // Preview data remains available for display deduplication, but the
        // execution lease must stay consumed.
        assertNotNull(cache.get(key, 100));
        assertNull(cache.takeForExecution(key));
    }

    @Test
    void executionLeaseMustBeValidatedBeforeItsExactEntryIsConsumed() {
        PlanCache cache = new PlanCache(10);
        PlanCache.Key key = key("validated_lease");
        cache.put(key, plan(), snapshot(key), 100);

        PlanCache.Entry previewed = cache.peekForExecution(key);

        assertNotNull(previewed);
        assertNotNull(cache.peekForExecution(key));
        assertTrue(cache.consumeForExecution(key, previewed));
        assertNull(cache.peekForExecution(key));
        assertNull(cache.takeForExecution(key));
    }

    @Test
    void unrelatedEntryCannotConsumeAnotherPlayersExecutionLease() {
        PlanCache cache = new PlanCache(10);
        PlanCache.Key key = key("lease_identity");
        cache.put(key, plan(), snapshot(key), 100);
        PlanCache.Entry unrelated = new PlanCache.Entry(
                plan(), snapshot(key), null, null, 99);

        assertNotNull(cache.peekForExecution(key));
        assertFalse(cache.consumeForExecution(key, unrelated));
        assertNotNull(cache.peekForExecution(key));
        assertTrue(cache.consumeForExecution(key, cache.peekForExecution(key)));
        assertNull(cache.peekForExecution(key));
    }

    @Test
    void latestExecutionLeaseCanBeConsumedAfterSemanticValidation() {
        PlanCache cache = new PlanCache(10);
        PlanCache.Key key = key("semantic_lease");
        cache.put(key, plan(), snapshot(key), 100);

        PlanCache.Entry previewed = cache.peekLatestForExecution(key.playerId());

        assertNotNull(previewed);
        assertTrue(cache.consumeLatestForExecution(key.playerId(), previewed));
        assertNull(cache.peekLatestForExecution(key.playerId()));
    }

    @Test
    void aNewPreviewResponseGrantsAnotherSingleExecutionLease() {
        PlanCache cache = new PlanCache(10);
        PlanCache.Key key = key("reissued_lease");
        cache.put(key, plan(), snapshot(key), 100);

        assertNotNull(cache.takeForExecution(key));
        assertTrue(cache.grantExecutionLease(key, 100));
        assertNotNull(cache.takeForExecution(key));
        assertNull(cache.takeForExecution(key));
    }

    @Test
    void newerDisplayedPlanReplacesOnlyThatPlayersExecutionLease() {
        PlanCache cache = new PlanCache(10);
        UUID player = UUID.randomUUID();
        PlanCache.Key oldKey = new PlanCache.Key(player,
                new ResourceLocation("test", "old"), Map.of(), 1, "");
        PlanCache.Key currentKey = new PlanCache.Key(player,
                new ResourceLocation("test", "current"), Map.of(), 1, "");

        cache.put(oldKey, plan(), snapshot(oldKey), 100);
        cache.put(currentKey, plan(), snapshot(currentKey), 101);

        assertNull(cache.takeForExecution(oldKey));
        assertNotNull(cache.takeForExecution(currentKey));
    }

    @Test
    void retainsOptionalPurePlanForValidatedExecution() {
        PlanCache cache = new PlanCache(100);
        PlanCache.Key key = new PlanCache.Key(UUID.randomUUID(),
                new ResourceLocation("test", "pure"), Map.of(), 1, "");
        PlanResponse plan = new PlanResponse(true, "", ItemStack.EMPTY, java.util.List.of(),
                Map.of(), java.util.List.of(), key.recipeId().toString());
        PlanningSnapshot snapshot = new PlanningSnapshot(key.playerId(), 1, 1,
                key.recipeId(), Map.of(), Map.of(), new ImmutableRecipeGraph(Map.of()),
                "", "", false);
        PureRecipePlanner.Result pure = new PureRecipePlanner.Result(
                true, java.util.List.of(), java.util.List.of(), Map.of());

        cache.put(key, plan, snapshot, pure, 1);

        assertSame(pure, cache.get(key, 2).purePlan());
    }

    @Test
    void executionLeaseIsBoundToRecipeRevision() {
        PlanCache cache = new PlanCache(100);
        PlanCache.Key current = key("revisioned");
        PlanCache.Key stale = new PlanCache.Key(
                current.playerId(), current.recipeId(), current.forcedRecipes(),
                current.repeatCount(), current.clickedOutputToken(), current.materialLocks(),
                current.preferences(), current.recipeRevision() + 1L);

        cache.put(current, plan(), snapshot(current), 1);

        assertNull(cache.takeForExecution(stale));
        assertNotNull(cache.takeForExecution(current));
    }

    private static PlanCache.Key key(String path) {
        return new PlanCache.Key(UUID.randomUUID(),
                new ResourceLocation("test", path), Map.of(), 1, "");
    }

    private static PlanResponse plan() {
        return new PlanResponse(true, "", ItemStack.EMPTY, java.util.List.of(),
                Map.of(), java.util.List.of(), "test:plan");
    }

    private static PlanResponse planWithGraph(int nodeCount) {
        List<PlanGraphView.NodeView> nodes = new ArrayList<>(nodeCount);
        List<Integer> order = new ArrayList<>(nodeCount);
        for (int nodeId = 0; nodeId < nodeCount; nodeId++) {
            nodes.add(new PlanGraphView.NodeView(nodeId,
                    new ResourceLocation("test", "node_" + nodeId),
                    "generic", 1, ItemStack.EMPTY, List.of(), List.of()));
            order.add(nodeId);
        }
        PlanGraphView graph = new PlanGraphView(CraftPlanGraph.CURRENT_VERSION, nodes,
                List.of(), List.of(), List.of(), order);
        return new PlanResponse(true, "", ItemStack.EMPTY, List.of(),
                Map.of(), List.of(), "test:weighted",
                null, null, 0, 0, 0, List.of(), 1,
                null, null, null, 0L, false, false, false, null,
                Set.of(), Map.of(), null, graph, false,
                List.of(), Map.of());
    }

    private static PlanningSnapshot snapshot(PlanCache.Key key) {
        return new PlanningSnapshot(key.playerId(), 1, key.recipeRevision(), key.recipeId(),
                Map.of(), Map.of(), new ImmutableRecipeGraph(Map.of()), "", "", false);
    }
}
