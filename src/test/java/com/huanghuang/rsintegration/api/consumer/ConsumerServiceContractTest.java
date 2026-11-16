package com.huanghuang.rsintegration.api.consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ConsumerServiceContractTest {
    @ParameterizedTest
    @EnumSource(value = ConsumerServiceState.class, names = {"CREATED", "INDEXING", "STOPPED"})
    void everyBusinessEntryRejectsBeforeDereferencingArgumentsWhenUnavailable(ConsumerServiceState state) {
        DefaultRSICraftConsumerApi api = new DefaultRSICraftConsumerApi();
        if (state == ConsumerServiceState.INDEXING) api.markIndexing();
        if (state == ConsumerServiceState.STOPPED) api.stop();
        ConsumerDiagnosticCode expected = state == ConsumerServiceState.STOPPED
                ? ConsumerDiagnosticCode.SERVICE_STOPPING : ConsumerDiagnosticCode.CATALOG_NOT_READY;
        List<ConsumerResult<?>> results = List.of(
                api.storageOptions(null),
                api.findRecipes(null, null),
                api.openInspection(null, null),
                api.nextInspectionPage(null, null),
                api.closeInspection(null, null),
                api.discardPlan(null, null, null),
                api.submit(null, null),
                api.submitBatch(null, null),
                api.status(null, null, null),
                api.cancel(null, null, null, null));
        for (ConsumerResult<?> result : results) {
            assertFalse(result.successful());
            assertFalse(result.idempotentReplay());
            assertEquals(expected, result.diagnostics().get(0).code());
        }
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

}
