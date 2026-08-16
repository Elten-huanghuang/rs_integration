package com.huanghuang.rsintegration.compat.ftbquests;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeItemTaskSubmissionServiceTest {

    @Test
    void rejectedProgressMustRefundCommittedItems() {
        assertTrue(QuestProgressSettlement.shouldRefundRejectedProgress(0));
        assertTrue(QuestProgressSettlement.shouldRefundRejectedProgress(-1));
    }

    @Test
    void partialProgressMustNotRefundTheWholeReservation() {
        assertFalse(QuestProgressSettlement.shouldRefundRejectedProgress(1));
    }
}
