package com.huanghuang.rsintegration.crafting;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.CraftingResolver.EdgeTracker;
import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.planning.PlanningPreferences;
import com.huanghuang.rsintegration.mods.embers.EreAlchemyRSModule;
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.huanghuang.rsintegration.util.ModIds;
import com.rekindled.embers.recipe.AlchemyRecipe;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.Level;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class TypedSelfAmplificationTest extends BootstrapTest {

    @BeforeAll
    static void loadConfig() {
        CommentedConfig config = CommentedConfig.inMemory();
        RSIntegrationConfig.SERVER_SPEC.correct(config);
        RSIntegrationConfig.SERVER_SPEC.setConfig(config);
        FMLJavaModLoadingContext context = mock(FMLJavaModLoadingContext.class);
        when(context.getModEventBus()).thenReturn(mock(IEventBus.class));
        try (MockedStatic<FMLJavaModLoadingContext> loading =
                     mockStatic(FMLJavaModLoadingContext.class)) {
            loading.when(FMLJavaModLoadingContext::get).thenReturn(context);
            RSIntegrationMod.MOD_BUS.getClass();
        }
        EreAlchemyRSModule.INSTANCE.registerModType();
    }

    @Test
    void oneSeedCanSatisfyMultipleTypedEngineBatches() {
        ArrayList<Ingredient> inputs = new ArrayList<>();
        inputs.add(Ingredient.of(Items.SOUL_SAND));
        inputs.add(Ingredient.of(Items.CLAY_BALL));
        AlchemyRecipe recipe = new AlchemyRecipe(
                new ResourceLocation("embers", "alchemy/test_amplification"),
                Ingredient.of(Items.DIAMOND),
                new ArrayList<>(List.of(Ingredient.of(Items.COAL))),
                inputs,
                new ItemStack(Items.DIAMOND, 5),
                ItemStack.EMPTY);
        RecipeIndex.Entry entry = new RecipeIndex.Entry(
                recipe, ModType.byId(ModIds.ID_EMBERS_ALCHEMY),
                new ResourceLocation("embers", "alchemy"), false);

        Level level = mock(Level.class);
        when(level.registryAccess()).thenReturn(RegistryAccess.EMPTY);
        ServerPlayer player = mock(ServerPlayer.class);
        ArrayList<String> missing = new ArrayList<>();

        try (MockedStatic<AltarBindingRegistry> bindings =
                     mockStatic(AltarBindingRegistry.class);
             MockedStatic<RecipeIndex> recipeIndex =
                     mockStatic(RecipeIndex.class)) {
            recipeIndex.when(() -> RecipeIndex.reusableCatalystRoutes(any()))
                    .thenReturn(Map.of());
            ResolutionContext context = new ResolutionContext(
                    level, Map.of(Items.DIAMOND, List.of(entry)),
                    Map.of(new StackKey(Items.DIAMOND, null), 1L,
                            new StackKey(Items.SOUL_SAND, null), 8L,
                            new StackKey(Items.CLAY_BALL, null), 8L),
                    null, null, player, null, false, missing,
                    Long.MAX_VALUE, false)
                    .withPreferences(PlanningPreferences.empty());
            bindings.when(() -> AltarBindingRegistry.hasBindingForRecipe(
                    any(), any(), any())).thenReturn(true);
            assertTrue(CraftingResolver.ensureIngredient(
                    Ingredient.of(Items.DIAMOND), 8, context, 0, new EdgeTracker()), missing.toString());
        }
    }

    @Test
    void oneSeedCanFeedTwoTypedSiblingBranches() {
        AlchemyRecipe brickRecipe = new AlchemyRecipe(
                new ResourceLocation("embers", "alchemy/test_brick"),
                Ingredient.of(Items.DIAMOND),
                new ArrayList<>(List.of(Ingredient.of(Items.COAL))),
                new ArrayList<>(List.of(
                        Ingredient.of(Items.SOUL_SAND), Ingredient.of(Items.CLAY_BALL))),
                new ItemStack(Items.DIAMOND, 5),
                ItemStack.EMPTY);
        CraftingRecipe circuitRecipe = mock(CraftingRecipe.class);
        when(circuitRecipe.getId())
                .thenReturn(new ResourceLocation("test", "circuit"));
        when(circuitRecipe.getIngredients()).thenReturn(NonNullList.of(
                Ingredient.EMPTY, Ingredient.of(Items.DIAMOND)));
        when(circuitRecipe.getRemainingItems(any())).thenReturn(NonNullList.create());
        when(circuitRecipe.getResultItem(any()))
                .thenReturn(new ItemStack(Items.REDSTONE));
        AlchemyRecipe apparatusRecipe = new AlchemyRecipe(
                new ResourceLocation("embers", "alchemy/test_apparatus"),
                Ingredient.of(Items.IRON_INGOT),
                new ArrayList<>(List.of(Ingredient.of(Items.COAL))),
                new ArrayList<>(List.of(
                        Ingredient.of(Items.GOLD_INGOT),
                        Ingredient.of(Items.REDSTONE),
                        Ingredient.of(Items.GOLD_INGOT),
                        Ingredient.of(Items.REDSTONE))),
                new ItemStack(Items.EMERALD),
                ItemStack.EMPTY);
        RecipeIndex.Entry brickEntry = new RecipeIndex.Entry(
                brickRecipe, ModType.byId(ModIds.ID_EMBERS_ALCHEMY),
                new ResourceLocation("embers", "alchemy"), false);
        RecipeIndex.Entry circuitEntry = new RecipeIndex.Entry(
                circuitRecipe, ModType.GENERIC,
                new ResourceLocation("minecraft", "crafting"), false);
        RecipeIndex.Entry apparatusEntry = new RecipeIndex.Entry(
                apparatusRecipe, ModType.byId(ModIds.ID_EMBERS_ALCHEMY),
                new ResourceLocation("embers", "alchemy"), false);

        Level level = mock(Level.class);
        when(level.registryAccess()).thenReturn(RegistryAccess.EMPTY);
        ServerPlayer player = mock(ServerPlayer.class);
        ArrayList<String> missing = new ArrayList<>();
        try (MockedStatic<AltarBindingRegistry> bindings =
                     mockStatic(AltarBindingRegistry.class);
             MockedStatic<RecipeIndex> recipeIndex =
                     mockStatic(RecipeIndex.class)) {
            recipeIndex.when(() -> RecipeIndex.reusableCatalystRoutes(any()))
                    .thenReturn(Map.of());
            bindings.when(() -> AltarBindingRegistry.hasBindingForRecipe(
                    any(), any(), any())).thenReturn(true);
            ResolutionContext context = new ResolutionContext(
                    level, Map.of(
                            Items.DIAMOND, List.of(brickEntry),
                            Items.REDSTONE, List.of(circuitEntry),
                            Items.EMERALD, List.of(apparatusEntry)),
                    Map.of(new StackKey(Items.DIAMOND, null), 1L,
                            new StackKey(Items.SOUL_SAND, null), 8L,
                            new StackKey(Items.CLAY_BALL, null), 16L,
                            new StackKey(Items.IRON_INGOT, null), 1L,
                            new StackKey(Items.GOLD_INGOT, null), 2L),
                    null, null, player, null, false, missing,
                    Long.MAX_VALUE, false)
                    .withPreferences(PlanningPreferences.empty());

            assertTrue(CraftingResolver.ensureIngredient(
                    Ingredient.of(Items.EMERALD), 1, context, 0, new EdgeTracker()), missing.toString());
        }
    }
}
