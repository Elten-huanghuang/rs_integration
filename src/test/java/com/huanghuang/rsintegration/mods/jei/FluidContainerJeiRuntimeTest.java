package com.huanghuang.rsintegration.mods.jei;

import com.huanghuang.rsintegration.client.recipe.FluidContainerRecipeCategory;
import com.huanghuang.rsintegration.crafting.fluid.FluidContainerRecipe;
import mezz.jei.api.recipe.IRecipeManager;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class FluidContainerJeiRuntimeTest {
    @Test
    void registersInBatchesAndRestoresExistingIdsAfterReload() {
        IRecipeManager manager = mock(IRecipeManager.class);
        var runtime = new FluidContainerJeiRuntime(manager);
        long revision = FluidContainerJeiRuntime.completedRevision();
        var type = FluidContainerRecipeCategory.TYPE;
        List<FluidContainerRecipe> initial = new ArrayList<>();
        for (int i = 0; i < 33; i++) initial.add(recipe("test_" + i));

        runtime.update(null);
        verifyNoMoreInteractions(manager);
        runtime.update(initial);
        verify(manager).addRecipes(type, initial.subList(0, 32));
        assertEquals(revision, FluidContainerJeiRuntime.completedRevision());
        verifyNoMoreInteractions(manager);
        runtime.update(initial);
        verify(manager).addRecipes(type, initial.subList(32, 33));
        runtime.update(initial);
        verifyNoMoreInteractions(manager);
        assertEquals(revision + 1, FluidContainerJeiRuntime.completedRevision());

        runtime.update(null);
        verify(manager).hideRecipes(type, initial);
        var newRecipe = recipe("new");
        runtime.update(List.of(recipe("test_32"), newRecipe));
        verify(manager).addRecipes(type, List.of(newRecipe));
        verify(manager).unhideRecipes(type, List.of(initial.get(32)));
        assertEquals(revision + 2, FluidContainerJeiRuntime.completedRevision());
        verifyNoMoreInteractions(manager);
    }

    private static FluidContainerRecipe recipe(String id) {
        FluidContainerRecipe recipe = mock(FluidContainerRecipe.class);
        when(recipe.getId()).thenReturn(new ResourceLocation("rs_integration", "fluid_container/" + id));
        return recipe;
    }
}
