package com.huanghuang.rsintegration.compat.ftbquests;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuestInventorySubmissionContextTest {
    @Test
    void suppressesOnlyInsideTheSettlementScope() {
        assertFalse(QuestInventorySubmissionContext.isSuppressed());
        try (QuestInventorySubmissionContext.Scope ignored =
                     QuestInventorySubmissionContext.open()) {
            assertTrue(QuestInventorySubmissionContext.isSuppressed());
        }
        assertFalse(QuestInventorySubmissionContext.isSuppressed());
    }

    @Test
    void nestedAndRepeatedCloseCannotLeakOrClearAnOuterScope() {
        QuestInventorySubmissionContext.Scope outer = QuestInventorySubmissionContext.open();
        QuestInventorySubmissionContext.Scope inner = QuestInventorySubmissionContext.open();
        inner.close();
        inner.close();
        assertTrue(QuestInventorySubmissionContext.isSuppressed());
        outer.close();
        assertFalse(QuestInventorySubmissionContext.isSuppressed());
    }
}
