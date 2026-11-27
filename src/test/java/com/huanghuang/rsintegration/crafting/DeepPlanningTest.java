package com.huanghuang.rsintegration.crafting;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanGraph;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanValidator;
import com.huanghuang.rsintegration.crafting.graph.MaterialAllocation;
import com.huanghuang.rsintegration.crafting.graph.MaterialSource;
import com.huanghuang.rsintegration.crafting.plan.IntegrationDiagnostic;
import com.huanghuang.rsintegration.crafting.plan.IntegrationDiagnosticCode;
import com.huanghuang.rsintegration.crafting.plan.PlanGraphView;
import com.huanghuang.rsintegration.crafting.plan.PlanOutcome;
import com.huanghuang.rsintegration.crafting.plan.PlanResponse;
import com.huanghuang.rsintegration.crafting.plan.PlanResponsePacket;
import com.huanghuang.rsintegration.crafting.planning.TypedPreviewAdmissionQueue;
import com.huanghuang.rsintegration.crafting.tree.IngredientKey;
import com.huanghuang.rsintegration.crafting.tree.PlanTreeLayout;
import com.huanghuang.rsintegration.crafting.tree.PlanTreeModel;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeepPlanningTest extends BootstrapTest {
    @BeforeAll
    static void initializeMod() {
        DeepPlanningFixture.initializeMod();
    }

    @BeforeEach
    void loadDefaults() {
        var config = CommentedConfig.inMemory();
        RSIntegrationConfig.SERVER_SPEC.correct(config);
        RSIntegrationConfig.SERVER_SPEC.setConfig(config);
    }

    @AfterEach
    void unloadConfig() {
        RSIntegrationConfig.SERVER_SPEC.setConfig(null);
    }

    @ParameterizedTest
    @CsvSource({"256, false", "1024, true"})
    void bothPlanningPathsResolveAllStepsFromRawStock(int depth, boolean machineSlots) {
        try (var fixture = new DeepPlanningFixture(depth, 1, false)) {
            var pure = fixture.pure(true);
            assertTrue(pure.feasible());
            assertEquals(depth, pure.steps().size());

            var graph = assertComplete(fixture, machineSlots, depth, 1);
            var tree = roundTrip(fixture, graph);
            var raw = tree.findByKey(IngredientKey.of(new ItemStack(Items.STICK)));
            assertEquals(depth + 1, raw.depth);
            assertEquals(1, raw.amount);
            var layout = new PlanTreeLayout();
            layout.rebuild(tree.root);
            assertEquals(depth + 2, layout.boxes().size());
            var box = layout.boxFor(raw);
            assertEquals(raw, layout.hitTest(box.x(), box.y()).node());
        }
    }

    @Test
    void sharedProductionFeedsEveryConsumer() {
        try (var fixture = new DeepPlanningFixture(48, 16, true)) {
            assertComplete(fixture, false, 64, 16);
        }
    }

    @Test
    void deepSharedChainRetainsAllProducerNodesAfterWireRoundTrip() {
        try (var fixture = new DeepPlanningFixture(256, 4, true)) {
            var graph = assertComplete(fixture, true, 260, 4);
            var tree = roundTrip(fixture, graph);
            assertEquals(4L, PlanTreeModel.grossDemandByKey(tree).get(
                    IngredientKey.of(new ItemStack(Items.STICK))));
        }
    }

    @Test
    void deepMixedChainRetainsNativeRecipeDispatch() {
        try (var fixture = new DeepPlanningFixture(512, 1, false, true)) {
            var graph = assertComplete(fixture, true, 512, 1);
            assertEquals(171, graph.nodes().stream().filter(node -> node.modTypeId().equals("fluid_container")).count());
            roundTrip(fixture, graph);
        }
    }

    @Test
    void supplementalSlicesRespectUnfinishedRecipesAndYieldWithoutReportingShortages() {
        try (var fixture = new DeepPlanningFixture(12, 1, false)) {
            var missing = new ArrayList<String>();
            var search = fixture.supplemental(Set.of(), missing);
            assertFalse(search.advance(Long.MAX_VALUE, () -> false));
            int ticks = 0;
            while (!search.advance(0)) {
                assertTrue(missing.isEmpty());
                assertTrue(++ticks < 2000);
            }
            var graph = search.graph();
            assertTrue(ticks > graph.nodes().size());
            assertTrue(graph.unresolvedDemands().isEmpty());
            var unfinished = graph.nodes().get(0).recipeId();
            var blocked = fixture.supplemental(Set.of(unfinished), missing);
            assertTrue(blocked.advance(Long.MAX_VALUE));
            assertTrue(blocked.graph().rootDemands().stream().anyMatch(root -> root.unresolvedQuantity() > 0));
            assertFalse(missing.isEmpty());
        }
    }

    @Test
    void tinySlicesResumeTheSameSearchWithoutReplayingCandidates() {
        try (var fixture = new DeepPlanningFixture(512, 1, false, true)) {
            var expected = fixture.typed(true, true, 10_000, new ArrayList<>());
            var job = fixture.sliced(true, 10_000, new ArrayList<>());
            int yields = 0;
            while (!job.advance(0L)) {
                assertTrue(++yields < 20_000);
            }
            assertTrue(yields > 512);
            var actual = job.graph();
            assertEquals(expected.topologicalOrder(), actual.topologicalOrder());
            assertEquals(expected.allocations(), actual.allocations());
            assertEquals(expected.nodes().stream().map(node -> node.recipeId()).toList(),
                    actual.nodes().stream().map(node -> node.recipeId()).toList());
            assertTrue(actual.unresolvedDemands().isEmpty());
        }
    }

    @ParameterizedTest
    @CsvSource({"10, 0, 48, 96", "9, 320, 32, 64"})
    void fullTreeVisitsEveryNativeRouteAndPreservesAllInputs(int levels, int trunkDepth, int routeCount,
                                                            int rootAlternatives) {
        int nodes = (1 << levels) - 1 + trunkDepth;
        var seen = new HashSet<String>();
        try (var fixture = DeepPlanningFixture.fullTree(levels, trunkDepth, routeCount, rootAlternatives,
                type -> seen.add(type.id()))) {
            var missing = new ArrayList<String>();
            var job = fixture.sliced(true, 10_000, missing);
            var queue = new TypedPreviewAdmissionQueue(2);
            var player = new UUID(0L, 1L);
            class Preview implements Runnable {
                void enqueue() {
                    assertEquals(TypedPreviewAdmissionQueue.OfferResult.QUEUED,
                            queue.offer(new TypedPreviewAdmissionQueue.Request(player, 1, System.nanoTime(),
                                    this, () -> { throw new AssertionError("规划不应过期"); },
                                    failure -> { throw new AssertionError(failure); })));
                }

                @Override
                public void run() {
                    if (!job.advance(0L)) enqueue();
                }
            }
            new Preview().enqueue();
            int ticks = 0;
            while (queue.size() > 0) {
                // 即使队列还能取任务，同一请求也只能在一个 tick 中推进一次。
                assertEquals(1, queue.run(2, System.nanoTime(), -1L, request -> true));
                assertTrue(++ticks < 50_000);
            }
            assertTrue(ticks > nodes);
            var graph = job.graph();
            assertEquals(routeCount, seen.size());
            assertEquals(routeCount, graph.nodes().stream().map(node -> node.modTypeId()).distinct().count());
            assertEquals(nodes, graph.nodes().size());
            assertEquals(rootAlternatives - 1,
                    graph.nodes().get(graph.nodes().size() - 1 - trunkDepth).alternativeIds().size());
            assertTrue(missing.isEmpty());
            assertTrue(graph.unresolvedDemands().isEmpty());
            assertEquals(1L << (levels - 1), graph.allocations().stream()
                    .filter(a -> a.source() instanceof MaterialSource.InitialPool).mapToLong(MaterialAllocation::quantity).sum());
            var estimates = fixture.parallelEstimator().start(graph);
            int estimateTicks = 1;
            while (!estimates.advance(0L)) estimateTicks++;
            assertEquals(nodes, estimateTicks);
            var nodeEstimates = estimates.result();
            assertEquals(nodes, nodeEstimates.size());
            for (var node : graph.nodes()) assertTrue(nodeEstimates.containsKey(node.id().value()));
            // 每种路由只建立一次探针，实际余烬类型也可能已由其他测试注册。
            assertTrue(fixture.probeCalls() >= routeCount - 1 && fixture.probeCalls() <= routeCount);
            var tree = roundTrip(fixture, graph);
            assertEquals(1L << (levels - 1), PlanTreeModel.grossDemandByKey(tree)
                    .get(IngredientKey.of(new ItemStack(Items.STICK))));
        }
    }

    @Test
    void configuredDepthLimitDoesNotReturnTheOldUnresolvedPlaceholderGraph() {
        RSIntegrationConfig.CRAFTING_MAX_DEPTH.set(16);
        try (var fixture = new DeepPlanningFixture(64, 1, false)) {
            var legacy = fixture.legacyTyped(10_000, new ArrayList<>());
            assertEquals(17, legacy.nodes().size());
            assertFalse(legacy.unresolvedDemands().isEmpty());
            assertThrows(CraftingPlanningLimitException.class,
                    () -> fixture.typed(true, 10_000, new ArrayList<>()));
        }
    }

    @Test
    void graphCannotExceedTheStepBudgetWhenParentsFinishAfterTheirChildren() {
        RSIntegrationConfig.CRAFTING_MAX_STEPS.set(256);
        try (var fixture = new DeepPlanningFixture(512, 1, false)) {
            assertThrows(CraftingPlanningLimitException.class,
                    () -> fixture.typed(true, 10_000, new ArrayList<>()));
        }
    }

    @Test
    void missingRawIngredientStillProducesAnInfeasibleGraphForInspection() {
        try (var fixture = new DeepPlanningFixture(512, 1, false)) {
            CraftPlanGraph graph = fixture.typed(false, 10_000, new ArrayList<>());
            assertEquals(1, graph.unresolvedDemands().size());
            assertEquals(512, graph.nodes().size());
            var tree = roundTrip(fixture, graph);
            var raw = tree.findByKey(IngredientKey.of(new ItemStack(Items.STICK)));
            assertEquals(1, raw.unresolved);
        }
    }

    @Test
    void exhaustedDeadlineIsDistinctFromMissingMaterials() {
        var context = new ResolutionContext(null, Map.of(), Map.of(), null, null,
                null, null, true, List.of(), System.nanoTime() - 1, false);
        assertTrue(context.timedOut());
        assertThrows(CraftingPlanningTimeoutException.class, context::requireKnownPreviewBudget);
    }

    @Test
    void timeoutPreviewRendersCompletedBranchWithoutGuessingTheNextRoot() {
        try (var fixture = new DeepPlanningFixture(12, 2, false)) {
            var job = fixture.sliced(true, 1_000, new ArrayList<>());
            int frames = 0;
            while (job.completedRootsGraph().rootDemands().isEmpty()) {
                assertFalse(job.advance(0L));
                assertTrue(++frames < 2_000);
            }
            // 在下一个求解切片中耗尽累计预算，切片之间的等待不计入。
            assertThrows(CraftingPlanningTimeoutException.class, () -> job.advance(Long.MAX_VALUE, () -> {
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1_050);
                long remaining;
                while ((remaining = deadline - System.nanoTime()) > 0) LockSupport.parkNanos(remaining);
                return true;
            }));
            var graph = job.completedRootsGraph();
            assertTrue(job.timeoutSummary().contains("slowMaterials=[minecraft:"));
            assertTrue(job.timeoutSummary().contains(",tried="));
            CraftPlanValidator.validate(graph);
            assertEquals(12, graph.nodes().size());
            assertEquals(1, graph.rootDemands().size());
            assertTrue(graph.unresolvedDemands().isEmpty());
            assertEquals(0, graph.rootDemands().get(0).unresolvedQuantity());

            var diagnostic = IntegrationDiagnostic.error(IntegrationDiagnosticCode.PLANNING_TIMEOUT);
            var response = PlanResponse.incomplete(new ResourceLocation("test", "target"), fixture.target(), 1,
                    null, null, null, null, null, job.draftView(),
                    Map.of(new StackKey(Items.STICK, null), 2L), diagnostic);
            var decoded = PlanResponsePacket.decodePayload(PlanResponsePacket.encodePayload(response, 17L)).plan();
            assertEquals(response.recipeId(), decoded.recipeId());
            assertFalse(decoded.targetResult().isEmpty());
            assertEquals(PlanOutcome.BUDGET_EXHAUSTED, decoded.outcome());
            assertEquals(List.of(diagnostic), decoded.diagnostics());
            assertFalse(decoded.success());
            assertTrue(decoded.executionBlocked());
            assertEquals(Map.of(IngredientKey.of(new ItemStack(Items.STICK)),
                    new PlanResponse.Availability(1, 2, 0)), decoded.materials());
            var layout = new PlanTreeLayout();
            layout.rebuild(PlanTreeModel.from(decoded).root);
            assertEquals(2, decoded.graph().roots().size());
            assertEquals(1, decoded.graph().roots().stream().filter(PlanGraphView.RootView::pending).count());
            assertEquals(15, layout.boxes().size());
            assertTrue(layout.boxes().stream().anyMatch(box -> box.node().pendingRoot));
            assertTrue(layout.boxes().stream().noneMatch(box -> box.node().unresolved > 0));
        }
    }

    @Test
    void configMigrationOnlyChangesTheOldDefault() {
        assertEquals(1024, RSIntegrationConfig.migrateCraftingMaxDepth(8, 16));
        assertEquals(32, RSIntegrationConfig.migrateCraftingMaxDepth(8, 32));
        assertEquals(16, RSIntegrationConfig.migrateCraftingMaxDepth(9, 16));
    }

    private static CraftPlanGraph assertComplete(DeepPlanningFixture fixture, boolean machineSlots,
                                                 int nodes, int stock) {
        var missing = new ArrayList<String>();
        var graph = fixture.typed(true, machineSlots, 10_000, missing);
        assertEquals(nodes, graph.nodes().size());
        assertTrue(missing.isEmpty());
        assertTrue(graph.unresolvedDemands().isEmpty());
        assertTrue(graph.rootDemands().stream().allMatch(root -> root.unresolvedQuantity() == 0));
        CraftPlanValidator.validate(graph);
        assertEquals(stock, graph.allocations().stream().filter(a -> a.source() instanceof MaterialSource.InitialPool)
                .mapToLong(MaterialAllocation::quantity).sum());
        return graph;
    }

    private static PlanTreeModel roundTrip(DeepPlanningFixture fixture, CraftPlanGraph graph) {
        var response = new PlanResponse(graph.unresolvedDemands().isEmpty(), "deep", fixture.target(),
                List.of(), Map.of(), List.of(), null, null, null, 0, 0, 0, List.of(), 1,
                null, null, null, 0, false, false, false, null, Set.of(), Map.of(), null,
                PlanGraphView.from(graph));
        var decoded = PlanResponsePacket.decodePayload(PlanResponsePacket.encodePayload(response, 1L)).plan();
        assertEquals(graph.nodes().size(), decoded.graph().nodes().size());
        assertEquals(graph.allocations().size(), decoded.graph().edges().size());
        var tree = PlanTreeModel.from(decoded);
        assertFalse(tree.truncated());
        assertEquals(graph.nodes().size(), tree.graphReferenceCounts().size());
        return tree;
    }
}
