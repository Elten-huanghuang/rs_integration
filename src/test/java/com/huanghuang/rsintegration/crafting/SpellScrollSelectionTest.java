package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.mods.ironsspellbooks.IronSpellBooksRecipeCatalog;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpellScrollSelectionTest extends BootstrapTest {
    @Test
    void lowerLevelSortsBeforeSpellIdAndMissingMetadata() {
        var levelOne = new IronSpellBooksRecipeCatalog.SpellScrollKey(
                new ResourceLocation("test", "z_spell"), 1);
        var levelTwo = new IronSpellBooksRecipeCatalog.SpellScrollKey(
                new ResourceLocation("test", "a_spell"), 2);

        assertTrue(SpellScrollSelection.compareKeys(levelOne, levelTwo) < 0);
        assertTrue(SpellScrollSelection.compareKeys(levelOne, null) < 0);
    }

    @Test
    void equalLevelsUseStableSpellIdOrder() {
        var first = new IronSpellBooksRecipeCatalog.SpellScrollKey(
                new ResourceLocation("test", "a_spell"), 1);
        var second = new IronSpellBooksRecipeCatalog.SpellScrollKey(
                new ResourceLocation("test", "b_spell"), 1);

        assertTrue(SpellScrollSelection.compareKeys(first, second) < 0);
    }

    @Test
    void commonRaritySortsBeforeLowerLevelUncommonScroll() {
        assertTrue(SpellScrollSelection.compareCost(0, 2, 1, 1) < 0);
    }

    @Test
    void ordinaryIngredientsNeverEnableScrollPolicy() {
        assertFalse(SpellScrollSelection.acceptsAnyScroll(Ingredient.of(Items.PAPER)));
    }
}
