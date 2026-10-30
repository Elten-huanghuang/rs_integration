package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.crafting.graph.CraftPlanGraph;
import com.huanghuang.rsintegration.crafting.graph.InputPortId;
import com.huanghuang.rsintegration.crafting.graph.NodeId;
import com.huanghuang.rsintegration.crafting.graph.RootDemand;
import com.huanghuang.rsintegration.crafting.graph.UnresolvedDemand;
import com.huanghuang.rsintegration.crafting.plan.IntegrationDiagnostic;
import com.huanghuang.rsintegration.crafting.plan.IntegrationDiagnosticCode;
import com.huanghuang.rsintegration.crafting.plan.PlanGraphView;
import com.huanghuang.rsintegration.crafting.plan.PlanOutcome;
import com.huanghuang.rsintegration.crafting.plan.PlanResponse;
import com.huanghuang.rsintegration.crafting.tree.IngredientKey;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CraftPlanExecutionEligibilityTest extends BootstrapTest {
    private final CraftPlanGraph graph = new CraftPlanGraph(CraftPlanGraph.CURRENT_VERSION,
            List.of(), List.of(), List.of(), List.of(), List.of());

    @Test
    void leasedMachineCanWaitUsingTheCompletePreviewGraph() {
        assertTrue(CraftPlanDecisionRules.canExecuteCachedPlan(busyPlan(graph), graph));
    }

    @Test
    void recursivelyProducedMaterialsDoNotCountAsMissingInitialStock() {
        PlanResponse plan = plan(PlanOutcome.RUNTIME_UNAVAILABLE, false,
                List.of(IntegrationDiagnostic.error(IntegrationDiagnosticCode.MACHINES_LEASED)),
                Map.of(IngredientKey.of(new ItemStack(Items.GLASS)),
                        new PlanResponse.Availability(3, 0, 0)),
                List.of(), PlanGraphView.from(graph));

        assertTrue(CraftPlanDecisionRules.canExecuteCachedPlan(plan, graph));
    }

    @ParameterizedTest
    @EnumSource(value = PlanOutcome.class, names = "RUNTIME_UNAVAILABLE", mode = EnumSource.Mode.EXCLUDE)
    void otherFailureOutcomesCannotBeRetriedAsMachineContention(PlanOutcome outcome) {
        PlanResponse plan = plan(outcome, false,
                List.of(IntegrationDiagnostic.error(IntegrationDiagnosticCode.MACHINES_LEASED)),
                Map.of(), List.of(), PlanGraphView.from(graph));

        assertFalse(CraftPlanDecisionRules.canExecuteCachedPlan(plan, graph));
    }

    @ParameterizedTest
    @EnumSource(value = IntegrationDiagnosticCode.class, names = "MACHINES_LEASED",
            mode = EnumSource.Mode.EXCLUDE)
    void otherFailuresCannotBeHiddenByTheMachineLeaseDiagnostic(IntegrationDiagnosticCode code) {
        PlanResponse plan = plan(PlanOutcome.RUNTIME_UNAVAILABLE, false,
                List.of(IntegrationDiagnostic.error(IntegrationDiagnosticCode.MACHINES_LEASED),
                        IntegrationDiagnostic.error(code)),
                Map.of(), List.of(), PlanGraphView.from(graph));

        assertFalse(CraftPlanDecisionRules.canExecuteCachedPlan(plan, graph));
    }

    @Test
    void runtimeFailureWithoutAnExplicitLeaseDiagnosticIsRejected() {
        assertFalse(CraftPlanDecisionRules.canExecuteCachedPlan(
                plan(PlanOutcome.RUNTIME_UNAVAILABLE, false, List.of(),
                        Map.of(), List.of(), PlanGraphView.from(graph)), graph));
    }

    @Test
    void hardPrerequisiteFailuresRemainBlocked() {
        assertFalse(CraftPlanDecisionRules.canExecuteCachedPlan(
                plan(PlanOutcome.RUNTIME_UNAVAILABLE, true,
                        List.of(IntegrationDiagnostic.error(IntegrationDiagnosticCode.MACHINES_LEASED)),
                        Map.of(), List.of(), PlanGraphView.from(graph)), graph));
    }

    @Test
    void blockedRecursiveStepsRemainBlocked() {
        PlanResponse plan = plan(PlanOutcome.RUNTIME_UNAVAILABLE, false,
                List.of(IntegrationDiagnostic.error(IntegrationDiagnosticCode.MACHINES_LEASED)),
                Map.of(), List.of(), PlanGraphView.from(graph),
                Map.of(new ResourceLocation("test", "intermediate"),
                        new PlanResponse.StepIssue(List.of(), true)));

        assertFalse(CraftPlanDecisionRules.canExecuteCachedPlan(plan, graph));
    }

    @Test
    void missingMaterialsRemainBlockedEvenWhenTheMachineIsLeased() {
        List<IntegrationDiagnostic> diagnostics = List.of(
                IntegrationDiagnostic.error(IntegrationDiagnosticCode.MACHINES_LEASED));
        assertFalse(CraftPlanDecisionRules.canExecuteCachedPlan(
                plan(PlanOutcome.RUNTIME_UNAVAILABLE, false, diagnostics,
                        Map.of(), List.of("glass"), PlanGraphView.from(graph)), graph));
        assertFalse(CraftPlanDecisionRules.canExecuteCachedPlan(
                plan(PlanOutcome.RUNTIME_UNAVAILABLE, false, diagnostics,
                        Map.of(IngredientKey.of(new ItemStack(Items.GLASS)),
                                new PlanResponse.Availability(1, 0)),
                        List.of(), PlanGraphView.from(graph)), graph));
    }

    @Test
    void bothTheDisplayGraphAndAuthoritativeGraphAreRequired() {
        assertFalse(CraftPlanDecisionRules.canExecuteCachedPlan(busyPlan(graph), null));
        assertFalse(CraftPlanDecisionRules.canExecuteCachedPlan(busyPlan(null), graph));
    }

    @Test
    void unresolvedInputsInEitherGraphRemainBlocked() {
        CraftPlanGraph incomplete = new CraftPlanGraph(CraftPlanGraph.CURRENT_VERSION,
                List.of(), List.of(), List.of(),
                List.of(new UnresolvedDemand(new InputPortId(new NodeId(0), 0),
                        Ingredient.of(Items.GLASS), 1, new ItemStack(Items.GLASS))),
                List.of());

        assertFalse(CraftPlanDecisionRules.canExecuteCachedPlan(busyPlan(incomplete), graph));
        assertFalse(CraftPlanDecisionRules.canExecuteCachedPlan(busyPlan(graph), incomplete));
    }

    @Test
    void unresolvedRootMaterialsInEitherGraphRemainBlocked() {
        CraftPlanGraph incomplete = new CraftPlanGraph(CraftPlanGraph.CURRENT_VERSION,
                List.of(), List.of(),
                List.of(new RootDemand(Ingredient.of(Items.GLASS), 1, 1,
                        new ItemStack(Items.GLASS), List.of())),
                List.of(), List.of());

        assertFalse(CraftPlanDecisionRules.canExecuteCachedPlan(busyPlan(incomplete), graph));
        assertFalse(CraftPlanDecisionRules.canExecuteCachedPlan(busyPlan(graph), incomplete));
    }

    @Test
    void successfulLegacyPlansKeepTheirExistingEligibility() {
        PlanResponse plan = new PlanResponse(true, "", new ItemStack(Items.GLASS),
                List.of(), Map.of(), List.of(), "test:glass");

        assertTrue(CraftPlanDecisionRules.canExecuteCachedPlan(plan, null));
    }

    private PlanResponse busyPlan(CraftPlanGraph resolvedGraph) {
        return plan(PlanOutcome.RUNTIME_UNAVAILABLE, false,
                List.of(IntegrationDiagnostic.error(IntegrationDiagnosticCode.MACHINES_LEASED)),
                Map.of(), List.of(), resolvedGraph == null ? null : PlanGraphView.from(resolvedGraph));
    }

    private PlanResponse plan(PlanOutcome outcome, boolean blocked,
                              List<IntegrationDiagnostic> diagnostics,
                              Map<IngredientKey, PlanResponse.Availability> materials,
                              List<String> missing, PlanGraphView view) {
        return plan(outcome, blocked, diagnostics, materials, missing, view, Map.of());
    }

    private PlanResponse plan(PlanOutcome outcome, boolean blocked,
                              List<IntegrationDiagnostic> diagnostics,
                              Map<IngredientKey, PlanResponse.Availability> materials,
                              List<String> missing, PlanGraphView view,
                              Map<ResourceLocation, PlanResponse.StepIssue> stepIssues) {
        return new PlanResponse(false, "", new ItemStack(Items.GLASS),
                List.of(), materials, missing, "test:glass", "generic",
                null, 0, 0, 0, List.of(), 1, null, null, null, 0L,
                false, false, false, null, Set.of(), Map.of(), null, view,
                blocked, List.of(), stepIssues, null, List.of(), outcome, diagnostics);
    }
}
