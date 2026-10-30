package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.batch.GenericBatchDelegate;
import com.huanghuang.rsintegration.mods.farmersdelight.CuttingBoardRecipeHandler;
import com.huanghuang.rsintegration.recipe.AbstractRecipeHandler;
import com.huanghuang.rsintegration.recipe.ModRecipeHandler;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import vectorwing.farmersdelight.common.crafting.CuttingBoardRecipe;
import vectorwing.farmersdelight.common.crafting.ingredient.ChanceResult;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeIndexProjectionPolicyTest extends BootstrapTest {
    @ParameterizedTest
    @CsvSource({"1.0, 1.0, true", "1.0, 0.35, true",
            "0.35, 1.0, false", "0.35, 0.35, false"})
    void cuttingBoardProjectionUsesGuaranteedPrimaryRatherThanRecipeWideRandomness(
            float primaryChance, float secondaryChance, boolean projectable) {
        CuttingBoardRecipe recipe = new CuttingBoardRecipe(
                new ResourceLocation("test", "cutting_projection"), "",
                Ingredient.of(Items.MELON), Ingredient.of(Items.IRON_SWORD),
                NonNullList.of(ChanceResult.EMPTY,
                        new ChanceResult(new ItemStack(Items.MELON_SLICE, 4), primaryChance),
                        new ChanceResult(new ItemStack(Items.MELON_SEEDS), secondaryChance)), "");
        ModType conditional = ModType.register("testutil_cutting_projection",
                        new String[0], new String[0], new String[0],
                        GenericBatchDelegate::new)
                .allowDeterministicProjection("砧板按主产物的实际概率放行");

        assertEquals(projectable, RecipeIndex.isTypedPureProjectionCandidate(
                new CuttingBoardRecipeHandler(), conditional, recipe));
    }

    @Test
    void prefixHandlersUseTheirDeclaredValueOnlyContractForProjection() {
        Recipe<?> recipe = new ShapelessRecipe(
                new ResourceLocation("test", "prefix_projection"), "",
                CraftingBookCategory.MISC, new ItemStack(Items.DIAMOND),
                NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.IRON_INGOT)));

        assertTrue(new PrefixHandler(true, false, true)
                .supportsBackgroundPlanning(recipe));
        assertFalse(new PrefixHandler(false, false, true)
                .supportsBackgroundPlanning(recipe));
        assertTrue(new PrefixHandler(true, true, true)
                .supportsBackgroundPlanning(recipe));
        assertFalse(new PrefixHandler(true, false, false)
                .supportsBackgroundPlanning(recipe));
    }

    @Test
    void soulBladeTransformPassesCatalogPolicyAndKeepsPositionalBaseInProjection() {
        var recipe = new net.minecraft.world.item.crafting.SmithingTransformRecipe(
                new ResourceLocation("callfromthedepth_:soulblade"),
                Ingredient.of(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE),
                Ingredient.of(Items.DIAMOND_SWORD), Ingredient.of(Items.NETHERITE_INGOT),
                new ItemStack(Items.NETHERITE_SWORD));
        var handler = new com.huanghuang.rsintegration.mods.vanilla.SmithingRecipeHandler();
        assertTrue(RecipeIndex.isTypedPureProjectionCandidate(handler, handler.modType(), recipe));
        var projected = com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraphProjector.projectRecipe(
                recipe.getId(), recipe.getResultItem(RegistryAccess.EMPTY), handler.getIngredients(recipe),
                handler.modType().id(), new ResourceLocation("minecraft:smithing"),
                !handler.hasRuntimeDependentPrimaryNbt(recipe));
        org.junit.jupiter.api.Assertions.assertNotNull(projected);
        org.junit.jupiter.api.Assertions.assertEquals(recipe.getId(), projected.recipeId());
        org.junit.jupiter.api.Assertions.assertEquals(new ResourceLocation("minecraft:diamond_sword"),
                projected.inputs().get(1).alternatives().get(0).itemId());
        assertTrue(projected.output().nbt().isEmpty());
    }
    @Test
    void onlyDeterministicGraphSafeTypesEnterTypedPureGraph() {
        Recipe<?> recipe = new ShapelessRecipe(
                new ResourceLocation("test", "typed_projection"), "",
                CraftingBookCategory.MISC, new ItemStack(Items.DIAMOND),
                NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.IRON_INGOT)));
        ModRecipeHandler deterministic = handler(true);
        ModRecipeHandler probabilistic = handler(false);

        assertTrue(RecipeIndex.isTypedPureProjectionCandidate(deterministic,
                ModType.FARMINGFORBLOCKHEADS_MARKET, recipe));
        assertFalse(RecipeIndex.isTypedPureProjectionCandidate(probabilistic,
                ModType.FARMINGFORBLOCKHEADS_MARKET, recipe));
        assertFalse(RecipeIndex.isTypedPureProjectionCandidate(deterministic,
                ModType.CUSTOM_GUI, recipe));
    }

    @Test
    void conditionalTypeAdmitsOnlyDeterministicRecipes() {
        Recipe<?> recipe = new ShapelessRecipe(
                new ResourceLocation("test", "conditional_projection"), "",
                CraftingBookCategory.MISC, new ItemStack(Items.DIAMOND),
                NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.IRON_INGOT)));
        ModType conditional = ModType.register("testutil_projection_conditional",
                        new String[0], new String[0], new String[0],
                        com.huanghuang.rsintegration.crafting.batch.GenericBatchDelegate::new)
                .allowDeterministicProjection("默认排除，确定性配方逐条放行");

        assertTrue(RecipeIndex.allowsPureProjection(
                randomizedHandler(conditional, true, false, false), conditional, recipe));
        assertFalse(RecipeIndex.allowsPureProjection(
                randomizedHandler(conditional, true, true, false), conditional, recipe));
        // 主产物本身不确定时，同样不能进纯图。
        assertFalse(RecipeIndex.allowsPureProjection(
                randomizedHandler(conditional, false, false, false), conditional, recipe));
    }

    @Test
    void unsupportedTypeStillNeedsExplicitPerRecipeOptIn() {
        Recipe<?> recipe = new ShapelessRecipe(
                new ResourceLocation("test", "rejected_projection"), "",
                CraftingBookCategory.MISC, new ItemStack(Items.DIAMOND),
                NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.IRON_INGOT)));
        ModType rejected = ModType.register("testutil_projection_rejected",
                        new String[0], new String[0], new String[0],
                        com.huanghuang.rsintegration.crafting.batch.GenericBatchDelegate::new)
                .rejectPureProjection("整体不允许纯图投影");

        assertFalse(RecipeIndex.allowsPureProjection(
                randomizedHandler(rejected, true, false, false), rejected, recipe));
        assertTrue(RecipeIndex.allowsPureProjection(
                randomizedHandler(rejected, true, false, true), rejected, recipe));
    }

    @Test
    void flatExecutorCanOptIntoIntermediateProjection() {
        Recipe<?> recipe = new ShapelessRecipe(
                new ResourceLocation("test", "flat_intermediate_projection"), "",
                CraftingBookCategory.MISC, new ItemStack(Items.DIAMOND),
                NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.IRON_INGOT)));
        ModRecipeHandler handler = new ModRecipeHandler() {
            @Override public ModType modType() { return ModType.CUSTOM_GUI; }
            @Override public boolean canHandle(Recipe<?> ignored) { return true; }
            @Override public ItemStack getResultItem(Recipe<?> ignored, RegistryAccess access) {
                return new ItemStack(Items.DIAMOND);
            }
            @Override public List<IngredientSpec> getIngredients(Recipe<?> ignored) {
                return List.of(new IngredientSpec(Ingredient.of(Items.IRON_INGOT), 1));
            }
            @Override public boolean supportsIntermediateProjection(Recipe<?> ignored) {
                return true;
            }
        };

        assertTrue(RecipeIndex.isTypedPureProjectionCandidate(handler,
                ModType.CUSTOM_GUI, recipe));
    }

    @Test
    void backgroundPlanningUsesRecursiveIngredientsInsteadOfRuntimeResources() {
        Recipe<?> recipe = new ShapelessRecipe(
                new ResourceLocation("test", "runtime_only_resource"), "",
                CraftingBookCategory.MISC, new ItemStack(Items.DIAMOND),
                NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.IRON_INGOT)));
        ModRecipeHandler handler = new ModRecipeHandler() {
            @Override public ModType modType() { return ModType.FARMINGFORBLOCKHEADS_MARKET; }
            @Override public boolean canHandle(Recipe<?> ignored) { return true; }
            @Override public ItemStack getResultItem(Recipe<?> ignored, RegistryAccess access) {
                return new ItemStack(Items.DIAMOND);
            }
            @Override public List<IngredientSpec> getIngredients(Recipe<?> ignored) {
                return List.of(new IngredientSpec(Ingredient.of(Items.IRON_SWORD), 1));
            }
            @Override public List<ScaledIngredientSpec> getRecursiveScaledIngredients(
                    Recipe<?> ignored, List<ScaledIngredientSpec> ingredients) {
                return List.of();
            }
        };

        assertFalse(handler.supportsBackgroundPlanning(recipe));
    }

    /** 测试处理器：同时声明主产物确定性、随机产物与显式放行，用来驱动统一放行判定。 */
    private static ModRecipeHandler randomizedHandler(ModType type, boolean deterministicPrimary,
                                                      boolean randomized, boolean explicitOptIn) {
        return new ModRecipeHandler() {
            @Override public ModType modType() { return type; }
            @Override public boolean canHandle(Recipe<?> recipe) { return true; }
            @Override public ItemStack getResultItem(Recipe<?> recipe, RegistryAccess access) {
                return new ItemStack(Items.DIAMOND);
            }
            @Override public List<IngredientSpec> getIngredients(Recipe<?> recipe) {
                return List.of(new IngredientSpec(Ingredient.of(Items.IRON_INGOT), 1));
            }
            @Override public boolean hasDeterministicPrimaryOutput(Recipe<?> recipe) {
                return deterministicPrimary;
            }
            @Override public boolean hasRandomizedOutputs(Recipe<?> recipe) {
                return randomized;
            }
            @Override public boolean supportsIntermediateProjection(Recipe<?> recipe) {
                return explicitOptIn;
            }
        };
    }

    private static ModRecipeHandler handler(boolean deterministic) {
        return new ModRecipeHandler() {
            @Override public ModType modType() {
                return ModType.FARMINGFORBLOCKHEADS_MARKET;
            }

            @Override public boolean canHandle(Recipe<?> recipe) {
                return true;
            }

            @Override public ItemStack getResultItem(Recipe<?> recipe, RegistryAccess access) {
                return new ItemStack(Items.DIAMOND);
            }

            @Override public List<IngredientSpec> getIngredients(Recipe<?> recipe) {
                return List.of(new IngredientSpec(Ingredient.of(Items.IRON_INGOT), 1));
            }

            @Override public boolean hasDeterministicPrimaryOutput(Recipe<?> recipe) {
                return deterministic;
            }
        };
    }

    private static final class PrefixHandler extends AbstractRecipeHandler {
        private final boolean deterministic;
        private final boolean runtimeNbt;
        private final boolean ingredientsAvailable;

        private PrefixHandler(boolean deterministic, boolean runtimeNbt,
                              boolean ingredientsAvailable) {
            this.deterministic = deterministic;
            this.runtimeNbt = runtimeNbt;
            this.ingredientsAvailable = ingredientsAvailable;
        }

        @Override public ModType modType() {
            return ModType.FARMINGFORBLOCKHEADS_MARKET;
        }

        @Override public boolean canHandle(Recipe<?> recipe) {
            return true;
        }

        @Override public ItemStack getResultItem(Recipe<?> recipe, RegistryAccess access) {
            return new ItemStack(Items.DIAMOND);
        }

        @Override public List<IngredientSpec> getIngredients(Recipe<?> recipe) {
            return ingredientsAvailable
                    ? List.of(new IngredientSpec(Ingredient.of(Items.IRON_INGOT), 1))
                    : null;
        }

        @Override public boolean hasDeterministicPrimaryOutput(Recipe<?> recipe) {
            return deterministic;
        }

        @Override public boolean hasRuntimeDependentPrimaryNbt(Recipe<?> recipe) {
            return runtimeNbt;
        }
    }
}
