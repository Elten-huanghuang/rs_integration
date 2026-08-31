package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.crafting.plan.PlanResponse;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

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
            PlanningSnapshot snapshot = new PlanningSnapshot(key.playerId(), 1, 1,
                    key.recipeId(), Map.of(), Map.of(), new ImmutableRecipeGraph(Map.of()),
                    "", "", false);
            cache.put(key, plan, snapshot, index);
        }

        assertEquals(2, cache.size());
        assertNull(cache.get(first, 3));
    }

    @Test
    void expiresEntriesAndRemovesPlayerEntries() {
        PlanCache cache = new PlanCache(10);
        PlanCache.Key key = new PlanCache.Key(UUID.randomUUID(), new ResourceLocation("test", "r"), Map.of(), 1, "");
        PlanResponse plan = new PlanResponse(false, "", ItemStack.EMPTY, java.util.List.of(), Map.of(), java.util.List.of(), "", null, null, 0, 0, 0, java.util.List.of(), 1);
        PlanningSnapshot snapshot = new PlanningSnapshot(key.playerId(), 1, 1, key.recipeId(), Map.of(), Map.of(), new ImmutableRecipeGraph(Map.of()), "", "", false);
        cache.put(key, plan, snapshot, 100);
        assertNotNull(cache.get(key, 105));
        assertNull(cache.get(key, 111));
        cache.put(key, plan, snapshot, 100);
        cache.removePlayer(key.playerId());
        assertNull(cache.get(key, 100));
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
}
