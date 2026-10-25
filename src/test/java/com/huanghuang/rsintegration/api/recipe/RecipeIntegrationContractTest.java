package com.huanghuang.rsintegration.api.recipe;

import com.huanghuang.rsintegration.api.diagnostic.IntegrationDiagnostic;
import com.huanghuang.rsintegration.api.diagnostic.IntegrationDiagnosticCode;
import com.huanghuang.rsintegration.api.diagnostic.IntegrationSeverity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Recipe;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeIntegrationContractTest {
    @Test
    void rejectedInspectionRequiresDiagnostics() {
        IntegrationDiagnostic diagnostic = diagnostic();
        RecipeInspectionResult result =
                RecipeInspectionResult.rejected(diagnostic);

        assertNull(result.view());
        assertEquals(List.of(diagnostic), result.diagnostics());
        assertThrows(IllegalArgumentException.class,
                () -> new RecipeInspectionResult(null, List.of()));
    }

    @Test
    void unavailableRecipeMustExplainWhyItCannotExecute() {
        assertTrue(RecipeAvailability.available().executionAvailable());
        assertThrows(IllegalArgumentException.class,
                () -> new RecipeAvailability(false, false, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new RecipeAvailability(false, true, List.of(diagnostic())));
    }

    @Test
    void recipeClaimWinnerMustBeOneOfCandidates() {
        RecipeIntegration<?> first = new StubIntegration("first");
        RecipeIntegration<?> second = new StubIntegration("second");

        RecipeClaim claim = new RecipeClaim(first, List.of(first, second), List.of());

        assertSame(first, claim.winner());
        assertThrows(IllegalArgumentException.class,
                () -> new RecipeClaim(new StubIntegration("third"),
                        List.of(first, second), List.of()));
    }

    @Test
    void integrationDefaultsAreConservative() {
        RecipeIntegration<?> integration = new StubIntegration("test");

        assertEquals(RecipeProjectionThread.SERVER_MAIN,
                integration.projectionThread());
        assertEquals(RecipeExecutionKind.NONE, integration.executionKind());
        assertFalse(integration.availabilityProvider().isPresent());
    }

    private static IntegrationDiagnostic diagnostic() {
        return new IntegrationDiagnostic(
                IntegrationDiagnosticCode.RECIPE_INSPECTION_FAILED,
                IntegrationSeverity.ERROR,
                new ResourceLocation("test", "integration"),
                Map.of());
    }

    private record StubIntegration(ResourceLocation integrationId)
            implements RecipeIntegration<Recipe<?>> {
        private StubIntegration(String path) {
            this(new ResourceLocation("test", path));
        }

        @Override
        public ResourceLocation id() {
            return integrationId;
        }

        @Override
        public boolean supports(Recipe<?> recipe) {
            return false;
        }

        @Override
        public RecipeInspectionResult inspect(
                RecipeContext context,
                Recipe<?> recipe) {
            return RecipeInspectionResult.rejected(diagnostic());
        }
    }
}
