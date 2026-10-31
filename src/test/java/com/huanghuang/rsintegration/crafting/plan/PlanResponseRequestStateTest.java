package com.huanghuang.rsintegration.crafting.plan;

import org.junit.jupiter.api.Test;

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
        // 页面没有待处理请求时，保留从配方浏览器重新预览同配方的行为。
        assertTrue(state.accept(12L, POPPY));
        state.beginPreview(13L, DANDELION);
        assertTrue(state.accept(13L, DANDELION));
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
        state.beginPreview(12L, POPPY);
        assertTrue(state.accept(12L, POPPY));
    }

    @Test
    void pendingTracksOnlyUnansweredPreviews() {
        PlanResponseRequestState state = new PlanResponseRequestState(10L, POPPY);

        assertFalse(state.pending());
        state.beginPreview(11L, POPPY);
        assertTrue(state.pending());
        assertTrue(state.accept(11L, POPPY));
        assertFalse(state.pending());
    }

    @Test
    void confirmationWaitsForTheLatestPreviewAndCanOnlyBeConsumedOnce() {
        PlanResponseRequestState state = new PlanResponseRequestState(10L, POPPY);
        state.beginPreview(11L, POPPY);

        assertTrue(state.requestConfirmation());
        assertFalse(state.consumeConfirmation(true));
        assertFalse(state.accept(10L, POPPY));
        assertFalse(state.consumeConfirmation(true));
        assertTrue(state.accept(11L, POPPY));
        assertTrue(state.consumeConfirmation(true));
        assertFalse(state.consumeConfirmation(true));
    }

    @Test
    void confirmationBeforeDebounceExpiresWaitsForTheScheduledPreview() {
        PlanResponseRequestState state = new PlanResponseRequestState(10L, POPPY);
        state.scheduleRefresh();

        assertFalse(state.pending());
        assertTrue(state.waitingForPreview());
        assertTrue(state.requestConfirmation());
        assertFalse(state.consumeConfirmation(true));

        state.beginPreview(11L, POPPY);
        assertFalse(state.refreshScheduled());
        assertTrue(state.requestConfirmation());
        assertTrue(state.accept(11L, POPPY));
        assertTrue(state.consumeConfirmation(true));
    }

    @Test
    void repeatedConfirmationDoesNotCreateMultipleExecutions() {
        PlanResponseRequestState state = new PlanResponseRequestState(10L, POPPY);
        state.beginPreview(11L, POPPY);

        assertTrue(state.requestConfirmation());
        assertTrue(state.requestConfirmation());
        assertTrue(state.accept(11L, POPPY));
        assertTrue(state.consumeConfirmation(true));
        assertFalse(state.consumeConfirmation(true));
    }

    @Test
    void editingAgainCancelsQueuedConfirmationAndWaitsForTheNewQuantity() {
        PlanResponseRequestState state = new PlanResponseRequestState(10L, POPPY);
        state.beginPreview(11L, POPPY);
        state.requestConfirmation();
        state.scheduleRefresh();

        assertFalse(state.confirmationRequested());
        assertTrue(state.accept(11L, POPPY));
        assertTrue(state.waitingForPreview());
        assertFalse(state.consumeConfirmation(true));
        state.beginPreview(12L, POPPY);
        assertTrue(state.accept(12L, POPPY));
        assertFalse(state.consumeConfirmation(true));
    }

    @Test
    void switchingRecipesCancelsConfirmationForThePreviousRecipe() {
        PlanResponseRequestState state = new PlanResponseRequestState(10L, POPPY);
        state.beginPreview(11L, POPPY);
        state.requestConfirmation();
        state.beginPreview(12L, DANDELION);

        assertFalse(state.confirmationRequested());
        assertFalse(state.accept(11L, POPPY));
        assertTrue(state.accept(12L, DANDELION));
        assertFalse(state.consumeConfirmation(true));
    }

    @Test
    void failedPreviewDoesNotLeaveAConfirmationForALaterResponse() {
        PlanResponseRequestState state = new PlanResponseRequestState(10L, POPPY);
        state.beginPreview(11L, POPPY);
        state.requestConfirmation();

        assertTrue(state.accept(11L, POPPY));
        assertFalse(state.consumeConfirmation(false));
        assertFalse(state.confirmationRequested());
        assertTrue(state.accept(12L, POPPY));
        assertFalse(state.consumeConfirmation(true));
    }

    @Test
    void closingOrChangingExecutionSettingsCancelsQueuedConfirmation() {
        PlanResponseRequestState state = new PlanResponseRequestState(10L, POPPY);
        state.beginPreview(11L, POPPY);
        state.requestConfirmation();
        state.cancelConfirmation();

        assertTrue(state.pending());
        assertTrue(state.accept(11L, POPPY));
        assertFalse(state.consumeConfirmation(true));
    }

    @Test
    void readyPlanDoesNotNeedToQueueConfirmation() {
        PlanResponseRequestState state = new PlanResponseRequestState(10L, POPPY);

        assertFalse(state.requestConfirmation());
        assertFalse(state.confirmationRequested());
        assertFalse(state.waitingForPreview());
    }

    @Test
    void progressOnlyFailureReleasesConfirmationAndAllowsAnExplicitRetry() {
        PlanResponseRequestState state = new PlanResponseRequestState(10L, POPPY);
        state.beginPreview(11L, POPPY);
        state.requestConfirmation();

        assertFalse(state.failPreview(10L));
        assertTrue(state.confirmationRequested());
        assertTrue(state.failPreview(11L));
        assertFalse(state.pending());
        assertFalse(state.confirmationRequested());
        assertFalse(state.refreshScheduled());
        assertTrue(state.refreshRequired());
        assertFalse(state.consumeConfirmation(true));

        state.beginPreview(12L, POPPY);
        assertFalse(state.refreshRequired());
        assertTrue(state.requestConfirmation());
        assertTrue(state.accept(12L, POPPY));
        assertTrue(state.consumeConfirmation(true));
    }

    @Test
    void fullResponseAfterFailureProgressStillFinishesThePreviewNormally() {
        PlanResponseRequestState state = new PlanResponseRequestState(10L, POPPY);
        state.beginPreview(11L, POPPY);
        state.requestConfirmation();
        state.failPreview(11L);

        assertTrue(state.accept(11L, POPPY));
        assertFalse(state.waitingForPreview());
        assertFalse(state.consumeConfirmation(true));
    }

    @Test
    void questConfirmationWaitsForItsUncorrelatedPreview() {
        String quest = "rs_integration:quest_submission/42";
        PlanResponseRequestState state = new PlanResponseRequestState(0L, quest);
        state.beginPreview(0L, quest);

        assertTrue(state.requestConfirmation());
        assertFalse(state.consumeConfirmation(true));
        assertTrue(state.accept(0L, quest));
        assertTrue(state.consumeConfirmation(true));
    }
}
