package com.huanghuang.rsintegration.crafting;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanGraph;
import com.huanghuang.rsintegration.crafting.graph.MaterialSource;
import com.huanghuang.rsintegration.crafting.plan.IntegrationDiagnostic;
import com.huanghuang.rsintegration.crafting.plan.IntegrationDiagnosticCode;
import com.huanghuang.rsintegration.crafting.plan.PlanGraphView;
import com.huanghuang.rsintegration.crafting.plan.PlanOutcome;
import com.huanghuang.rsintegration.crafting.plan.PlanResponse;
import com.huanghuang.rsintegration.crafting.planning.FrozenRecipeBranch;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class HybridBranchPlanningTest extends BootstrapTest {
    @BeforeAll
    static void configure() {
        DeepPlanningFixture.initializeMod();
        var config = CommentedConfig.inMemory();
        RSIntegrationConfig.SERVER_SPEC.correct(config);
        RSIntegrationConfig.SERVER_SPEC.setConfig(config);
    }

    private static CraftPlanGraph finish(CraftingResolver.TypedPreview preview) {
        while (!preview.advance(5_000_000L)) assertFalse(preview.waitingForBranch());
        return preview.graph();
    }

    @Test
    void diamondAggregatesInWorkerAndSuppliesBothConsumers() {
        try (var fixture = new DeepPlanningFixture(4, 2, true)) {
            int[] submissions = {0};
            var job = fixture.hybrid(new ArrayList<>(), (branch, callback) -> {
                var candidate = branch.solve();
                assertNotNull(candidate);
                assertEquals(7, candidate.steps().size());
                assertEquals(4, candidate.steps().stream().filter(step -> step.batches() == 2).count());
                submissions[0]++;
                callback.accept(candidate);
            }, (keys, adjust) -> true, fixture.joinedRoots());
            var graph = finish(job);
            assertEquals(1, submissions[0]);
            assertEquals(1, job.admittedBranches());
            var shared = graph.nodes().stream().filter(node -> node.recipeId().getPath().equals("deep_3"))
                    .findFirst().orElseThrow();
            assertEquals(2, graph.allocations().stream()
                    .filter(edge -> edge.source() instanceof MaterialSource.ProducerOutput source
                            && source.outputPort().nodeId().equals(shared.id()))
                    .map(edge -> edge.consumer().nodeId()).distinct().count());
            assertEquals(2, graph.allocations().stream().filter(edge -> edge.source() instanceof MaterialSource.InitialPool)
                    .mapToLong(edge -> edge.quantity()).sum());
        }
    }

    @Test
    void lostUnusedStockRollsBackCandidateAndReplansAgainstReducedPool() {
        try (var fixture = new DeepPlanningFixture(4, 2, true)) {
            var stick = new CraftingResolver.StackKey(Items.STICK, null);
            boolean[] corrected = {false};
            var job = fixture.hybrid(new ArrayList<>(), (branch, callback) -> callback.accept(branch.solve()),
                    (keys, adjust) -> {
                        if (corrected[0] || !keys.contains(stick)) return true;
                        assertTrue(adjust.apply(stick, 2, 1));
                        corrected[0] = true;
                        return false;
                    }, fixture.joinedRoots());
            var graph = finish(job);
            assertTrue(corrected[0]);
            assertFalse(graph.unresolvedDemands().isEmpty());
            assertEquals(1, graph.allocations().stream().filter(edge -> edge.source() instanceof MaterialSource.InitialPool)
                    .mapToLong(edge -> edge.quantity()).sum());
        }
    }

    @Test
    void dynamicCallbackFailureKeepsDraftReadOnlyAndUnsolvedInputsUnknown() {
        try (var fixture = DeepPlanningFixture.hybridTree(4, 0, 4, 1, type -> {})) {
            fixture.onAvailabilityCheck(() -> { throw new IllegalStateException("机器状态读取失败"); });
            var job = fixture.hybrid(new ArrayList<>(), (branch, callback) -> callback.accept(branch.solve()));
            var failure = assertThrows(PlanningCallbackException.class, () -> finish(job));
            var draft = PlanResponse.incomplete(failure.recipe(), fixture.target(), 1, null, null,
                    null, null, null, PlanGraphView.from(job.completedRootsGraph()), Map.of(),
                    IntegrationDiagnostic.error(IntegrationDiagnosticCode.UNSUPPORTED_RECIPE));
            assertEquals(PlanOutcome.UNSUPPORTED_SEMANTICS, draft.outcome());
            assertFalse(draft.canConfirmExecution());
            assertTrue(draft.missing().isEmpty());
            assertTrue(draft.materials().values().stream().noneMatch(row -> row.missingCount() > 0));
        }
    }

    @Test
    void rejectsCorruptDeltaAndReplaysOnlyThatBranch() {
        try (var fixture = new DeepPlanningFixture(4, 2, true)) {
            var missing = new ArrayList<String>();
            int[] trials = {0};
            var preview = fixture.hybrid(missing, (branch, callback) -> {
                var candidate = branch.solve();
                assertNotNull(candidate);
                trials[0]++;
                callback.accept(new FrozenRecipeBranch.Candidate(candidate.steps(), Map.of(branch.target().alternatives().get(0), 1L)));
            });
            var graph = finish(preview);
            assertTrue(trials[0] > 0, preview::timeoutSummary);
            assertEquals(0, preview.admittedBranches());
            assertTrue(missing.isEmpty());
            assertEquals(2L, graph.allocations().stream().filter(a -> a.source() instanceof MaterialSource.InitialPool)
                    .mapToLong(a -> a.quantity()).sum());
            assertEquals(2, graph.rootDemands().size());
        }
    }

    @Test
    void cancelledPreviewDiscardsLateDeliveryBeforeNextGeneration() {
        try (var fixture = new DeepPlanningFixture(4, 2, true)) {
            var late = new AtomicReference<Runnable>();
            var old = fixture.hybrid(new ArrayList<>(), (branch, callback) ->
                    late.set(() -> callback.accept(branch.solve())));
            while (!old.waitingForBranch()) assertFalse(old.advance(Long.MAX_VALUE), old::timeoutSummary);
            old.cancel();
            var fresh = fixture.hybrid(new ArrayList<>(), (branch, callback) -> callback.accept(branch.solve()));
            late.get().run();
            assertEquals(0, old.admittedBranches());
            var graph = finish(fresh);
            assertTrue(fresh.admittedBranches() > 0, fresh::timeoutSummary);
            assertEquals(2, graph.rootDemands().size());
            assertTrue(graph.unresolvedDemands().isEmpty());
        }
    }
}
