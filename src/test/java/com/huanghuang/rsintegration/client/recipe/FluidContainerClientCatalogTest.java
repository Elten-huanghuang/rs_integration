package com.huanghuang.rsintegration.client.recipe;

import com.huanghuang.rsintegration.crafting.fluid.FluidContainerBucketTestFixtures;
import com.huanghuang.rsintegration.crafting.fluid.FluidContainerCatalog;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidTestFixtures;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraftforge.fluids.FluidStack;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FluidContainerClientCatalogTest extends BootstrapTest {
    @Test
    void coldClientLookupDoesNotScanRecipesAndOldWorldRequestIsDiscarded() {
        RecipeManager manager = mock(RecipeManager.class);
        ClientLevel first = clientLevel(manager);
        var id = new ResourceLocation("rs_integration", "fluid_container/not_yet_available");

        try {
            assertTrue(FluidContainerCatalog.allRecipes(first).isEmpty());
            assertNull(FluidContainerClientCatalog.resolve(first, id));
            assertNull(FluidContainerClientCatalog.requestRecipes(first));
            verifyNoInteractions(manager);

            FluidContainerClientCatalog.tick(null, 0L);
            FluidContainerClientCatalog.tick(first, 0L);
            verifyNoInteractions(manager);
            assertNull(FluidContainerCatalog.publishedRecipes(first));
        } finally {
            FluidContainerClientCatalog.tick(null, 0L);
        }
    }

    @Test
    void ticksPublishContainerRecipesAfterTheRequestedBuildCompletes() {
        ClientLevel level = clientLevel(new RecipeManager());
        var tokenItem = InkFluidTestFixtures.tokenItem();
        try (var capabilities = FluidContainerBucketTestFixtures.capabilities();
             var tokens = mockStatic(InkFluidSupport.class, CALLS_REAL_METHODS)) {
            tokens.when(() -> InkFluidSupport.token(any(FluidStack.class)))
                    .thenAnswer(call -> InkFluidSupport.token(tokenItem, call.getArgument(0)));
            assertNull(FluidContainerClientCatalog.requestRecipes(level));
            FluidContainerClientCatalog.tick(level, 0L);
            assertNull(FluidContainerCatalog.publishedRecipes(level));

            for (int tick = 0; tick < 100 && FluidContainerCatalog.publishedRecipes(level) == null; tick++) {
                FluidContainerClientCatalog.tick(level, TimeUnit.MILLISECONDS.toNanos(5));
            }
            var ready = FluidContainerClientCatalog.requestRecipes(level);
            assertNotNull(ready);
            assertFalse(ready.isEmpty());
            var recipe = ready.get(0);
            assertSame(recipe, FluidContainerClientCatalog.resolve(level, recipe.getId()));
        } finally {
            FluidContainerClientCatalog.tick(null, 0L);
            FluidContainerCatalog.invalidateClientRecipes(level);
        }
    }

    private static ClientLevel clientLevel(RecipeManager manager) {
        ClientLevel level = mock(ClientLevel.class);
        when(level.getRecipeManager()).thenReturn(manager);
        when(level.isClientSide()).thenReturn(true);
        return level;
    }
}
