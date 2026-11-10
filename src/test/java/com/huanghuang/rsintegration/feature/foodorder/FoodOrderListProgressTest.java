package com.huanghuang.rsintegration.feature.foodorder;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FoodOrderListProgressTest extends BootstrapTest {
    @Test
    void completeCatalogIsPresentBeforeAnyRecipeIsEvaluated() {
        List<Item> foods = new ArrayList<>(Collections.nCopies(6000, Items.APPLE));
        foods.add(Items.CARROT);
        FoodOrderListProgress progress = new FoodOrderListProgress(foods);

        FoodOrderSnapshot indexing = progress.snapshot(1, Component.empty(), true);
        assertEquals(6001, indexing.entries().size());
        assertEquals(6001, indexing.totalUneaten());
        assertSame(Items.CARROT, indexing.entries().get(6000).stack().getItem());
        assertTrue(indexing.indexing());
        assertTrue(indexing.partial());
        assertTrue(indexing.entries().stream().noneMatch(FoodOrderEntry::orderable));
    }

    @Test
    void exhaustedBudgetResumesUntilTailIsEvaluatedExactlyOnce() {
        List<Item> foods = new ArrayList<>(Collections.nCopies(6000, Items.APPLE));
        foods.add(Items.CARROT);
        FoodOrderListProgress progress = new FoodOrderListProgress(foods);
        AtomicInteger evaluated = new AtomicInteger();
        for (int tick = 0; tick < foods.size(); tick++) {
            progress.advance(Long.MIN_VALUE, (food, deadline) -> {
                evaluated.incrementAndGet();
                return ready(food);
            });
            assertEquals(tick + 1, evaluated.get());
        }
        FoodOrderSnapshot complete = progress.snapshot(6001, Component.empty(), false);
        assertEquals(6001, complete.entries().size());
        assertEquals(6001, complete.totalOrderable());
        assertFalse(complete.partial());
        assertTrue(complete.entries().get(6000).orderable());
        assertSame(Items.CARROT, complete.entries().get(6000).stack().getItem());
        progress.advance(Long.MAX_VALUE, (food, deadline) -> {
            throw new AssertionError("已完成的食物不应重新判定");
        });
    }

    @Test
    void earlierSnapshotDoesNotChangeWhenLaterTicksFinish() {
        FoodOrderListProgress progress = new FoodOrderListProgress(List.of(Items.APPLE, Items.CARROT));
        progress.advance(Long.MIN_VALUE, (food, deadline) -> ready(food));
        FoodOrderSnapshot partial = progress.snapshot(1, Component.empty(), false);
        progress.advance(Long.MAX_VALUE, (food, deadline) -> ready(food));

        assertTrue(partial.partial());
        assertEquals(1, partial.totalOrderable());
        assertEquals(FoodOrderKind.WAITING, partial.entries().get(1).kind());
        assertFalse(progress.snapshot(2, Component.empty(), false).partial());
    }

    @Test
    void emptyCatalogFinishesWithoutEvaluatingAnything() {
        FoodOrderListProgress progress = new FoodOrderListProgress(List.of());
        progress.advance(Long.MIN_VALUE, (food, deadline) -> {
            throw new AssertionError("空目录不应进行判定");
        });
        assertFalse(progress.snapshot(1, Component.empty(), false).partial());
    }

    private static FoodOrderEntry ready(Item food) {
        return new FoodOrderEntry(new ItemStack(food), FoodOrderKind.READY,
                new ResourceLocation("test", "food"), Component.empty());
    }
}
