package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class FlatMaterialBatchTest extends BootstrapTest {
    @Test
    void twentySeedsCompleteOneHundredOneToTwoOperations() {
        for (int seed : List.of(1, 2, 20, 23)) {
            AtomicInteger stock = new AtomicInteger(seed);
            int remaining = 100;
            int dispatches = 0;
            while (remaining > 0) {
                var batch = AsyncCraftChain.reserveFlatMaterialBatch(Math.min(remaining, 32),
                        limit -> limit, count -> {
                            if (stock.get() < count) return null;
                            stock.addAndGet(-count);
                            return List.of(new ItemStack(Items.DIAMOND, count));
                        });
                assertNotNull(batch.materials());
                assertTrue(batch.executions() > 0);
                stock.addAndGet(batch.executions() * 2);
                remaining -= batch.executions();
                assertTrue(++dispatches < 15);
            }
            assertEquals(seed + 100, stock.get());
        }
    }

    @Test
    void affordableBatchIsReservedOnceWithoutRepreparing() {
        AtomicInteger calls = new AtomicInteger();
        var batch = AsyncCraftChain.reserveFlatMaterialBatch(32,
                limit -> { fail("must keep the already prepared affordable batch"); return 0; },
                count -> {
                    calls.incrementAndGet();
                    return List.of(new ItemStack(Items.DIAMOND, count));
                });
        assertEquals(32, batch.executions());
        assertEquals(1, calls.get());
    }

    @Test
    void smallerBatchReconfiguresDelegateBeforeReservation() {
        AtomicInteger configured = new AtomicInteger(32);
        List<Integer> attempts = new ArrayList<>();
        var batch = AsyncCraftChain.reserveFlatMaterialBatch(32, limit -> {
            configured.set(limit);
            return limit;
        }, count -> {
            assertEquals(configured.get(), count);
            attempts.add(count);
            return count <= 23 ? List.of(new ItemStack(Items.DIAMOND, count)) : null;
        });
        assertEquals(List.of(32, 16), attempts);
        assertEquals(16, batch.executions());
    }

    @Test
    void missingSeedStopsAfterSingleOperationProbe() {
        List<Integer> attempts = new ArrayList<>();
        var batch = AsyncCraftChain.reserveFlatMaterialBatch(32, limit -> limit, count -> {
            attempts.add(count);
            return null;
        });
        assertEquals(List.of(32, 16, 8, 4, 2, 1), attempts);
        assertEquals(0, batch.executions());
        assertNull(batch.materials());
    }

    @Test
    void respectsDelegateSmallerThanRequestedBatch() {
        var batch = AsyncCraftChain.reserveFlatMaterialBatch(32, limit -> 1,
                count -> count == 1 ? List.of(new ItemStack(Items.DIAMOND)) : null);
        assertEquals(1, batch.executions());
    }

    @Test
    void invalidDelegateBatchCannotLoopOrOverReserve() {
        for (int invalid : List.of(0, -1, 17, 32)) {
            assertThrows(IllegalStateException.class, () ->
                    AsyncCraftChain.reserveFlatMaterialBatch(32, limit -> invalid, count -> null));
        }
    }

    @Test
    void previewNeedsSeedButPhysicalBatchConsumesOneInputPerOperation() {
        var specs = List.of(new IngredientSpec(Ingredient.of(Items.DIAMOND), 1));
        assertEquals(1, SelfAmplifyingRecipePolicy.scaleTargetInputs(
                specs, new ItemStack(Items.DIAMOND, 2), 100).get(0).count());
        assertEquals(16, AsyncCraftChain.scaleGraphSpecsForExecutions(
                specs, List.of(), 16).get(0).count());
    }

    @Test
    void reservationFailureIsNotClassifiedAsMachineStartRejection() {
        assertEquals(CraftProgressSnapshot.Reason.MATERIAL_EXTRACTION_FAILED,
                AsyncCraftChain.progressReasonForDetail(
                        "Material reservation failed before machine start: recipe=botania:kjs/test"));
    }
}
