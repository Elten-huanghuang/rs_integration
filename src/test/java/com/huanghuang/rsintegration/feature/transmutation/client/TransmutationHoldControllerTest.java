package com.huanghuang.rsintegration.feature.transmutation.client;

import com.huanghuang.rsintegration.feature.transmutation.TransmutationTableReroll;
import com.huanghuang.rsintegration.feature.transmutation.TransmutationTableReroll.Outcome;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransmutationHoldControllerTest {
    @Test
    void waitsForTheServerBeforeSendingAnotherRequest() {
        var controller = new TransmutationHoldController();
        assertTrue(controller.press());
        assertTrue(controller.nextRequest(0));
        for (int tick = 0; tick < 20; tick++) {
            assertFalse(controller.nextRequest(0));
            assertFalse(controller.press());
        }
        int acknowledged = TransmutationTableReroll.nextState(0, Outcome.READY);
        assertTrue(controller.nextRequest(acknowledged));
        assertFalse(controller.nextRequest(acknowledged));
    }

    @Test
    void stopOutcomesDoNotRestartFromOperatingSystemKeyRepeat() {
        for (Outcome outcome : new Outcome[]{Outcome.MATCHED, Outcome.UNAVAILABLE}) {
            var controller = new TransmutationHoldController();
            controller.press();
            assertTrue(controller.nextRequest(0));
            int response = TransmutationTableReroll.nextState(0, outcome);
            assertFalse(controller.nextRequest(response));
            assertEquals(outcome, controller.stoppedWith());
            assertFalse(controller.press());
            assertFalse(controller.nextRequest(response));
            controller.release();
            assertTrue(controller.press());
            assertTrue(controller.nextRequest(response));
        }
    }

    @Test
    void releasingOrLosingTheScreenStopsWithoutAnotherRequest() {
        var controller = new TransmutationHoldController();
        controller.press();
        controller.nextRequest(0);
        controller.release();
        assertFalse(controller.nextRequest(5));
        assertNull(controller.stoppedWith());

        controller.press();
        controller.cancel();
        assertFalse(controller.nextRequest(5));
        assertFalse(controller.press());
    }

    @Test
    void timeoutStopsInsteadOfRetryingAnUncertainTransmutation() {
        var controller = new TransmutationHoldController();
        controller.press();
        controller.nextRequest(0);
        for (int tick = 0; tick < 100; tick++) assertFalse(controller.nextRequest(0));
        assertEquals(Outcome.UNAVAILABLE, controller.stoppedWith());
        assertFalse(controller.press());
        assertFalse(controller.nextRequest(5));
    }
}
