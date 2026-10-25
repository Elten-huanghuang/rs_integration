package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.crafting.RSICraftException;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CraftFailureMessagesTest {
    @Test
    void structuredNetworkReasonDoesNotDependOnExceptionText() {
        RSICraftException failure =
                RSICraftException.networkUnavailable(new RuntimeException("third-party detail"));

        TranslatableContents contents = (TranslatableContents) CraftFailureMessages
                .build(failure, new ResourceLocation("test", "recipe")).getContents();

        assertEquals("rsi.generic.error.network_unavailable", contents.getKey());
        assertEquals(RSICraftException.Reason.NETWORK_UNAVAILABLE, failure.reason());
        assertTrue(failure.retryable());
    }
}
