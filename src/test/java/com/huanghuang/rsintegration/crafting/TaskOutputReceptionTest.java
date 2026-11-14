package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.api.IntegrationResourceKey;
import com.huanghuang.rsintegration.crafting.TaskOutputReception.Demand;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidTestFixtures;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

class TaskOutputReceptionTest extends BootstrapTest {
    private final IntegrationResourceKey iron = IntegrationResourceKey.item(new ItemStack(Items.IRON_INGOT));

    private TaskOutputReception.Batch batch(TaskOutputReception.Session session, long count) {
        var batch = session.prepare(List.of(new Demand("out", iron, count, count)));
        batch.activate();
        return batch;
    }

    @Test
    void incrementalCollectionPublishesActualResourcesOnlyOnce() {
        try (var session = TaskOutputReception.open()) {
            var batch = batch(session, 8);
            batch.collect(iron, 3);
            assertFalse(batch.complete());
            assertEquals(3, session.drainStacks().get(0).getCount());
            batch.collect(iron, 5);
            assertTrue(batch.complete());
            assertEquals(8, batch.received("out"));
            assertEquals(8, session.received(iron));
            assertEquals(5, session.drainStacks().get(0).getCount());
            assertTrue(session.drainStacks().isEmpty());
        }
    }

    @Test
    void equalPartialCollectionsRemainIndependentAndClosingRejectsMoreOutput() {
        try (var session = TaskOutputReception.open()) {
            var batch = batch(session, 8);
            assertEquals(2, batch.collect(iron, 2));
            assertEquals(2, batch.collect(iron, 2));
            assertEquals(4, session.drain().get(0).amount());
            batch.close();
            assertTrue(session.drain().isEmpty());
            assertThrows(IllegalStateException.class, () -> batch.collect(iron, 2));
        }
    }

    @Test
    void separateOperationsNeverClaimEachOthersResources() {
        try (var first = TaskOutputReception.open(); var second = TaskOutputReception.open()) {
            var a = batch(first, 5);
            var b = batch(second, 5);
            b.collect(iron, 3);
            assertEquals(0, a.received("out"));
            assertTrue(first.drain().isEmpty());
            assertEquals(3, second.drain().get(0).amount());
        }
    }

    @Test
    void physicalBatchSwitchRequiresClosingAndDoesNotReuseReceivedCount() {
        try (var session = TaskOutputReception.open()) {
            var first = batch(session, 2);
            assertThrows(IllegalStateException.class, () -> batch(session, 2));
            first.collect(iron, 2);
            first.close();
            var second = batch(session, 2);
            assertNotSame(first, second);
            assertFalse(second.complete());
            assertThrows(IllegalStateException.class, () -> first.collect(iron, 2));
            second.collect(iron, 2);
            assertEquals(4, session.drain().get(0).amount());
        }
    }

    @Test
    void failedStartKeepsAlreadyCollectedResourcesWithoutCompleting() {
        var session = TaskOutputReception.open();
        var batch = session.prepare(List.of(new Demand("out", iron, 8, 8)));
        batch.collect(iron, 3);
        assertFalse(batch.complete());
        session.close();
        assertFalse(batch.complete());
        assertThrows(IllegalStateException.class, batch::activate);
        assertThrows(IllegalStateException.class, () -> session.prepare(List.of(new Demand("out", iron, 1, 1))));
        assertThrows(IllegalStateException.class, () -> batch.collect(iron, 5));
        assertEquals(3, session.drain().get(0).amount());
        assertTrue(session.drain().isEmpty());
    }

    @Test
    void optionalPortCannotStealGuaranteedPortAndIsNotRequiredForCompletion() {
        try (var session = TaskOutputReception.open()) {
            var batch = session.prepare(List.of(new Demand("chance", iron, 0, 10), new Demand("main", iron, 3, 3)));
            batch.activate();
            batch.collect(iron, 3);
            assertEquals(3, batch.received("main"));
            assertEquals(0, batch.received("chance"));
            assertTrue(batch.complete());
            assertEquals(10, batch.remaining("chance"));
        }
    }

    @Test
    void exactIdentityIncludesNbtButUnmatchedPhysicalResourcesAreStillKept() {
        try (var session = TaskOutputReception.open()) {
            var batch = batch(session, 8);
            ItemStack tagged = new ItemStack(Items.IRON_INGOT);
            tagged.getOrCreateTag().putString("grade", "pure");
            assertEquals(0, batch.collect(IntegrationResourceKey.item(tagged), 1));
            assertFalse(batch.complete());
            ItemStack actual = session.drainStacks().get(0);
            assertTrue(ItemStack.isSameItemSameTags(tagged, actual));
            tagged.setTag(null);
            tagged.getOrCreateTag();
            assertEquals(8, batch.collect(IntegrationResourceKey.item(tagged), 8));
            assertTrue(batch.complete());
        }
    }

    @Test
    void collectionKeepsSurplusBeyondDeclaredProgress() {
        try (var session = TaskOutputReception.open()) {
            var batch = batch(session, 8);
            assertEquals(8, batch.collect(iron, 10));
            assertEquals(0, batch.collect(iron, 2));
            assertEquals(8, batch.received("out"));
            assertEquals(12, session.drain().get(0).amount());
        }
    }

    @Test
    void fluidCollectionPreservesIdentityAndActualAmount() {
        try (var session = TaskOutputReception.open()) {
            FluidStack fluid = new FluidStack(Fluids.WATER, 400);
            fluid.getOrCreateTag().putString("grade", "pure");
            var key = IntegrationResourceKey.fluid(fluid);
            var batch = session.prepare(List.of(new Demand("water", key, 1000, 1000)));
            batch.activate();
            batch.collect(key, 400);
            // 单测没有模组物品注册，只替换凭据工厂，真实 NBT 转换沿用现有实现。
            try (var tokens = mockStatic(InkFluidSupport.class, CALLS_REAL_METHODS)) {
                ItemStack expected = InkFluidSupport.token(InkFluidTestFixtures.tokenItem(), fluid);
                tokens.when(() -> InkFluidSupport.tokenFor(key, 400)).thenReturn(expected);
                ItemStack token = session.drainStacks().get(0);
                assertEquals(key, TaskOutputReception.resource(token));
                assertEquals(400, token.getCount());
            }
            assertFalse(batch.complete());
            assertTrue(session.drainStacks().isEmpty());
        }
    }

    @Test
    void failedFluidConversionKeepsEveryHeldResourceForRecovery() {
        try (var session = TaskOutputReception.open();
             var tokens = mockStatic(InkFluidSupport.class, CALLS_REAL_METHODS)) {
            FluidStack fluid = new FluidStack(Fluids.WATER, 400);
            var water = IntegrationResourceKey.fluid(fluid);
            var batch = session.prepare(List.of(new Demand("item", iron, 1, 1),
                    new Demand("fluid", water, 400, 400)));
            batch.activate();
            batch.collect(iron, 1);
            batch.collect(water, 400);
            tokens.when(() -> InkFluidSupport.tokenFor(water, 400)).thenReturn(ItemStack.EMPTY);
            assertThrows(IllegalStateException.class, session::drainStacks);
            session.close();
            assertEquals(List.of(new TaskOutputReception.Received(iron, 1),
                    new TaskOutputReception.Received(water, 400)), session.drain());
            assertTrue(session.drain().isEmpty());
        }
    }

    @Test
    void foreignThreadCannotCollectDrainOrClose() throws InterruptedException {
        try (var session = TaskOutputReception.open()) {
            var batch = batch(session, 8);
            batch.collect(iron, 3);
            AtomicBoolean rejected = new AtomicBoolean();
            Thread other = new Thread(() -> {
                try { batch.collect(iron, 5); }
                catch (IllegalStateException expected) {
                    try { session.drain(); }
                    catch (IllegalStateException expectedDrain) {
                        try { session.close(); }
                        catch (IllegalStateException expectedClose) { rejected.set(true); }
                    }
                }
            });
            other.start();
            other.join();
            assertTrue(rejected.get());
            assertFalse(session.closed());
            assertEquals(3, session.received(iron));
            assertEquals(3, session.drain().get(0).amount());
        }
    }

    @Test
    void overflowDoesNotPartlyMutateHeldResourcesOrBatchProgress() {
        try (var session = TaskOutputReception.open()) {
            var first = batch(session, Long.MAX_VALUE);
            first.collect(iron, Long.MAX_VALUE - 1);
            assertThrows(ArithmeticException.class, () -> first.collect(iron, 2));
            assertEquals(Long.MAX_VALUE - 1, first.received("out"));
            first.collect(iron, 1);
            assertEquals(Long.MAX_VALUE, session.drain().get(0).amount());
            first.close();
            var second = batch(session, 3);
            assertThrows(ArithmeticException.class, () -> second.collect(iron, 1));
            assertEquals(0, second.received("out"));
            assertTrue(session.drain().isEmpty());
        }
    }

    @Test
    void invalidDemandDoesNotReplaceThePreviousBatch() {
        try (var session = TaskOutputReception.open()) {
            var first = batch(session, 2);
            first.close();
            assertThrows(IllegalArgumentException.class, () -> session.prepare(List.of(
                    new Demand("same", iron, 1, 1), new Demand("same", iron, 1, 1))));
            assertThrows(ArithmeticException.class, () -> session.prepare(List.of(
                    new Demand("a", iron, 0, Long.MAX_VALUE), new Demand("b", iron, 0, 1))));
            assertSame(first, session.current());
        }
    }

    @Test
    void randomizedCollectionAndPublicationConserveEveryPhysicalUnit() {
        try (var session = TaskOutputReception.open()) {
            var batch = batch(session, 999);
            Random random = new Random(8732);
            long collected = 0;
            long published = 0;
            for (int i = 0; i < 1000; i++) {
                int amount = random.nextInt(20) + 1;
                long accepted = batch.collect(iron, amount);
                assertEquals(Math.min(amount, Math.max(0, 999 - collected)), accepted);
                collected += amount;
                if (random.nextBoolean()) published += session.drain().stream()
                        .mapToLong(TaskOutputReception.Received::amount).sum();
                assertEquals(collected, session.received(iron));
            }
            published += session.drain().stream().mapToLong(TaskOutputReception.Received::amount).sum();
            assertEquals(collected, published);
            assertEquals(999, batch.received("out"));
            assertTrue(batch.complete());
        }
    }
}
