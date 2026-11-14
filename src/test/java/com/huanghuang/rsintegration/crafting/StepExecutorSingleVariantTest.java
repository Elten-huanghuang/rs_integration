package com.huanghuang.rsintegration.crafting;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.level.Level;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class StepExecutorSingleVariantTest extends BootstrapTest {
    @BeforeAll
    static void initializeModRuntime() throws Exception {
        CommentedConfig config = CommentedConfig.inMemory();
        RSIntegrationConfig.SERVER_SPEC.correct(config);
        RSIntegrationConfig.SERVER_SPEC.setConfig(config);
        var context = mock(FMLJavaModLoadingContext.class);
        when(context.getModEventBus()).thenReturn(mock(IEventBus.class));
        try (var loading = mockStatic(FMLJavaModLoadingContext.class)) {
            loading.when(FMLJavaModLoadingContext::get).thenReturn(context);
            Class.forName("com.huanghuang.rsintegration.RSIntegrationMod");
        }
    }

    @Test
    void oneStoneKeepsAlternativeVariantsAndConsumesTheStockedMaterial() {
        Ingredient stones = Ingredient.of(Items.STONE, Items.DIORITE, Items.GRANITE);
        var context = new ResolutionContext(null, Map.of(),
                List.of(new ItemStack(Items.GRANITE)), null);
        var consumed = new ArrayList<ResolutionContext.SupplySlice>();

        Ingredient selected = StepExecutor.ensureSingleVariantMachineInput(stones, 1L,
                context, 0, new CraftingResolver.EdgeTracker(), null, consumed);

        assertSame(stones, selected);
        assertEquals(1, consumed.size());
        assertEquals(Items.GRANITE, consumed.get(0).material().item());
        assertEquals(1L, consumed.get(0).quantity());
        assertEquals(0L, context.countMatching(stones));
        assertTrue(context.missingSince(0).isEmpty());
    }

    @Test
    void multipleItemsUseStockBeforeSoftPreferredVariant() {
        Ingredient wool = Ingredient.of(Items.WHITE_WOOL, Items.GREEN_WOOL);
        var context = new ResolutionContext(null, Map.of(),
                List.of(new ItemStack(Items.GREEN_WOOL, 3)), null);
        var consumed = new ArrayList<ResolutionContext.SupplySlice>();

        Ingredient selected = StepExecutor.ensureSingleVariantMachineInput(wool, 3L,
                context, 0, new CraftingResolver.EdgeTracker(), null, consumed);

        assertTrue(selected.test(new ItemStack(Items.GREEN_WOOL)));
        assertEquals(3L, consumed.stream().mapToLong(ResolutionContext.SupplySlice::quantity).sum());
        assertTrue(context.steps.isEmpty(), "库存足量时不应递归合成偏好变体");
    }

    @Test
    void partialStockIsToppedUpBeforeProducingAnUnstockedPreferredVariant() {
        var whiteRecipe = new ShapelessRecipe(new ResourceLocation("test", "white_wool"), "",
                CraftingBookCategory.MISC, new ItemStack(Items.WHITE_WOOL),
                NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.STRING)));
        var greenRecipe = new ShapelessRecipe(new ResourceLocation("test", "green_wool"), "",
                CraftingBookCategory.MISC, new ItemStack(Items.GREEN_WOOL),
                NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.STRING)));
        var crafting = new ResourceLocation("minecraft", "crafting");
        Level level = mock(Level.class);
        when(level.registryAccess()).thenReturn(RegistryAccess.EMPTY);
        try (var index = mockStatic(RecipeIndex.class)) {
            index.when(() -> RecipeIndex.reusableCatalystRoutes(level)).thenReturn(Map.of());
            var context = new ResolutionContext(level,
                    Map.of(Items.WHITE_WOOL, List.of(new RecipeIndex.Entry(whiteRecipe, ModType.GENERIC, crafting)),
                            Items.GREEN_WOOL, List.of(new RecipeIndex.Entry(greenRecipe, ModType.GENERIC, crafting))),
                    List.of(new ItemStack(Items.GREEN_WOOL), new ItemStack(Items.STRING, 3)), null);
            var consumed = new ArrayList<ResolutionContext.SupplySlice>();

            Ingredient selected = StepExecutor.ensureSingleVariantMachineInput(
                    Ingredient.of(Items.WHITE_WOOL, Items.GREEN_WOOL), 3L,
                    context, 0, new CraftingResolver.EdgeTracker(), null, consumed);

            assertTrue(selected.test(new ItemStack(Items.GREEN_WOOL)));
            assertEquals(1, context.steps.size());
            assertEquals(greenRecipe.getId(), context.steps.get(0).recipeId());
            assertEquals(2, context.steps.get(0).executions());
            assertEquals(1L, context.countMatching(Ingredient.of(Items.STRING)), "只补两份缺口");
            assertEquals(3L, consumed.stream().mapToLong(ResolutionContext.SupplySlice::quantity).sum());
        }
    }
}
