package com.huanghuang.rsintegration.mods.biomancy;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.Ingredient;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class BiomancyRecipeHandlerTest extends BootstrapTest {
    public record UniformRange(int min, int max) {}
    public record ConstantValue(int value) {}
    public record BinomialRange(int n, float p) {}
    public record Output(ItemStack stack, Object range) {
        public ItemStack getItemStack() { return stack; }
        public Object getCountRange() { return range; }
    }
    public interface DecomposerOutputs {
        List<Output> getOutputs();
    }
    public record IngredientQuantity(Ingredient ingredient, int count) {}
    public interface BioLabInputs {
        List<IngredientQuantity> getIngredientQuantities();
        Ingredient getReactant();
    }

    @Test
    void bioLabMaterialReservationKeepsReactantAfterSideIngredients() {
        Recipe<?> recipe = mock(Recipe.class, withSettings().extraInterfaces(BioLabInputs.class));
        Ingredient side = Ingredient.of(Items.DIAMOND);
        Ingredient center = Ingredient.of(Items.POTION);
        when(((BioLabInputs) recipe).getIngredientQuantities()).thenReturn(
                List.of(new IngredientQuantity(side, 3)));
        when(((BioLabInputs) recipe).getReactant()).thenReturn(center);
        BiomancyRecipeHandler handler = new BiomancyRecipeHandler("biomancy_bio_lab",
                BiomancyRecipeHandler.Kind.BIO_LAB);
        List<IngredientSpec> specs = handler.getIngredients(recipe);
        assertEquals(2, specs.size());
        assertEquals(3, specs.get(0).count());
        assertTrue(specs.get(0).ingredient().test(new ItemStack(Items.DIAMOND)));
        assertEquals(1, specs.get(1).count());
        assertTrue(specs.get(1).ingredient().test(new ItemStack(Items.POTION)));
    }

    @Test
    void randomEggOutputCannotPromiseHormoneSecretion() {
        Output eggOutput = new Output(new ItemStack(Items.DIAMOND), new UniformRange(0, 1));
        assertTrue(BiomancyRecipeHandler.guaranteedOutput(eggOutput).isEmpty());
    }

    @Test
    void randomOutputUsesGuaranteedMinimumAndPreservesNbt() {
        ItemStack display = new ItemStack(Items.DIAMOND);
        display.getOrCreateTag().putString("variant", "test");
        ItemStack guaranteed = BiomancyRecipeHandler.guaranteedOutput(new Output(display, new UniformRange(4, 8)));
        assertEquals(4, guaranteed.getCount());
        assertEquals(display.getTag(), guaranteed.getTag());
        assertEquals(1, display.getCount());
    }

    @Test
    void constantOutputUsesItsRealCountInsteadOfOneDisplayItem() {
        assertEquals(6, BiomancyRecipeHandler.guaranteedOutput(
                new Output(new ItemStack(Items.DIAMOND), new ConstantValue(6))).getCount());
    }

    @Test
    void unknownAndBinomialRangesDoNotPromiseOutput() {
        assertTrue(BiomancyRecipeHandler.guaranteedOutput(
                new Output(new ItemStack(Items.DIAMOND), new BinomialRange(8, 0.5f))).isEmpty());
        assertTrue(BiomancyRecipeHandler.guaranteedOutput(
                new Output(new ItemStack(Items.DIAMOND), new Object())).isEmpty());
    }

    @Test
    void recipeChoosesGuaranteedOutputAndRejectsEntirelyRandomOutput() {
        Recipe<?> recipe = mock(Recipe.class, withSettings().extraInterfaces(DecomposerOutputs.class));
        BiomancyRecipeHandler handler = new BiomancyRecipeHandler("biomancy_decomposer",
                BiomancyRecipeHandler.Kind.DECOMPOSER);
        Output random = new Output(new ItemStack(Items.DIAMOND), new UniformRange(0, 1));
        Output guaranteed = new Output(new ItemStack(Items.EMERALD), new UniformRange(2, 4));
        when(((DecomposerOutputs) recipe).getOutputs()).thenReturn(List.of(random, guaranteed));
        ItemStack result = handler.getResultItem(recipe, RegistryAccess.EMPTY);
        assertEquals(Items.EMERALD, result.getItem());
        assertEquals(2, result.getCount());
        assertFalse(handler.hasDeterministicPrimaryOutput(recipe));
        when(((DecomposerOutputs) recipe).getOutputs()).thenReturn(List.of(random));
        assertTrue(handler.getResultItem(recipe, RegistryAccess.EMPTY).isEmpty());
    }
}
