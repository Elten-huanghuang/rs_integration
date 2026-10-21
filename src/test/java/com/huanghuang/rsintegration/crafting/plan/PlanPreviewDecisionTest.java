package com.huanghuang.rsintegration.crafting.plan;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanPreviewDecisionTest extends BootstrapTest {
    private static final ResourceLocation TARGET = new ResourceLocation("test", "cycle_target");
    private static final ResourceLocation OTHER = new ResourceLocation("test", "other");
    private static final ResourceLocation BLOCK = new ResourceLocation("test", "cycle_block");

    @Test
    void failedPreviewWithoutTargetStepKeepsTheUsablePlan() {
        PlanResponse current = plan(true, TARGET, TARGET);
        PlanResponse failed = plan(false, TARGET, OTHER);

        assertTrue(PlanPreviewDecision.keepCurrentPlan(current, failed));
    }

    @Test
    void failedPreviewWithTargetStepMayReplaceThePlan() {
        PlanResponse current = plan(true, TARGET, TARGET);
        PlanResponse failed = plan(false, TARGET, TARGET);

        assertFalse(PlanPreviewDecision.keepCurrentPlan(current, failed));
    }

    @Test
    void failedRecipeSwitchKeepsThePlanEvenWhenTargetStepExists() {
        PlanResponse current = plan(true, TARGET, TARGET);
        PlanResponse cyclic = planWithSteps(false, TARGET, List.of(
                new PlanStep(TARGET, new ItemStack(Items.DIAMOND), 1,
                        List.of(new ItemStack(Items.DIRT))),
                new PlanStep(BLOCK, new ItemStack(Items.DIRT), 1,
                        List.of(new ItemStack(Items.DIAMOND)))),
                PlanOutcome.CYCLE);

        assertTrue(PlanPreviewDecision.keepCurrentPlan(current, cyclic, true));
    }

    @Test
    void failedRecipeSwitchWithPlainMissingMaterialsMayReplaceThePlan() {
        PlanResponse current = plan(true, TARGET, TARGET);
        PlanResponse missing = planWithSteps(false, TARGET, List.of(
                new PlanStep(TARGET, new ItemStack(Items.DIAMOND), 1,
                        List.of(new ItemStack(Items.GOLD_INGOT)))),
                PlanOutcome.MISSING_INPUTS);

        assertFalse(PlanPreviewDecision.keepCurrentPlan(current, missing, true));
    }

    @Test
    void legalSelfInputIsNotGuessedAsACycleWithoutServerOutcome() {
        PlanResponse current = plan(true, TARGET, TARGET);
        PlanResponse amplifiedMissing = planWithSteps(false, TARGET, List.of(
                new PlanStep(TARGET, new ItemStack(Items.DIAMOND, 2), 1,
                        List.of(new ItemStack(Items.DIAMOND, 1), new ItemStack(Items.GOLD_INGOT)))),
                PlanOutcome.MISSING_INPUTS);

        assertFalse(PlanPreviewDecision.keepCurrentPlan(current, amplifiedMissing, true));
    }

    @Test
    void successfulPreviewAlwaysReplacesThePlan() {
        PlanResponse current = plan(true, TARGET, TARGET);
        PlanResponse successful = plan(true, TARGET, OTHER);

        assertFalse(PlanPreviewDecision.keepCurrentPlan(current, successful));
    }

    @Test
    void emptyCurrentPlanHasNothingToPreserve() {
        PlanResponse empty = plan(true, TARGET);

        assertFalse(PlanPreviewDecision.keepCurrentPlan(empty, plan(false, TARGET, OTHER)));
    }

    private static PlanResponse plan(boolean success, ResourceLocation recipe,
                                     ResourceLocation... stepRecipes) {
        List<PlanStep> steps = Arrays.stream(stepRecipes)
                .map(id -> new PlanStep(id, new ItemStack(Items.DIAMOND), 1, List.of()))
                .toList();
        return planWithSteps(success, recipe, steps);
    }

    private static PlanResponse planWithSteps(boolean success, ResourceLocation recipe,
                                              List<PlanStep> steps) {
        return new PlanResponse(success, "target", new ItemStack(Items.DIAMOND), steps,
                Map.of(), success ? List.of() : List.of("cycle"),
                recipe.toString(), List.of(), 1,
                success ? PlanOutcome.READY : PlanOutcome.MISSING_INPUTS, List.of());
    }

    private static PlanResponse planWithSteps(boolean success, ResourceLocation recipe,
                                              List<PlanStep> steps, PlanOutcome outcome) {
        return new PlanResponse(success, "target", new ItemStack(Items.DIAMOND), steps,
                Map.of(), success ? List.of() : List.of("failed"),
                recipe.toString(), List.of(), 1, outcome, List.of());
    }
}
