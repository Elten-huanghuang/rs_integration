package com.huanghuang.rsintegration.feature.ftbquests.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuestRecipeRefreshStateTest {
    @Test
    void waitsForInitialServerSyncAndRefreshesWithoutAnotherProgressPacket() {
        QuestRecipeRefreshState state = new QuestRecipeRefreshState();
        Object file = new Object();
        Object data = new Object();
        assertFalse(state.tick(null, null, false));
        assertFalse(state.tick(file, null, true));
        assertFalse(state.tick(file, data, true));
        assertTrue(state.tick(file, data, false));
        state.refreshed();
        assertFalse(state.tick(file, data, false));
    }

    @Test
    void reloadingTheSameFileKeepsARefreshPendingUntilLoadingFinishes() {
        QuestRecipeRefreshState state = new QuestRecipeRefreshState();
        Object file = new Object();
        Object data = new Object();
        assertTrue(state.tick(file, data, false));
        state.refreshed();
        assertFalse(state.tick(file, data, true));
        assertTrue(state.tick(file, data, false));
    }

    @Test
    void replacedQuestFileOrTeamDataTriggersARefreshWithoutAnExplicitRequest() {
        QuestRecipeRefreshState state = new QuestRecipeRefreshState();
        Object file = new Object();
        Object data = new Object();
        assertTrue(state.tick(file, data, false));
        state.refreshed();
        data = new Object();
        assertTrue(state.tick(file, data, false));
        state.refreshed();
        assertTrue(state.tick(new Object(), data, false));
    }

    @Test
    void progressUpdatesAreDebouncedButCannotPostponeTheRefreshForever() {
        QuestRecipeRefreshState state = new QuestRecipeRefreshState();
        Object file = new Object();
        Object data = new Object();
        assertTrue(state.tick(file, data, false));
        state.refreshed();
        for (int tick = 1; tick < 100; tick++) {
            state.requestRefresh();
            assertFalse(state.tick(file, data, false));
        }
        state.requestRefresh();
        assertTrue(state.tick(file, data, false));
    }
}
