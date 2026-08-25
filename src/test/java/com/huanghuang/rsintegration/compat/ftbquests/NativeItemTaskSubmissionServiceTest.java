package com.huanghuang.rsintegration.compat.ftbquests;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void acceptedAmountIsComputedBeforeRepeatableAutoReset() {
        assertEquals(4L, QuestProgressSettlement.expectedAccepted(0L, 4L, 4L));
        assertEquals(2L, QuestProgressSettlement.expectedAccepted(2L, 4L, 3L));
        assertEquals(0L, QuestProgressSettlement.expectedAccepted(4L, 4L, 1L));
    }
}
