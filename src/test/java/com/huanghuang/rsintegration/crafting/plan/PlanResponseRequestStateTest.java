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
}
