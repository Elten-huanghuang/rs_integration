package com.huanghuang.rsintegration.compat.ftbquests;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FtbQuestRewardDropContextTest {

    @Test
    void restoresNestedScope() {
        assertFalse(FtbQuestRewardDropContext.isActive());

        try (FtbQuestRewardDropContext.Scope outer = FtbQuestRewardDropContext.activate()) {
            assertTrue(FtbQuestRewardDropContext.isActive());
            try (FtbQuestRewardDropContext.Scope inner = FtbQuestRewardDropContext.activate()) {
                assertTrue(FtbQuestRewardDropContext.isActive());
            }
            assertTrue(FtbQuestRewardDropContext.isActive());
        }

        assertFalse(FtbQuestRewardDropContext.isActive());
    }

    @Test
    void closingScopeTwiceDoesNotPopAnotherScope() {
        FtbQuestRewardDropContext.Scope outer = FtbQuestRewardDropContext.activate();
        FtbQuestRewardDropContext.Scope inner = FtbQuestRewardDropContext.activate();

        inner.close();
        inner.close();
        assertTrue(FtbQuestRewardDropContext.isActive());

        outer.close();
        assertFalse(FtbQuestRewardDropContext.isActive());
    }
}
