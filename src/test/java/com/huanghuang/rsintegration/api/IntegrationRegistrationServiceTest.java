package com.huanghuang.rsintegration.api;

import com.huanghuang.rsintegration.api.diagnostic.IntegrationDiagnosticCode;
import com.huanghuang.rsintegration.api.machine.MachineContext;
import com.huanghuang.rsintegration.api.machine.MachineDescriptorResult;
import com.huanghuang.rsintegration.api.machine.MachineIntegration;
import com.huanghuang.rsintegration.api.recipe.RecipeClaim;
import com.huanghuang.rsintegration.api.recipe.RecipeContext;
import com.huanghuang.rsintegration.api.recipe.RecipeInspectionResult;
import com.huanghuang.rsintegration.api.recipe.RecipeIntegration;
import com.huanghuang.rsintegration.api.diagnostic.IntegrationDiagnostic;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.eventbus.api.BusBuilder;
import net.minecraftforge.eventbus.api.IEventBus;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class IntegrationRegistrationServiceTest {
    @Test
    void registrationOnlyAcceptsDuringRegistrationPhase() {
        DefaultRSIIntegrationApi api = new DefaultRSIIntegrationApi();
        StubRecipeIntegration recipe = new StubRecipeIntegration("recipe", 0);
        StubMachineIntegration machine = new StubMachineIntegration("recipe");

        RegistrationResult before = api.registerRecipe(recipe);
        assertEquals(RegistrationStatus.PHASE_INVALID, before.status());

        api.beginRegistration();
        RegistrationResult recipeResult = api.registerRecipe(recipe);
        RegistrationResult machineResult = api.registerMachine(machine);
        RegistrationResult duplicateRecipe = api.registerRecipe(recipe);

        assertEquals(RegistrationStatus.ACCEPTED, recipeResult.status());
        assertEquals(RegistrationStatus.ACCEPTED, machineResult.status());
        assertEquals(RegistrationStatus.REJECTED, duplicateRecipe.status());
        assertEquals(IntegrationDiagnosticCode.REGISTRATION_DUPLICATE_ID,
                duplicateRecipe.diagnostics().get(0).code());

        api.freezeRegistration();
        RegistrationResult frozen = api.registerRecipe(
                new StubRecipeIntegration("late", 0));
        assertEquals(RegistrationStatus.FROZEN, frozen.status());
    }

    @Test
    void lifecycleRejectionPrecedesIntegrationValidation() {
        DefaultRSIIntegrationApi api = new DefaultRSIIntegrationApi();

        assertEquals(
                RegistrationStatus.PHASE_INVALID,
                api.registerRecipe(null).status()
        );

        api.beginRegistration();
        assertEquals(
                RegistrationStatus.REJECTED,
                api.registerRecipe(null).status()
        );

        api.freezeRegistration();
        assertEquals(
                RegistrationStatus.FROZEN,
                api.registerRecipe(null).status()
        );

        api.stop();
        assertEquals(
                RegistrationStatus.PHASE_INVALID,
                api.registerRecipe(null).status()
        );
    }

    @Test
    void recipeClaimUsesHighestPriorityAndRejectsTies() {
        DefaultRSIIntegrationApi api = new DefaultRSIIntegrationApi();
        StubRecipeIntegration low = new StubRecipeIntegration("low", 10);
        StubRecipeIntegration high = new StubRecipeIntegration("high", 20);
        StubRecipeIntegration tied = new StubRecipeIntegration("tied", 20);
        Recipe<?> recipe = mock(Recipe.class);
        api.beginRegistration();
        api.registerRecipe(low, new RegistrationOptions(low.priority(), Map.of()));
        api.registerRecipe(high, new RegistrationOptions(high.priority(), Map.of()));

        RecipeClaim unambiguous = api.recipes().claim(recipe);
        assertSame(high, unambiguous.winner());
        assertEquals(1, unambiguous.candidates().size());

        api.registerRecipe(tied, new RegistrationOptions(tied.priority(), Map.of()));
        RecipeClaim ambiguous = api.recipes().claim(recipe);
        assertNull(ambiguous.winner());
        assertTrue(ambiguous.ambiguous());
        assertEquals(IntegrationDiagnosticCode.RECIPE_CLAIM_AMBIGUOUS,
                ambiguous.diagnostics().get(0).code());
    }

    @Test
    void registrationEventCarriesTheSameApiEntryPoint() {
        DefaultRSIIntegrationApi api = new DefaultRSIIntegrationApi();
        RSIIntegrationRegistrationEvent event =
                new RSIIntegrationRegistrationEvent(api);

        assertSame(api, event.api());
        assertEquals(new ApiVersion(1, 0, 0), event.api().version());
        assertFalse(event.isCancelable());
    }

    @Test
    void forgeEventBusDeliversThePublicRegistrationEntryPoint() {
        DefaultRSIIntegrationApi api = new DefaultRSIIntegrationApi();
        AtomicReference<RSIIntegrationRegistrationEvent> received =
                new AtomicReference<>();
        IEventBus bus = BusBuilder.builder().build();
        bus.addListener((RSIIntegrationRegistrationEvent event) ->
                received.set(event));

        bus.post(new RSIIntegrationRegistrationEvent(api));

        assertSame(api, received.get().api());
    }

    @Test
    void stoppedApiResumesFrozenWithoutLosingRegistrations() {
        DefaultRSIIntegrationApi api = new DefaultRSIIntegrationApi();
        StubRecipeIntegration recipe = new StubRecipeIntegration("recipe", 0);
        api.beginRegistration();
        api.registerRecipe(recipe);
        api.freezeRegistration();

        api.stop();
        assertEquals(RegistrationPhase.STOPPED, api.phase());
        api.resumeAfterStop();

        assertEquals(RegistrationPhase.FROZEN, api.phase());
        assertSame(recipe, api.recipes().find(mock(Recipe.class))
                .orElseThrow());
    }

    private static final class StubRecipeIntegration
            implements RecipeIntegration<Recipe<?>> {
        private final ResourceLocation id;
        private final int priority;

        private StubRecipeIntegration(String path, int priority) {
            this.id = new ResourceLocation("test", path);
            this.priority = priority;
        }

        @Override
        public ResourceLocation id() {
            return id;
        }

        @Override
        public boolean supports(Recipe<?> recipe) {
            return true;
        }

        @Override
        public RecipeInspectionResult inspect(
                RecipeContext context, Recipe<?> recipe) {
            return RecipeInspectionResult.rejected(
                    IntegrationDiagnostic.error(
                            IntegrationDiagnosticCode.RECIPE_INSPECTION_FAILED));
        }

        private int priority() {
            return priority;
        }
    }

    private static final class StubMachineIntegration implements MachineIntegration {
        private final ResourceLocation id;

        private StubMachineIntegration(String path) {
            this.id = new ResourceLocation("test", path);
        }

        @Override
        public ResourceLocation id() {
            return id;
        }

        @Override
        public boolean supports(BlockEntity blockEntity) {
            return false;
        }

        @Override
        public MachineDescriptorResult describe(
                MachineContext context, BlockEntity blockEntity) {
            return MachineDescriptorResult.rejected(
                    IntegrationDiagnostic.error(
                            IntegrationDiagnosticCode.MACHINE_DESCRIPTOR_INVALID));
        }
    }
}
