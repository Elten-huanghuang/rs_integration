package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.ModType;
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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeIndexProjectionPolicyTest extends BootstrapTest {
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
            @Override public List<IngredientSpec> getRecursiveIngredients(
                    Recipe<?> ignored, List<IngredientSpec> ingredients) {
                return List.of();
            }
        };

        assertFalse(handler.supportsBackgroundPlanning(recipe));
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
