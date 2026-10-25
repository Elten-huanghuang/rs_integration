package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.server.level.ServerPlayer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class OperationStartContextTest extends BootstrapTest {
    @Test
    void legacyContextsHaveNoFabricatedOperationIdentity() {
        ServerPlayer player = mock(ServerPlayer.class);
        OperationStartContext context = new OperationStartContext(
                player,
                new ExtractionLedger(),
                OperationStartContext.MaterialOwnership.CHAIN_RESERVED,
                MaterialPlan.none(),
                List.of(),
                InputBufferPlan.none(),
                null
        );

        assertNull(context.transactionId());
        assertNull(context.operationId());
        assertEquals(1, context.attempt());
    }

    @Test
    void operationIdentityRequiresBothIdsAndPositiveAttempt() {
        ServerPlayer player = mock(ServerPlayer.class);
        OperationStartContext context = new OperationStartContext(
                player,
                new ExtractionLedger(),
                OperationStartContext.MaterialOwnership.CHAIN_RESERVED,
                MaterialPlan.none(),
                List.of(),
                InputBufferPlan.none(),
                null
        );
        UUID transactionId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();

        OperationStartContext identified =
                context.withOperationIdentity(transactionId, operationId, 2);

        assertEquals(transactionId, identified.transactionId());
        assertEquals(operationId, identified.operationId());
        assertEquals(2, identified.attempt());
        assertThrows(IllegalArgumentException.class,
                () -> context.withOperationIdentity(
                        transactionId, operationId, 0));
        assertThrows(IllegalArgumentException.class, () ->
                new OperationStartContext(
                        player,
                        new ExtractionLedger(),
                        OperationStartContext.MaterialOwnership.CHAIN_RESERVED,
                        MaterialPlan.none(),
                        List.of(),
                        InputBufferPlan.none(),
                        null,
                        transactionId,
                        null,
                        1
                ));
    }
}
