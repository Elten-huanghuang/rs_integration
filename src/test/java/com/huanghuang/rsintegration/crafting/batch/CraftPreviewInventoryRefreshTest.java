package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.crafting.plan.IntegrationDiagnostic;
import com.huanghuang.rsintegration.crafting.plan.IntegrationDiagnosticCode;
import com.huanghuang.rsintegration.crafting.plan.PlanGraphView;
import com.huanghuang.rsintegration.crafting.plan.PlanOutcome;
import com.huanghuang.rsintegration.crafting.plan.PlanResponse;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph;
import com.huanghuang.rsintegration.crafting.planning.PlanningSnapshot;
import com.huanghuang.rsintegration.storage.StorageBackendId;
import com.huanghuang.rsintegration.storage.StorageReference;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CraftPreviewInventoryRefreshTest extends BootstrapTest {
    @Test
    void liveGraphPublishesWithoutInventoryRevalidationOrRestart() {
        CraftPlanResultDispatcher.Sink destination = mock(CraftPlanResultDispatcher.Sink.class);
        PlanResponse graphPlan = new PlanResponse(true, "target", new ItemStack(Items.DIAMOND), List.of(),
                Map.of(), List.of(), "test:target", "generic", "minecraft:overworld", 0, 0, 0,
                List.of(), 1, null, null, null, 0L, false, false, false, null,
                Set.of(), Map.of(), null, new PlanGraphView(1, List.of(), List.of(), List.of(), List.of(), List.of()));
        CraftPreviewInventoryRefresh refresh = new CraftPreviewInventoryRefresh(destination, 10, () -> true,
                (snapshot, plan) -> { fail("增量图不应再次读取库存"); return true; },
                snapshot -> fail("不应使完整计划缓存失效"),
                task -> fail("不应排队重算"), (plan, sink) -> fail("不应重新搜索"));
        PlanningSnapshot snapshot = snapshot();
        refresh.success(graphPlan, snapshot);
        verify(destination).success(graphPlan, snapshot);
    }

    @Test
    void aSingleRefreshPublishesOnlyTheNewStableResult() {
        CraftPlanResultDispatcher.Sink destination = mock(CraftPlanResultDispatcher.Sink.class);
        Queue<Runnable> queue = new ArrayDeque<>();
        AtomicBoolean changed = new AtomicBoolean(true);
        AtomicInteger invalidations = new AtomicInteger();
        PlanningSnapshot original = snapshot();
        PlanningSnapshot fresh = snapshot();
        PlanResponse previous = plan(false);
        PlanResponse updated = plan(true);
        CraftPreviewInventoryRefresh refresh = new CraftPreviewInventoryRefresh(destination, 1, () -> true,
                (snapshot, plan) -> changed.get(), snapshot -> {
                    assertSame(original, snapshot);
                    invalidations.incrementAndGet();
                }, queue::add, (plan, sink) -> {
                    assertSame(previous, plan);
                    changed.set(false);
                    sink.success(updated, fresh);
                });
        refresh.success(previous, original);
        refresh.success(previous, original); // 同一轮重复回调不能额外排队。
        assertEquals(1, queue.size());
        assertEquals(0, invalidations.get());
        verifyNoInteractions(destination);
        queue.remove().run();
        assertEquals(1, invalidations.get());
        verify(destination).success(updated, fresh);
        refresh.success(previous, original);
        refresh.cancelled(Component.literal("late"));
        verifyNoMoreInteractions(destination);
    }

    @Test
    void continuousChurnHonorsConfiguredBudgetWithoutCancellation() {
        assertContinuousChurnBudget(0);
        assertContinuousChurnBudget(1);
        assertContinuousChurnBudget(3);
    }

    private static void assertContinuousChurnBudget(int budget) {
        CraftPlanResultDispatcher.Sink destination = mock(CraftPlanResultDispatcher.Sink.class);
        Queue<Runnable> queue = new ArrayDeque<>();
        AtomicInteger starts = new AtomicInteger();
        AtomicInteger invalidations = new AtomicInteger();
        PlanResponse plan = plan(false);
        PlanningSnapshot snapshot = snapshot();
        CraftPreviewInventoryRefresh refresh = new CraftPreviewInventoryRefresh(destination, budget, () -> true,
                (ignoredSnapshot, ignoredPlan) -> true, ignored -> invalidations.incrementAndGet(),
                queue::add, (previous, sink) -> {
                    starts.incrementAndGet();
                    sink.success(plan, snapshot);
                });
        refresh.success(plan, snapshot);
        for (int attempt = 0; attempt < budget; attempt++) {
            assertEquals(1, queue.size());
            verifyNoInteractions(destination);
            queue.remove().run();
        }
        assertTrue(queue.isEmpty());
        assertEquals(budget, starts.get());
        assertEquals(budget, invalidations.get());
        verify(destination).success(argThat(result -> result.outcome() == PlanOutcome.MISSING_INPUTS
                && result.modWarnings().stream().anyMatch(warning ->
                warning.getContents() instanceof TranslatableContents contents
                        && contents.getKey().equals("rsi.plan.warning.inventory_changing"))), same(snapshot));
        verifyNoMoreInteractions(destination);
    }

    @Test
    void sufficientSupplyDoesNotRefreshEvenWithALargeBudget() {
        CraftPlanResultDispatcher.Sink destination = mock(CraftPlanResultDispatcher.Sink.class);
        CraftPreviewInventoryRefresh refresh = new CraftPreviewInventoryRefresh(destination, Integer.MAX_VALUE,
                () -> true, (snapshot, plan) -> false, snapshot -> fail("不应清缓存"),
                task -> fail("不应排队"), (plan, sink) -> fail("不应重算"));
        PlanResponse plan = plan(true);
        PlanningSnapshot snapshot = snapshot();
        refresh.success(plan, snapshot);
        verify(destination).success(plan, snapshot);
        verifyNoMoreInteractions(destination);
    }

    @Test
    void supersededRequestCannotStartQueuedWork() {
        CraftPlanResultDispatcher.Sink destination = mock(CraftPlanResultDispatcher.Sink.class);
        Queue<Runnable> queue = new ArrayDeque<>();
        AtomicBoolean current = new AtomicBoolean(true);
        CraftPreviewInventoryRefresh refresh = new CraftPreviewInventoryRefresh(destination, 1, current::get,
                (snapshot, plan) -> true, snapshot -> fail("过期请求不应清缓存"), queue::add,
                (plan, sink) -> fail("过期请求不应重算"));
        refresh.success(plan(true), snapshot());
        current.set(false);
        queue.remove().run();
        verify(destination).cancelled(any());
        refresh.success(plan(true), snapshot());
        verifyNoMoreInteractions(destination);
    }

    @Test
    void staleRequestBeforeCompletionDoesNotQueueARefresh() {
        CraftPlanResultDispatcher.Sink destination = mock(CraftPlanResultDispatcher.Sink.class);
        CraftPreviewInventoryRefresh refresh = new CraftPreviewInventoryRefresh(destination, 1, () -> false,
                (snapshot, plan) -> { fail("过期请求不应再读库存"); return false; },
                snapshot -> fail("不应清缓存"), task -> fail("不应排队"), (plan, sink) -> fail("不应重算"));
        refresh.success(plan(true), snapshot());
        verify(destination).cancelled(any());
        verifyNoMoreInteractions(destination);
    }

    @Test
    void refreshFailureIsDeliveredOnce() {
        CraftPlanResultDispatcher.Sink destination = mock(CraftPlanResultDispatcher.Sink.class);
        Queue<Runnable> queue = new ArrayDeque<>();
        IntegrationDiagnostic diagnostic = IntegrationDiagnostic.error(IntegrationDiagnosticCode.PLANNER_BUSY);
        CraftPreviewInventoryRefresh refresh = new CraftPreviewInventoryRefresh(destination, 2, () -> true,
                (snapshot, plan) -> true, snapshot -> {}, queue::add,
                (plan, sink) -> sink.error(PlanOutcome.RUNTIME_UNAVAILABLE, diagnostic));
        refresh.success(plan(false), snapshot());
        queue.remove().run();
        refresh.error(PlanOutcome.RUNTIME_UNAVAILABLE, diagnostic);
        refresh.success(plan(true), snapshot());
        verify(destination).error(PlanOutcome.RUNTIME_UNAVAILABLE, diagnostic);
        verifyNoMoreInteractions(destination);
    }

    @Test
    void unexpectedRefreshExceptionDoesNotLeaveThePreviewWaiting() {
        CraftPlanResultDispatcher.Sink destination = mock(CraftPlanResultDispatcher.Sink.class);
        Queue<Runnable> queue = new ArrayDeque<>();
        CraftPreviewInventoryRefresh refresh = new CraftPreviewInventoryRefresh(destination, 1, () -> true,
                (snapshot, plan) -> true, snapshot -> {}, queue::add,
                (plan, sink) -> { throw new IllegalStateException("模拟重算异常"); });
        refresh.success(plan(false), snapshot());
        assertDoesNotThrow(() -> queue.remove().run());
        verify(destination).error(eq(PlanOutcome.RUNTIME_UNAVAILABLE), any(IntegrationDiagnostic.class));
        refresh.success(plan(true), snapshot());
        verifyNoMoreInteractions(destination);
    }

    @Test
    void inventoryNoticePreservesExecutionDataAndExistingDiagnostics() {
        PlanGraphView graph = new PlanGraphView(1, List.of(), List.of(), List.of(), List.of(), List.of());
        StorageReference reference = new StorageReference(new StorageBackendId("test"), "network:1");
        IntegrationDiagnostic diagnostic = IntegrationDiagnostic.error(IntegrationDiagnosticCode.PLANNER_BUSY);
        PlanResponse original = new PlanResponse(true, "target", new ItemStack(Items.DIAMOND), List.of(),
                Map.of(), List.of(), "test:target", "generic", "minecraft:overworld", 1, 2, 3,
                List.of(Component.literal("existing")), 17, null, null, null, 123L,
                false, true, true, new ItemStack(Items.COAL), Set.of("generic"), Map.of(),
                new ItemStack(Items.DIAMOND), graph, true, List.of(), Map.of(), reference,
                List.of(), PlanOutcome.RUNTIME_UNAVAILABLE, List.of(diagnostic));
        PlanResponse changed = original.withInventoryChangeNotice();
        // record 各字段逐一对照，防止加提示时遗漏执行侧字段。
        for (var field : PlanResponse.class.getRecordComponents()) {
            if (field.getName().equals("modWarnings")) continue;
            try {
                assertEquals(field.getAccessor().invoke(original), field.getAccessor().invoke(changed), field.getName());
            } catch (ReflectiveOperationException exception) {
                throw new AssertionError(exception);
            }
        }
        assertEquals(1, original.modWarnings().size());
        assertEquals(2, changed.modWarnings().size());
        assertEquals(original.modWarnings().get(0), changed.modWarnings().get(0));
    }

    private static PlanResponse plan(boolean success) {
        return new PlanResponse(success, "", ItemStack.EMPTY, List.of(), Map.of(),
                success ? List.of() : List.of("coal"), "test:target", List.of(), 1,
                success ? PlanOutcome.READY : PlanOutcome.MISSING_INPUTS, List.of());
    }

    private static PlanningSnapshot snapshot() {
        return new PlanningSnapshot(UUID.randomUUID(), 1, 7, new ResourceLocation("test", "target"),
                Map.of(), Map.of(), new ImmutableRecipeGraph(Map.of()), "network:inventory", "binding", false);
    }
}
