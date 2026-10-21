package com.huanghuang.rsintegration.crafting.tree;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipePreviewRendererRoutingTest {

    @Test
    void syntheticFluidContainerRecipesUseTheCatalogRoute() {
        assertTrue(RecipePreviewRenderer.isFluidContainerRecipe(new ResourceLocation(
                "rs_integration",
                "fluid_container/drain/3ad7099e97567c269eda68014fe3162a56a31023ce6523eab8689cd4f3ff34be")));
        assertTrue(RecipePreviewRenderer.isFluidContainerRecipe(new ResourceLocation(
                "rs_integration", "fluid_container/fill/abc")));
        assertFalse(RecipePreviewRenderer.isFluidContainerRecipe(
                new ResourceLocation("minecraft", "crystal_matrix_ingot")));
    }
}
