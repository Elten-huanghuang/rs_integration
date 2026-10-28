package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.recipe.ModRecipeHandler;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import net.minecraft.world.item.crafting.Recipe;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class RecipeIndexTest {
    @Test
    void externalClaimOwnsIndexedTypeWithoutLegacyClassification() {
        Recipe<?> recipe = mock(Recipe.class);
        ModRecipeHandler handler = mock(ModRecipeHandler.class);
        ModType external = ModType.EXTERNAL_INTEGRATION;
        when(handler.modType(recipe)).thenReturn(external);

        try (MockedStatic<ModType> classifications =
                     mockStatic(ModType.class)) {
            assertEquals(
                    external,
                    RecipeIndex.resolveIndexedModType(recipe, handler)
            );
            classifications.verify(
                    () -> ModType.classifyRecipe(recipe),
                    never()
            );
        }
    }

    @Test
    void internalHandlerRetainsRecipeFamilyClassification() {
        Recipe<?> recipe = mock(Recipe.class);
        ModRecipeHandler handler = mock(ModRecipeHandler.class);
        ModType claimed = ModType.GENERIC;
        ModType classified = ModType.CUSTOM_GUI;
        when(handler.modType(recipe)).thenReturn(claimed);

        try (MockedStatic<ModType> classifications =
                     mockStatic(ModType.class)) {
            classifications.when(
                    () -> ModType.classifyRecipe(recipe)
            ).thenReturn(classified);

            assertEquals(
                    classified,
                    RecipeIndex.resolveIndexedModType(recipe, handler)
            );
        }
    }

    @Test
    void backgroundProjectionKeepsExternalClaimOwnership() {
        Recipe<?> recipe = mock(Recipe.class);
        ModRecipeHandler handler = mock(ModRecipeHandler.class);
        when(handler.modType(recipe))
                .thenReturn(ModType.EXTERNAL_INTEGRATION);
        when(handler.supportsBackgroundPlanning(recipe)).thenReturn(true);
        when(handler.hasDeterministicPrimaryOutput(recipe)).thenReturn(true);

        try (MockedStatic<ModRecipeHandlers> claims =
                     mockStatic(ModRecipeHandlers.class)) {
            claims.when(() -> ModRecipeHandlers.claimFor(recipe))
                    .thenReturn(new ModRecipeHandlers.Claim(
                            handler, List.of(handler)));

            assertTrue(RecipeIndex.isBackgroundProjectable(recipe));
        }
    }
}
