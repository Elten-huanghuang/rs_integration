package com.huanghuang.rsintegration.crafting.planning;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanRequestServiceTest {
    @Test
    void newerGenerationInvalidatesOlderAndForgetClearsCurrent() {
        try (PlanRequestService service = new PlanRequestService(1)) {
            UUID player = UUID.randomUUID();
            long first = service.begin(player);
            assertTrue(service.isCurrent(player, first));
            long second = service.begin(player);
            assertFalse(service.isCurrent(player, first));
            assertTrue(service.isCurrent(player, second));
            service.forget(player);
            assertFalse(service.isCurrent(player, second));
        }
    }
}
