package com.huanghuang.rsintegration.mods.ironsspellbooks;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.items.ItemStackHandler;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IronSpellBooksRecipeTest extends BootstrapTest {
    @Test
    void scrollForgeUsesVersionIndependentValidatedOutput() {
        assertTrue(IronSpellBooksBatchDelegate.usesDeterministicScrollOutput(
                IronSpellBooksRecipe.Machine.SCROLL_FORGE));
        assertFalse(IronSpellBooksBatchDelegate.usesDeterministicScrollOutput(
                IronSpellBooksRecipe.Machine.ARCANE_ANVIL));
    }

    @Test
    void scrollForgeEvacuationReturnsInputsButDiscardsDerivedPreview() {
        ItemStackHandler handler = new ItemStackHandler(4);
        handler.setStackInSlot(0, new ItemStack(Items.INK_SAC, 2));
        handler.setStackInSlot(1, new ItemStack(Items.PAPER, 3));
        handler.setStackInSlot(2, new ItemStack(Items.AMETHYST_SHARD));
        handler.setStackInSlot(3, new ItemStack(Items.MAP));
        List<ItemStack> returned = new ArrayList<>();

        int returnedCount = IronSpellBooksBatchDelegate.evacuateScrollForgeInventory(
                handler, returned::add);

        assertEquals(6, returnedCount);
        assertEquals(3, returned.size());
        assertEquals(Items.INK_SAC, returned.get(0).getItem());
        assertEquals(Items.PAPER, returned.get(1).getItem());
        assertEquals(Items.AMETHYST_SHARD, returned.get(2).getItem());
        assertTrue(handler.getStackInSlot(0).isEmpty());
        assertTrue(handler.getStackInSlot(1).isEmpty());
        assertTrue(handler.getStackInSlot(2).isEmpty());
        assertTrue(handler.getStackInSlot(3).isEmpty());
    }

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
