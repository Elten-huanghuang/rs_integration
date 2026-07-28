package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.crafting.plan.PlanResponseDraft;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncPlanResponseServiceTest extends BootstrapTest {
    @Test
    void finalizesOnWorkerAndCommitsOnlyThroughServerExecutor() throws Exception {
        try (AsyncPlanningCoordinator coordinator = new AsyncPlanningCoordinator(1)) {
            LinkedBlockingQueue<Runnable> serverTasks = new LinkedBlockingQueue<>();
            Executor serverExecutor = serverTasks::add;
            AtomicBoolean committed = new AtomicBoolean();
            AtomicReference<Thread> revalidationThread = new AtomicReference<>();
            Thread testThread = Thread.currentThread();

            new AsyncPlanResponseService(coordinator).submit(snapshot(), draft(), serverExecutor,
                    ignored -> {
                        revalidationThread.set(Thread.currentThread());
                        return true;
                    }, response -> committed.set(true), failure -> committed.set(true));

            Runnable callback = serverTasks.poll(2, TimeUnit.SECONDS);
            assertTrue(callback != null);
            assertFalse(committed.get());
            callback.run();
            assertTrue(committed.get());
            assertSame(testThread, revalidationThread.get());
        }
    }

    @Test
    void staleDraftRollsBackInsteadOfCommitting() throws Exception {
        try (AsyncPlanningCoordinator coordinator = new AsyncPlanningCoordinator(1)) {
            CountDownLatch completed = new CountDownLatch(1);
            AtomicBoolean committed = new AtomicBoolean();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            new AsyncPlanResponseService(coordinator).submit(snapshot(), draft(), Runnable::run,
                    ignored -> false, response -> committed.set(true), thrown -> {
                        failure.set(thrown);
                        completed.countDown();
                    });

            assertTrue(completed.await(2, TimeUnit.SECONDS));
            assertFalse(committed.get());
            assertTrue(failure.get() instanceof AsyncPlanningCoordinator.StalePlanningResultException);
        }
    }

    private static PlanResponseDraft draft() {
        return new PlanResponseDraft(true, "stone", new ItemStack(Items.STONE),
                List.of(), Map.of(), List.of(), "test:recipe", null, null,
                0, 0, 0, List.of(), 1, null, null, null, 0L,
                false, false, false, null, Set.of(), Map.of(), null, null);
    }

    private static PlanningSnapshot snapshot() {
        return new PlanningSnapshot(UUID.randomUUID(), 1L, 1L,
                new ResourceLocation("test", "recipe"), Map.of(), Map.of(),
                new ImmutableRecipeGraph(Map.of()), "network", "binding", false);
    }
}
