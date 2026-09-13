package com.huanghuang.rsintegration.mods.farmersdelight;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArcaneCookingPotCompatibilityTest extends BootstrapTest {
    private static final String ARCANE_COOKED = "IronsSpellsDelightArcaneCooked";

    @Test
    void arcaneCookedMarkerDoesNotBlockCompletion() {
        ItemStack expected = new ItemStack(Items.APPLE);
        ItemStack actual = expected.copy();
        actual.getOrCreateTag().putBoolean(ARCANE_COOKED, true);

        assertTrue(CookingPotBatchDelegate.matchesRecipeOutputStack(actual, expected, true));
        assertFalse(CookingPotBatchDelegate.matchesRecipeOutputStack(actual, expected, false));
        assertTrue(actual.getTag().getBoolean(ARCANE_COOKED),
                "completion matching must not strip the marker from the real output");
    }

    @Test
    void arcaneMatchingStillRejectsUnrelatedNbtAndWrongCounts() {
        ItemStack expected = new ItemStack(Items.APPLE, 2);
        expected.getOrCreateTag().putString("recipe_data", "expected");

        ItemStack matching = expected.copy();
        matching.getOrCreateTag().putBoolean(ARCANE_COOKED, true);
        assertTrue(CookingPotBatchDelegate.matchesRecipeOutputStack(matching, expected, true));

        ItemStack unrelated = matching.copy();
        unrelated.getOrCreateTag().putString("recipe_data", "different");
        assertFalse(CookingPotBatchDelegate.matchesRecipeOutputStack(unrelated, expected, true));

        ItemStack tooFew = matching.copyWithCount(1);
        assertFalse(CookingPotBatchDelegate.matchesRecipeOutputStack(tooFew, expected, true));
    }
}
