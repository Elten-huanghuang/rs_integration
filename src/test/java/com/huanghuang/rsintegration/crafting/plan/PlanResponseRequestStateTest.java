package com.huanghuang.rsintegration.crafting.plan;

import org.junit.jupiter.api.Test;

import static com.huanghuang.rsintegration.crafting.plan.PlanResponseRequestState.Confirmation.BLOCKED;
import static com.huanghuang.rsintegration.crafting.plan.PlanResponseRequestState.Confirmation.EXECUTE;
import static com.huanghuang.rsintegration.crafting.plan.PlanResponseRequestState.Confirmation.RECALCULATE;
import static com.huanghuang.rsintegration.crafting.plan.PlanResponseRequestState.Confirmation.WAIT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanResponseRequestStateTest {
    private static final String POPPY = "the_long_travail:spring_game/poppy";
    private static final String DANDELION = "the_long_travail:spring_game/dandelion";
    private static final String PITCHER_PLANT = "the_long_travail:spring_game/pitcher_plant";

    @Test
    void rootFlowerSwitchAcceptsTheRequestedRecipeInsteadOfTheDisplayedRecipe() {
        PlanResponseRequestState state = new PlanResponseRequestState(10L, POPPY);
        state.beginPreview(11L, DANDELION);

        assertFalse(state.accept(11L, POPPY));
        assertTrue(state.accept(11L, DANDELION));
        assertEquals(DANDELION, state.expectedRecipeId());
        assertEquals(11L, state.activeRequestId());
    }

    @Test
    void rapidSwitchesRejectOldResponsesBeforeAndAfterTheLatestResponse() {
        PlanResponseRequestState state = new PlanResponseRequestState(10L, POPPY);
        state.beginPreview(11L, DANDELION);
        state.beginPreview(12L, PITCHER_PLANT);

        assertFalse(state.accept(11L, DANDELION));
        assertTrue(state.accept(12L, PITCHER_PLANT));
        assertFalse(state.accept(11L, DANDELION));
        assertEquals(PITCHER_PLANT, state.expectedRecipeId());
        assertEquals(12L, state.activeRequestId());
    }

    @Test
    void switchingBackToTheOriginalFlowerStillRequiresTheLatestRequest() {
        PlanResponseRequestState state = new PlanResponseRequestState(10L, POPPY);
        state.beginPreview(11L, DANDELION);
        state.beginPreview(12L, POPPY);

        assertFalse(state.accept(10L, POPPY));
        assertFalse(state.accept(11L, DANDELION));
        assertTrue(state.accept(12L, POPPY));
    }

    @Test
    void unrelatedResponsesDoNotConsumeThePendingSwitch() {
        PlanResponseRequestState state = new PlanResponseRequestState(10L, POPPY);
        state.beginPreview(11L, DANDELION);

        assertFalse(state.accept(12L, DANDELION));
        assertFalse(state.accept(11L, "minecraft:stick"));
        assertFalse(state.accept(0L, DANDELION));
        assertTrue(state.accept(11L, DANDELION));
    }

    @Test
    void sameRecipeRefreshAndSubsequentSwitchBothRemainAvailable() {
        PlanResponseRequestState state = new PlanResponseRequestState(10L, POPPY);
        state.beginPreview(11L, POPPY);

        assertTrue(state.accept(11L, POPPY));
        // 同配方的新任务应单独保存，不能覆盖这个已完成的页面。
        assertFalse(state.accept(12L, POPPY));
        state.beginPreview(13L, DANDELION);
        assertTrue(state.accept(13L, DANDELION));
    }

    @Test
    void editingOnePageOnlySupersedesItsOwnPendingRequest() {
        PlanResponseRequestState first = new PlanResponseRequestState(10L, POPPY);
        PlanResponseRequestState second = new PlanResponseRequestState(11L, POPPY);
        assertEquals(0L, first.beginPreview(12L, POPPY));
        assertEquals(0L, second.beginPreview(13L, POPPY));

        assertEquals(12L, first.beginPreview(14L, DANDELION));
        assertFalse(first.accept(12L, POPPY));
        assertTrue(second.pending());
        assertTrue(second.accept(13L, POPPY));
        assertTrue(first.failPreview(14L));
        assertFalse(first.pending());
        assertTrue(first.refreshRequired());
        assertEquals(0L, first.beginPreview(15L, DANDELION));
        assertTrue(first.accept(15L, DANDELION));
    }

    @Test
    void initialResponseIdPreventsAnOlderPlanFromOverwritingANewScreen() {
        PlanResponseRequestState state = new PlanResponseRequestState(12L, POPPY);

        assertFalse(state.accept(11L, POPPY));
        assertFalse(state.accept(0L, POPPY));
        assertFalse(state.accept(13L, DANDELION));
        assertTrue(state.accept(12L, POPPY));
    }

    @Test
    void uncorrelatedQuestPreviewsCanStillRefresh() {
        String quest = "rs_integration:quest_submission/42";
        PlanResponseRequestState state = new PlanResponseRequestState(0L, quest);
        state.beginPreview(0L, quest);

        assertFalse(state.accept(0L, POPPY));
        assertTrue(state.accept(0L, quest));
    }

    @Test
    void matchingErrorResponseDoesNotAllowAnOlderPreviewToReturn() {
        PlanResponseRequestState state = new PlanResponseRequestState(10L, POPPY);
        state.beginPreview(11L, DANDELION);

        assertFalse(state.accept(10L, ""));
        assertTrue(state.accept(11L, ""));
        assertFalse(state.accept(10L, POPPY));
        assertFalse(state.calculating());
        assertEquals(RECALCULATE, state.confirmation(false, false, true));
        state.beginPreview(12L, POPPY);
        assertTrue(state.accept(12L, POPPY));
        assertEquals(EXECUTE, state.confirmation(false, false, true));
    }

    @Test
    void pendingTracksOnlyUnansweredPreviews() {
        PlanResponseRequestState state = new PlanResponseRequestState(10L, POPPY);

        assertFalse(state.pending());
        assertFalse(state.calculating());
        state.beginPreview(11L, POPPY);
        assertTrue(state.pending());
        assertTrue(state.accept(11L, POPPY));
        assertFalse(state.pending());
    }

    @Test
    void inventoryRecalculationDoesNotAuthorizeExecutionWhileWaiting() {
        PlanResponseRequestState state = new PlanResponseRequestState(10L, POPPY);

        assertEquals(RECALCULATE, state.confirmation(false, true, false));
        state.beginPreview(11L, POPPY);

        assertEquals(WAIT, state.confirmation(false, true, true));
        assertEquals(WAIT, state.confirmation(false, false, true));
        assertFalse(state.accept(10L, POPPY));
        assertEquals(WAIT, state.confirmation(false, false, true));
        assertTrue(state.accept(11L, POPPY));
        assertEquals(EXECUTE, state.confirmation(false, false, true));
    }

    @Test
    void confirmationBeforeDebounceExpiresRequestsOnlyTheLatestQuantityPreview() {
        PlanResponseRequestState state = new PlanResponseRequestState(10L, POPPY);
        state.scheduleRefresh();
        assertEquals(RECALCULATE, state.confirmation(false, false, true));

        state.beginPreview(11L, POPPY);
        assertFalse(state.refreshScheduled());
        assertEquals(WAIT, state.confirmation(false, false, true));

        state.scheduleRefresh();
        assertEquals(RECALCULATE, state.confirmation(false, false, true));

        assertTrue(state.accept(11L, POPPY));
        assertTrue(state.waitingForPreview());
        assertEquals(RECALCULATE, state.confirmation(false, false, true));
        state.beginPreview(12L, POPPY);
        assertFalse(state.accept(11L, POPPY));
        assertEquals(WAIT, state.confirmation(false, false, true));
        assertTrue(state.accept(12L, POPPY));
        assertEquals(EXECUTE, state.confirmation(false, false, true));
    }

    @Test
    void keepingTheOldTreeAfterAFailedPreviewRequiresARefreshBeforeExecution() {
        PlanResponseRequestState state = new PlanResponseRequestState(10L, POPPY);
        state.beginPreview(11L, POPPY);

        assertTrue(state.accept(11L, POPPY));
        state.requireRetry();
        assertFalse(state.calculating(), "重算失败后必须恢复路线修改和重试入口");
        assertEquals(RECALCULATE, state.confirmation(false, false, true));
        state.beginPreview(12L, POPPY);
        assertEquals(WAIT, state.confirmation(false, false, true));
        assertTrue(state.accept(12L, POPPY));
        assertEquals(EXECUTE, state.confirmation(false, false, true));
    }

    @Test
    void readOnlyDraftRemainsBlockedEvenWithPreparationEnabledOrARefreshScheduled() {
        PlanResponseRequestState state = new PlanResponseRequestState(10L, POPPY);

        assertEquals(BLOCKED, state.confirmation(true, false, true));
        state.scheduleRefresh();
        assertEquals(BLOCKED, state.confirmation(true, true, true));
    }

    @Test
    void readyPlanCanExecuteAndBlockedPlanCannot() {
        PlanResponseRequestState state = new PlanResponseRequestState(10L, POPPY);

        assertEquals(EXECUTE, state.confirmation(false, false, true));
        assertEquals(BLOCKED, state.confirmation(false, false, false));
    }

    @Test
    void progressOnlyFailureAllowsAnExplicitRetryButNotExecutionOfTheOldPlan() {
        PlanResponseRequestState state = new PlanResponseRequestState(10L, POPPY);
        state.beginPreview(11L, POPPY);

        assertFalse(state.failPreview(10L));
        assertTrue(state.failPreview(11L));
        assertFalse(state.pending());
        assertFalse(state.refreshScheduled());
        assertTrue(state.refreshRequired());
        assertEquals(RECALCULATE, state.confirmation(false, false, true));

        state.beginPreview(12L, POPPY);
        assertFalse(state.refreshRequired());
        assertEquals(WAIT, state.confirmation(false, false, true));
        assertTrue(state.accept(12L, POPPY));
        assertEquals(EXECUTE, state.confirmation(false, false, true));
    }

    @Test
    void fullResponseAfterFailureProgressStillFinishesThePreviewNormally() {
        PlanResponseRequestState state = new PlanResponseRequestState(10L, POPPY);
        state.beginPreview(11L, POPPY);
        state.failPreview(11L);

        assertTrue(state.accept(11L, POPPY));
        assertFalse(state.waitingForPreview());
        assertEquals(EXECUTE, state.confirmation(false, false, true));
    }
}
