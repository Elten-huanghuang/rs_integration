package com.huanghuang.rsintegration.mods.malum;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.ShapedRecipe;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MalumSpiritCrucibleRecipeRefreshTest extends BootstrapTest {
    @Test
    void refreshInvokesInitAndAcceptsTheExpectedRecipeId() {
        Recipe<?> expected = recipe("neutron_nugget");
        FakeCrucible crucible = new FakeCrucible(recipe("neutron_nugget"));

        assertTrue(MalumSpiritCrucibleBatchDelegate.refreshRecipeSelection(crucible, expected));
        assertTrue(crucible.initialized);
    }

    @Test
    void refreshRejectsNoMatchInsteadOfWaitingForTimeout() {
        Recipe<?> expected = recipe("neutron_nugget");

        assertFalse(MalumSpiritCrucibleBatchDelegate.refreshRecipeSelection(
                new FakeCrucible(null), expected));
        assertFalse(MalumSpiritCrucibleBatchDelegate.refreshRecipeSelection(
                new FakeCrucible(recipe("different")), expected));
    }

    @Test
    void refreshesNearbyAcceleratorsAfterProgrammaticPlacement() {
        FakeCrucible crucible = new FakeCrucible(recipe("neutron_nugget"));

        assertTrue(MalumSpiritCrucibleBatchDelegate.refreshAccelerators(
                crucible, new Object(), new Object()));
        assertTrue(crucible.acceleratorsRefreshed);
    }

    @Test
    void missingAcceleratorApiRemainsCompatible() {
        assertFalse(MalumSpiritCrucibleBatchDelegate.refreshAccelerators(
                new Object(), new Object(), new Object()));
    }

    private static ShapedRecipe recipe(String path) {
        return new ShapedRecipe(new ResourceLocation("test", path), "",
                CraftingBookCategory.MISC, 1, 1,
                NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.IRON_INGOT)),
                new ItemStack(Items.IRON_NUGGET));
    }

    public static final class FakeCrucible {
        public Recipe<?> recipe;
        private final Recipe<?> selected;
        private boolean initialized;
        private boolean acceleratorsRefreshed;

        private FakeCrucible(Recipe<?> selected) {
            this.selected = selected;
        }

        public void init() {
            initialized = true;
            recipe = selected;
        }

        public void recalibrateAccelerators(Object level, Object pos) {
            acceleratorsRefreshed = level != null && pos != null;
        }
    }
}
