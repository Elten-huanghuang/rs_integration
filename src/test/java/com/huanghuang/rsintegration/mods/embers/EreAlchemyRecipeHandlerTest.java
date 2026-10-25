package com.huanghuang.rsintegration.mods.embers;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.rekindled.embers.recipe.AlchemyRecipe;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class EreAlchemyRecipeHandlerTest extends BootstrapTest {

    @BeforeAll
    static void configureEnvironment() {
        CommentedConfig config = CommentedConfig.inMemory();
        RSIntegrationConfig.SERVER_SPEC.correct(config);
        RSIntegrationConfig.SERVER_SPEC.setConfig(config);
        FMLJavaModLoadingContext context = mock(FMLJavaModLoadingContext.class);
        when(context.getModEventBus()).thenReturn(mock(IEventBus.class));
        try (var loading = mockStatic(FMLJavaModLoadingContext.class)) {
            loading.when(FMLJavaModLoadingContext::get).thenReturn(context);
            assertNotNull(RSIntegrationMod.LOGGER);
        }
    }

    @Test
    void exposesRecursiveInputsAndCountedOutputForRealAlchemyRecipe() {
        ArrayList<Ingredient> aspects = new ArrayList<>();
        aspects.add(Ingredient.of(Items.COAL));
        ArrayList<Ingredient> inputs = new ArrayList<>();
        inputs.add(Ingredient.of(Items.COPPER_INGOT));
        inputs.add(Ingredient.of(Items.REDSTONE));
        inputs.add(Ingredient.of(Items.COPPER_INGOT));
        inputs.add(Ingredient.of(Items.REDSTONE));
        AlchemyRecipe recipe = new AlchemyRecipe(
                new ResourceLocation("embers", "alchemy/intelligent_apparatus"),
                Ingredient.of(Items.COPPER_INGOT),
                aspects,
                inputs,
                new ItemStack(Items.CLAY_BALL, 4),
                ItemStack.EMPTY);
        EreAlchemyRecipeHandler handler = new EreAlchemyRecipeHandler();
        EreAlchemyRSModule.INSTANCE.registerModType();

        assertTrue(handler.canHandle(recipe));
        assertSame(handler.modType(), ModType.classifyRecipe(recipe));
        ItemStack output = handler.getResultItem(recipe, RegistryAccess.EMPTY);
        assertEquals(Items.CLAY_BALL, output.getItem());
        assertEquals(4, output.getCount());
        List<IngredientSpec> specs = handler.getIngredients(recipe);
        assertNotNull(specs);
        assertEquals(5, specs.size());
    }
}
