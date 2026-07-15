package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.crafting.graph.MaterialKey;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaterialMatcherTest extends BootstrapTest {

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

    private static ItemStack tagged(String value) {
        ItemStack stack = new ItemStack(Items.DIAMOND);
        CompoundTag tag = new CompoundTag();
        tag.putString("variant", value);
        stack.setTag(tag);
        return stack;
    }
}
