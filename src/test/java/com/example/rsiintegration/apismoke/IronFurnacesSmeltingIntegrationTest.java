package com.example.rsiintegration.apismoke;

import com.huanghuang.rsintegration.api.machine.MachineContext;
import com.huanghuang.rsintegration.api.machine.MachineDescriptor;
import com.huanghuang.rsintegration.api.machine.MachineDescriptorResult;
import com.huanghuang.rsintegration.api.machine.MachineExecutionMode;
import com.huanghuang.rsintegration.api.machine.ItemPortAccess;
import com.huanghuang.rsintegration.api.recipe.InputConsumption;
import com.huanghuang.rsintegration.api.recipe.RecipeContext;
import com.huanghuang.rsintegration.api.recipe.RecipeInspectionResult;
import com.huanghuang.rsintegration.api.recipe.RecipeView;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import ironfurnaces.tileentity.furnaces.BlockIronFurnaceTileBase;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IronFurnacesSmeltingIntegrationTest extends BootstrapTest {
    @Test
    void supportsAllStandardCookingRecipes() {
        IronFurnacesSmeltingIntegration integration =
                new IronFurnacesSmeltingIntegration();
        AbstractCookingRecipe rawIron =
                mock(AbstractCookingRecipe.class);
        when(rawIron.getId()).thenReturn(new ResourceLocation(
                "minecraft", "iron_ingot_from_smelting_raw_iron"));
        assertTrue(integration.recipe().supports(rawIron));

        AbstractCookingRecipe unrelated =
                mock(AbstractCookingRecipe.class);
        when(unrelated.getId()).thenReturn(new ResourceLocation(
                "minecraft", "gold_ingot_from_smelting_raw_gold"));
        assertTrue(integration.recipe().supports(unrelated));

        assertFalse(integration.recipe().supports(
                mock(Recipe.class)));
    }

    @Test
    void smokeSmeltingRecipeProjectsAsStandardIo() {
        AbstractCookingRecipe recipe = mock(AbstractCookingRecipe.class);
        when(recipe.getId()).thenReturn(
                new ResourceLocation("rsi_api_smoke", "smelt_test"));
        doReturn(RecipeType.SMELTING).when(recipe).getType();
        NonNullList<Ingredient> ingredients = NonNullList.create();
        ingredients.add(Ingredient.of(Items.RAW_IRON));
        when(recipe.getIngredients()).thenReturn(ingredients);
        when(recipe.getResultItem(RegistryAccess.EMPTY)).thenReturn(
                new ItemStack(Items.IRON_INGOT));

        IronFurnacesSmeltingIntegration integration =
                new IronFurnacesSmeltingIntegration();
        assertEquals(
                new ResourceLocation("ironfurnaces", "iron_furnace"),
                integration.machine().bindings().get(0)
                        .blockIds().get(0)
        );
        RecipeInspectionResult result = integration.recipe().inspect(
                new RecipeContext(
                        new ResourceLocation("rsi_api_smoke", "test"),
                        RegistryAccess.EMPTY,
                        1L
                ),
                recipe
        );

        RecipeView view = result.view();
        assertNotNull(view);
        assertEquals(2, view.materials().size());
        assertEquals(
                InputConsumption.Kind.FUEL,
                view.materials().get(1).consumption().kind()
        );
        assertEquals(1, view.outputs().size());
        assertEquals(
                new ResourceLocation("minecraft", "iron_ingot"),
                view.outputs().get(0).resource().id()
        );
    }

    @Test
    void ordinaryIronFurnaceProducesTickedBusyDescriptor() {
        BlockIronFurnaceTileBase furnace =
                mock(BlockIronFurnaceTileBase.class);
        when(furnace.isGenerator()).thenReturn(false);
        when(furnace.isFurnace()).thenReturn(true);
        ServerLevel level = mock(ServerLevel.class);

        IronFurnacesSmeltingIntegration integration =
                new IronFurnacesSmeltingIntegration();
        MachineDescriptorResult result = integration.machine().describe(
                new MachineContext(
                        new ResourceLocation("rsi_api_smoke", "test"),
                        level,
                        BlockPos.ZERO,
                        null
                ),
                furnace
        );

        MachineDescriptor descriptor = result.descriptor();
        assertNotNull(descriptor);
        assertEquals(
                MachineExecutionMode.TICKED_AND_BUSY,
                descriptor.executionMode()
        );
        assertEquals(3, descriptor.itemPorts().size());
        assertTrue(descriptor.itemPorts().stream()
                .allMatch(port -> port.access()
                        == ItemPortAccess.CONTAINER));
        assertTrue(integration.machine().stateProvider().isPresent());
    }
}
