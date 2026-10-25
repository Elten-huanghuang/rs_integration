package com.huanghuang.rsintegration.api.consumer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsumerServiceContractTest {
    @Test
    void indexingServiceRejectsBusinessCallsBeforePlayerAccess() {
        DefaultRSICraftConsumerApi api =
                new DefaultRSICraftConsumerApi();
        api.markIndexing();

        ConsumerResult<ConsumerRecipeCatalogPage> result =
                api.findRecipes(null, null);

        assertFalse(result.successful());
        assertEquals(
                ConsumerDiagnosticCode.CATALOG_NOT_READY,
                result.diagnostics().get(0).code()
        );
    }

    @Test
    void lifecycleMovesFromIndexingToStopped() {
        DefaultRSICraftConsumerApi api =
                new DefaultRSICraftConsumerApi();

        api.markIndexing();
        assertEquals(ConsumerServiceState.INDEXING, api.state());

        api.stop();
        assertEquals(ConsumerServiceState.STOPPED, api.state());
        ConsumerResult<?> result = api.status(
                null, null, null);
        assertFalse(result.successful());
        assertEquals(
                ConsumerDiagnosticCode.SERVICE_STOPPING,
                result.diagnostics().get(0).code()
        );
    }

    @Test
    void capabilitiesRemainPositiveAndVersioned() {
        DefaultRSICraftConsumerApi api =
                new DefaultRSICraftConsumerApi();

        assertEquals(1, api.version().major());
        assertTrue(api.capabilities().maxPageSize() > 0);
        assertTrue(api.capabilities().sessionTtlTicks() > 0L);
    }

    @Test
    void serviceLookupUsesTheInstalledImplementation() {
        DefaultRSICraftConsumerApi api =
                new DefaultRSICraftConsumerApi();
        try {
            RSIConsumerServices.install(api);

            assertSame(api, RSICraftConsumerApi.get().orElseThrow());
        } finally {
            RSIConsumerServices.clear();
        }
    }
}
