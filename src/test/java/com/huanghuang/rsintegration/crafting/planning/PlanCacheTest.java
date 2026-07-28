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

class PlanCacheTest extends BootstrapTest {
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
}
