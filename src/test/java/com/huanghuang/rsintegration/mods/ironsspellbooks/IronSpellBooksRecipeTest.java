package com.huanghuang.rsintegration.mods.ironsspellbooks;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class IronSpellBooksRecipeTest extends BootstrapTest {
    @Test
    void retainsExplicitScrollLevelForExecutionValidation() {
        IronSpellBooksRecipe recipe = new IronSpellBooksRecipe(
                new ResourceLocation("test", "scroll"),
                IronSpellBooksRecipe.Machine.SCROLL_FORGE,
                List.of(new ItemStack(Items.PAPER)),
                List.of(Ingredient.of(Items.PAPER)),
                new ItemStack(Items.PAPER), "test:spell", 2);

        assertEquals(2, recipe.spellLevel());
    }

    @Test
    void readsLegacySpellDataShapeWithoutAConcreteElementCast() {
        assertEquals(new IronSpellBooksRecipeCatalog.SpellScrollKey(
                        new ResourceLocation("test", "legacy_spell"), 3),
                IronSpellBooksRecipeCatalog.spellEntryKey(
                        new LegacySpellData(new TestSpell("test:legacy_spell"), 3)));
    }

    @Test
    void readsModernSpellSlotShapeWithoutLinkingTheNewClass() {
        assertEquals(new IronSpellBooksRecipeCatalog.SpellScrollKey(
                        new ResourceLocation("test", "modern_spell"), 7),
                IronSpellBooksRecipeCatalog.spellEntryKey(
                        new ModernSpellSlot(new TestSpell("test:modern_spell"), 7, 0)));
    }

    @Test
    void rejectsMalformedSpellEntriesConservatively() {
        assertNull(IronSpellBooksRecipeCatalog.spellEntryKey(new Object()));
        assertNull(IronSpellBooksRecipeCatalog.spellEntryKey(
                new LegacySpellData(new TestSpell("not a resource id"), 2)));
    }

    public record TestSpell(String getSpellId) { }

    public record LegacySpellData(TestSpell getSpell, int getLevel) { }

    public record ModernSpellSlot(TestSpell getSpell, int getLevel, int index) { }
}
