package com.huanghuang.rsintegration.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalItemProgressSuppressionTest {

    @AfterEach
    void clearSuppression() {
        ExternalItemProgressSuppression.consume();
    }

    @Test
    void defaultsToNotSuppressed() {
        ExternalItemProgressSuppression.beginOperation();

        assertFalse(ExternalItemProgressSuppression.consume());
    }

    @Test
    void suppressionIsConsumedOnce() {
        ExternalItemProgressSuppression.beginOperation();
        ExternalItemProgressSuppression.suppress();

        assertTrue(ExternalItemProgressSuppression.consume());
        assertFalse(ExternalItemProgressSuppression.consume());
    }

    @Test
    void newOperationClearsPreviousSuppression() {
        ExternalItemProgressSuppression.beginOperation();
        ExternalItemProgressSuppression.suppress();
        ExternalItemProgressSuppression.beginOperation();

        assertFalse(ExternalItemProgressSuppression.consume());
    }
}
