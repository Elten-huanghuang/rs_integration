package com.huanghuang.rsintegration.crafting.availability;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class RecipeAvailabilityCacheTest {
    private static RecipeAvailabilityKey key(int position) {
        return new RecipeAvailabilityKey(new ResourceLocation("test", "recipe"),
                new ResourceLocation("minecraft", "overworld"), new BlockPos(position, 0, 0),
                new CompoundTag(), new CompoundTag());
    }

    @Test void expiresAndRejectsLateReplies() {
        var cache = new RecipeAvailabilityCache();
        List<Long> tickets = new ArrayList<>();
        var key = key(0);
        assertEquals(MaterialAvailability.UNKNOWN, cache.get(key, 0, (k, t) -> tickets.add(t)));
        cache.accept(key, tickets.get(0), MaterialAvailability.READY, 10);
        assertEquals(MaterialAvailability.READY, cache.get(key, 100, (k, t) -> tickets.add(t)));
        assertEquals(MaterialAvailability.UNKNOWN, cache.get(key, 2000, (k, t) -> tickets.add(t)));
        cache.accept(key, tickets.get(0), MaterialAvailability.READY, 2010);
        assertEquals(MaterialAvailability.UNKNOWN, cache.get(key, 2020, (k, t) -> tickets.add(t)));
        cache.accept(key, tickets.get(1), MaterialAvailability.MISSING, 2030);
        assertEquals(MaterialAvailability.MISSING, cache.get(key, 2040, (k, t) -> tickets.add(t)));
        assertEquals(2, tickets.size());
    }

    @Test void invalidationDoesNotReuseTicketsOrMachineResults() {
        var cache = new RecipeAvailabilityCache();
        List<Long> tickets = new ArrayList<>();
        cache.get(key(0), 0, (k, t) -> tickets.add(t));
        cache.clear();
        cache.get(key(0), 1, (k, t) -> tickets.add(t));
        assertNotEquals(tickets.get(0), tickets.get(1));
        cache.accept(key(0), tickets.get(0), MaterialAvailability.READY, 2);
        assertEquals(MaterialAvailability.UNKNOWN, cache.get(key(0), 3, (k, t) -> tickets.add(t)));
        assertEquals(MaterialAvailability.UNKNOWN, cache.get(key(1), 4, (k, t) -> tickets.add(t)));
    }

    @Test void limitsRequestsWithoutExtendingTheRateWindow() {
        var cache = new RecipeAvailabilityCache();
        List<Long> tickets = new ArrayList<>();
        for (int i = 0; i < 100; i++) cache.get(key(i), 0, (k, t) -> tickets.add(t));
        assertEquals(32, tickets.size());
        cache.get(key(99), 1000, (k, t) -> tickets.add(t));
        assertEquals(33, tickets.size());
    }

    @Test void variantKeysOwnTheirTags() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("level", 1);
        var first = new RecipeAvailabilityKey(key(0).recipeId(), key(0).dimension(), BlockPos.ZERO,
                new CompoundTag(), tag);
        tag.putInt("level", 2);
        var second = new RecipeAvailabilityKey(key(0).recipeId(), key(0).dimension(), BlockPos.ZERO,
                new CompoundTag(), tag);
        first.output().putInt("level", 3);
        assertEquals(1, first.output().getInt("level"));
        assertNotEquals(first, second);
    }
}
