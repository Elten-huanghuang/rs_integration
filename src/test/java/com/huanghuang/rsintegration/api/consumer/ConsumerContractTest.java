package com.huanghuang.rsintegration.api.consumer;

import com.huanghuang.rsintegration.api.diagnostic.IntegrationDiagnostic;
import com.huanghuang.rsintegration.api.diagnostic.IntegrationDiagnosticCode;
import com.huanghuang.rsintegration.api.diagnostic.IntegrationSeverity;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsumerContractTest extends BootstrapTest {
    @Test
    void rejectedResultRequiresDiagnostic() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ConsumerResult<>(null, List.of(), false)
        );
        ConsumerResult<String> rejected = ConsumerResult.rejected(
                ConsumerDiagnostic.error(
                        ConsumerDiagnosticCode.INVALID_REQUEST,
                        "bad request",
                        false
                )
        );

        assertFalse(rejected.successful());
        assertEquals(1, rejected.diagnostics().size());
    }

    @Test
    void readyCraftabilityRequiresPlanAndShortageStatusRequiresResource() {
        ConsumerRecipeKey recipe = recipeKey();

        assertThrows(
                IllegalArgumentException.class,
                () -> new ConsumerCraftability(
                        recipe,
                        ConsumerCraftabilityStatus.READY,
                        1L,
                        1L,
                        List.of(),
                        null,
                        List.of()
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new ConsumerCraftability(
                        recipe,
                        ConsumerCraftabilityStatus.MISSING_MATERIALS,
                        1L,
                        0L,
                        List.of(),
                        null,
                        List.of(diagnostic())
                )
        );
    }

    @Test
    void inspectionPageCannotPublishPartialResultsOrAdvanceEarly() {
        ConsumerInspectionTicketId ticketId =
                new ConsumerInspectionTicketId(UUID.randomUUID());
        ConsumerCraftability result = new ConsumerCraftability(
                recipeKey(),
                ConsumerCraftabilityStatus.RECIPE_UNSUPPORTED,
                1L,
                0L,
                List.of(),
                null,
                List.of(diagnostic())
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> new ConsumerInspectionPage(
                        ticketId,
                        0,
                        ConsumerInspectionPageState.RUNNING,
                        null,
                        1L,
                        List.of(result)
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new ConsumerInspectionPage(
                        ticketId,
                        0,
                        ConsumerInspectionPageState.PENDING,
                        1,
                        1L,
                        List.of()
                )
        );
    }

    @Test
    void catalogPageRequiresAForwardCursor() {
        ConsumerRecipeQuery query = new ConsumerRecipeQuery.All();

        assertThrows(
                IllegalArgumentException.class,
                () -> new ConsumerRecipeCatalogPage(
                        query,
                        1L,
                        4L,
                        4L,
                        List.of()
                )
        );
    }

    @Test
    void batchOrdersMustUseTheDeclaredConsumer() {
        ResourceLocation firstConsumer = resource("first_consumer");
        ResourceLocation secondConsumer = resource("second_consumer");

        assertThrows(
                IllegalArgumentException.class,
                () -> new ConsumerBatchOrderRequest(
                        firstConsumer,
                        new ConsumerBatchRequestId(UUID.randomUUID()),
                        List.of(
                                order(firstConsumer),
                                order(secondConsumer)
                        )
                )
        );
    }

    @Test
    void planningPreferencesDefensivelyCopyAndRejectBlanks() {
        List<ResourceLocation> excluded = new ArrayList<>();
        excluded.add(resource("excluded"));
        ConsumerPlanningPreferences preferences =
                new ConsumerPlanningPreferences(Map.of(), Map.of(), Set.copyOf(excluded));
        excluded.clear();

        assertEquals(
                Set.of(resource("excluded")),
                preferences.excludedRecipes()
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new ConsumerPlanningPreferences(
                        Map.of(" ", resource("forced")),
                        Map.of(),
                        Set.of()
                )
        );
    }

    private static ConsumerRecipeKey recipeKey() {
        return new ConsumerRecipeKey(
                resource("recipe"),
                null,
                new ConsumerRequestAmount.OutputUnits(1L),
                ConsumerPlanningPreferences.empty()
        );
    }

    private static ConsumerOrderRequest order(ResourceLocation consumerId) {
        return new ConsumerOrderRequest(
                consumerId,
                new ConsumerExternalRequestId(UUID.randomUUID()),
                new ConsumerPlanId(UUID.randomUUID())
        );
    }

    private static IntegrationDiagnostic diagnostic() {
        return new IntegrationDiagnostic(
                IntegrationDiagnosticCode.RECIPE_INSPECTION_FAILED,
                IntegrationSeverity.ERROR,
                null,
                Map.of()
        );
    }

    private static ResourceLocation resource(String path) {
        return new ResourceLocation("test", path);
    }
}
