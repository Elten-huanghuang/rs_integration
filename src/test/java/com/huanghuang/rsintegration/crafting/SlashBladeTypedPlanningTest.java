package com.huanghuang.rsintegration.crafting;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.mods.slashblade.SlashBladeRSModule;
import com.huanghuang.rsintegration.mods.slashblade.SlashBladeRecipeHandler;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.level.Level;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Map;

import static com.huanghuang.rsintegration.testutil.SlashBladeFixtures.blade;
import static com.huanghuang.rsintegration.testutil.SlashBladeFixtures.ingredient;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SlashBladeTypedPlanningTest extends BootstrapTest {
    @BeforeAll
    static void configureEnvironment() throws Exception {
        CommentedConfig config = CommentedConfig.inMemory();
        RSIntegrationConfig.SERVER_SPEC.correct(config);
        RSIntegrationConfig.SERVER_SPEC.setConfig(config);
        FMLJavaModLoadingContext context = mock(FMLJavaModLoadingContext.class);
        when(context.getModEventBus()).thenReturn(mock(IEventBus.class));
        try (MockedStatic<FMLJavaModLoadingContext> loading = mockStatic(FMLJavaModLoadingContext.class)) {
            loading.when(FMLJavaModLoadingContext::get).thenReturn(context);
            Class.forName("com.huanghuang.rsintegration.RSIntegrationMod");
        }
        SlashBladeRSModule.INSTANCE.registerModType();
    }

    @Test
    void candidatePlanningAndStepReservationKeepTheRaisedThreshold() {
        Level level = mock(Level.class);
        when(level.registryAccess()).thenReturn(RegistryAccess.EMPTY);
        Ingredient base = ingredient("test:base", 800, 8);
        Ingredient demand = ingredient("test:result", 1000, 10);
        ItemStack declared = blade("test:result", 0, 0);
        ShapedRecipe recipe = new ShapedRecipe(new ResourceLocation("test:upgrade"), "",
                CraftingBookCategory.MISC, 1, 1, NonNullList.of(Ingredient.EMPTY, base), declared);
        var entry = new RecipeIndex.Entry(recipe, ModType.byId("slashblade"),
                new ResourceLocation("minecraft:crafting"));
        var weak = CraftingResolver.StackKey.of(blade("test:base", 900, 9), true);
        var strong = CraftingResolver.StackKey.of(blade("test:base", 1200, 12), true);

        // 只替代第三方配方类型识别和运行环境；候选计算、搜索、预留与步骤生成走真实代码。
        try (MockedStatic<RecipeIndex> index = mockStatic(RecipeIndex.class);
             MockedStatic<SlashBladeRecipeHandler> blades = mockStatic(SlashBladeRecipeHandler.class, CALLS_REAL_METHODS);
             MockedStatic<ModRecipeHandlers> handlers = mockStatic(ModRecipeHandlers.class)) {
            index.when(() -> RecipeIndex.reusableCatalystRoutes(level)).thenReturn(Map.of());
            blades.when(() -> SlashBladeRecipeHandler.isShapedBladeRecipe(recipe)).thenReturn(true);
            SlashBladeRecipeHandler handler = new SlashBladeRecipeHandler();
            handlers.when(() -> ModRecipeHandlers.claimFor(recipe))
                    .thenReturn(new ModRecipeHandlers.Claim(handler, List.of(handler)));
            handlers.when(() -> ModRecipeHandlers.tryGetResultItem(recipe, RegistryAccess.EMPTY)).thenReturn(declared);
            ResolutionContext context = new ResolutionContext(level, Map.of(), Map.of(weak, 1, strong, 1),
                    null, false, null, Long.MAX_VALUE, false);
            ItemStack candidate = CandidateEngine.outputForDemand(entry, demand, context);
            assertTrue(IngredientMatcher.test(demand, candidate));
            assertTrue(StepExecutor.craftBatched(entry, context, 0, List.of(), List.of(),
                    new CraftingResolver.EdgeTracker(), 1, candidate));
            assertEquals(1L, context.counts.getOrDefault(weak, 0L));
            assertEquals(0L, context.counts.getOrDefault(strong, 0L));
            assertEquals(1, context.steps.size());
            assertNotNull(context.steps.get(0).syntheticOutput());
            assertTrue(IngredientMatcher.test(demand, context.steps.get(0).syntheticOutput()));

            ResolutionContext insufficient = new ResolutionContext(level, Map.of(), Map.of(weak, 1),
                    null, false, null, Long.MAX_VALUE, false);
            assertFalse(StepExecutor.craftBatched(entry, insufficient, 0, List.of(), List.of(),
                    new CraftingResolver.EdgeTracker(), 1, candidate));
            assertTrue(insufficient.steps.isEmpty());
            assertEquals(1L, insufficient.counts.getOrDefault(weak, 0L));
        }
    }
}
