package com.example.rsiintegration.apismoke;

import com.huanghuang.rsintegration.api.recipe.RecipeContext;
import com.huanghuang.rsintegration.api.recipe.RecipeInspectionResult;
import com.huanghuang.rsintegration.api.recipe.RecipeView;
import com.huanghuang.rsintegration.api.machine.ItemPortAccess;
import com.huanghuang.rsintegration.api.machine.MachineContext;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.items.ItemStackHandler;
import org.junit.jupiter.api.Test;
import vectorwing.farmersdelight.common.block.entity.CookingPotBlockEntity;
import vectorwing.farmersdelight.common.crafting.CookingPotRecipe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FarmersDelightCookingPotIntegrationTest extends BootstrapTest {
    @Test
    void realCookingPotRecipeProjectsIntoPublicRecipeView() {
        CookingPotRecipe recipe = recipe();
        FarmersDelightCookingPotIntegration integration =
                new FarmersDelightCookingPotIntegration();
        assertEquals(
                new ResourceLocation("farmersdelight", "cooking_pot"),
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
        assertEquals(recipe.getId(), view.recipeId());
        assertEquals(3, view.materials().size());
        assertEquals(1, view.outputs().size());
        assertEquals(Items.MUSHROOM_STEW, BuiltInRegistries.ITEM.get(
                view.outputs().get(0).resource().id()));
        assertTrue(view.flags().orderedInputs());
        assertTrue(view.flags().deterministicOutput());
    }

    @Test
    void cookingPotPortsUseInternalInventorySlots() {
        FarmersDelightCookingPotIntegration integration =
                new FarmersDelightCookingPotIntegration();
        CookingPotBlockEntity pot = mock(CookingPotBlockEntity.class);
        var descriptor = integration.machine().describe(
                new MachineContext(
                        new ResourceLocation("rsi_api_smoke", "test"),
                        mock(ServerLevel.class),
                        BlockPos.ZERO,
                        null
                ),
                pot
        ).descriptor();

        assertNotNull(descriptor);
        assertTrue(descriptor.itemPorts().stream()
                .allMatch(port -> port.access()
                        == ItemPortAccess.MACHINE_INVENTORY));
        assertTrue(descriptor.itemPorts().stream()
                .allMatch(port -> port.side() == null));

        ItemStackHandler inventory = mock(ItemStackHandler.class);
        when(pot.getInventory()).thenReturn(inventory);
        assertTrue(integration.machine().internalItemHandler(
                new MachineContext(
                        new ResourceLocation("rsi_api_smoke", "test"),
                        mock(ServerLevel.class),
                        BlockPos.ZERO,
                        null
                ),
                pot
        ).isPresent());
    }

    private static CookingPotRecipe recipe() {
        NonNullList<Ingredient> ingredients = NonNullList.create();
        ingredients.add(Ingredient.of(Items.BEEF));
        ingredients.add(Ingredient.of(Items.CARROT));
        return new CookingPotRecipe(
                new ResourceLocation("farmersdelight", "test_stew"),
                "",
                null,
                ingredients,
                new ItemStack(Items.MUSHROOM_STEW),
                new ItemStack(Items.BOWL),
                0.35F,
                200
        );
    }
}
