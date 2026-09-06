package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.crafting.graph.MaterialKey;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaterialMatcherTest extends BootstrapTest {
    @Test
    void largeExternalStockSaturatesInsteadOfWrappingNegative() {
        assertEquals(Integer.MAX_VALUE,
                MaterialSources.saturatedAdd(Integer.MAX_VALUE - 10, 100));
        assertEquals(Integer.MAX_VALUE,
                MaterialSources.saturatedAdd(Integer.MAX_VALUE, 1));
        assertEquals(42, MaterialSources.saturatedAdd(40, 2));
    }

    @Test
    void semanticAndExactModesRemainDistinct() {
        ItemStack first = tagged("one");
        ItemStack second = tagged("two");

        assertTrue(MaterialMatcher.matchesIngredient(Ingredient.of(Items.DIAMOND), first));
        assertTrue(MaterialMatcher.matchesExact(MaterialKey.of(first), first));
        assertFalse(MaterialMatcher.matchesExact(MaterialKey.of(first), second));
        assertFalse(MaterialMatcher.sameRuntimeFragment(first, second));
    }

    @Test
    void captureExpectationUsesExactNbtWhenDeclared() {
        ItemStack first = tagged("one");
        ItemStack second = tagged("two");

        assertTrue(MaterialMatcher.matchesCaptureExpectation(MaterialKey.of(first), first));
        assertFalse(MaterialMatcher.matchesCaptureExpectation(MaterialKey.of(first), second));
        assertTrue(MaterialMatcher.matchesCaptureExpectation(
                new MaterialKey(Items.DIAMOND, null), second));
    }

    @Test
    void nonSpellOutputsRemainStrictWhenTheirDeclaredNbtDiffers() {
        ItemStack declared = tagged("declared");
        ItemStack actual = tagged("actual");

        assertFalse(MaterialMatcher.matchesOutputDeclaration(
                MaterialKey.of(declared), actual));
    }

    @Test
    void pristineDamageTagDoesNotConstrainRuntimeOutputNbt() {
        ItemStack pristine = new ItemStack(Items.DIAMOND_SWORD);
        pristine.getOrCreateTag().putInt("Damage", 0);
        MaterialKey declaration = MaterialKey.of(pristine);

        ItemStack runtimeOutput = pristine.copy();
        runtimeOutput.getOrCreateTag().putInt("runtime_state", 1);
        ItemStack damaged = pristine.copy();
        damaged.setDamageValue(1);

        assertNull(declaration.tag());
        assertTrue(MaterialMatcher.matchesOutputDeclaration(declaration, runtimeOutput));
        assertNotNull(MaterialKey.of(damaged).tag());
    }

    @Test
    void pristineUnbreakableOutputRetainsRuntimeStateForDelivery() throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ItemStack expected = new ItemStack(Items.DIAMOND_SWORD);
        expected.setTag(net.minecraft.nbt.TagParser.parseTag("{Unbreakable:1}"));
        ItemStack actual = expected.copy();
        actual.getOrCreateTag().putInt("Damage", 0);
        actual.getOrCreateTag().putInt("RepairCost", 8);
        actual.getOrCreateTag().putString("itemModifier", "celestial_forge:vicious");
        ItemStack damaged = actual.copy();
        damaged.setDamageValue(1);

        assertTrue(IngredientMatcher.matchesProducedOutput(expected, actual));
        assertFalse(IngredientMatcher.matchesProducedOutput(expected, damaged));
    }

    @Test
    void unrelatedTaggedOutputStillRequiresExactTagsForDelivery() {
        ItemStack expected = tagged("declared");
        ItemStack actual = tagged("runtime");
        assertFalse(IngredientMatcher.matchesProducedOutput(expected, actual));
    }

    private static ItemStack tagged(String value) {
        ItemStack stack = new ItemStack(Items.DIAMOND);
        CompoundTag tag = new CompoundTag();
        tag.putString("variant", value);
        stack.setTag(tag);
        return stack;
    }
}
