package com.huanghuang.rsintegration.crafting.tree;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Layer-2 tests for {@link IngredientKey} — needs real ItemStack/NBT, so it
 * runs after {@link BootstrapTest} initialises the vanilla registries.
 *
 * <p>The class Javadoc states the critical contract: equals/hashCode compare
 * item identity + NBT ONLY, never count. If that breaks, plan-tree fold state
 * silently resets on every refresh. These tests pin that contract.</p>
 */
class IngredientKeyTest extends BootstrapTest {

    @Test
    void countIsIgnoredForEquality() {
        IngredientKey one = IngredientKey.of(new ItemStack(Items.IRON_INGOT, 1));
        IngredientKey sixtyFour = IngredientKey.of(new ItemStack(Items.IRON_INGOT, 64));
        assertEquals(one, sixtyFour, "same item, different count → same key");
        assertEquals(one.hashCode(), sixtyFour.hashCode(),
                "hashCode must not depend on count");
    }

    @Test
    void differentItemsAreNotEqual() {
        IngredientKey iron = IngredientKey.of(new ItemStack(Items.IRON_INGOT));
        IngredientKey gold = IngredientKey.of(new ItemStack(Items.GOLD_INGOT));
        assertNotEquals(iron, gold);
    }

    @Test
    void nbtDistinguishesKeys() {
        ItemStack plain = new ItemStack(Items.DIAMOND_SWORD);
        ItemStack tagged = new ItemStack(Items.DIAMOND_SWORD);
        CompoundTag tag = new CompoundTag();
        tag.putInt("Damage", 5);
        tagged.setTag(tag);

        IngredientKey plainKey = IngredientKey.of(plain);
        IngredientKey taggedKey = IngredientKey.of(tagged);
        assertNotEquals(plainKey, taggedKey, "differing NBT → distinct keys");
    }

    @Test
    void sameNbtContentIsEqual() {
        ItemStack a = new ItemStack(Items.DIAMOND_SWORD);
        ItemStack b = new ItemStack(Items.DIAMOND_SWORD);
        CompoundTag ta = new CompoundTag();
        ta.putInt("Damage", 5);
        a.setTag(ta);
        CompoundTag tb = new CompoundTag();
        tb.putInt("Damage", 5);
        b.setTag(tb);

        assertEquals(IngredientKey.of(a), IngredientKey.of(b),
                "structurally equal NBT → equal keys (deep compare, not identity)");
        assertEquals(IngredientKey.of(a).hashCode(), IngredientKey.of(b).hashCode());
    }

    @Test
    void keyIsDefensivelyCopiedFromSourceTag() {
        ItemStack stack = new ItemStack(Items.DIAMOND_SWORD);
        CompoundTag tag = new CompoundTag();
        tag.putInt("Damage", 5);
        stack.setTag(tag);

        IngredientKey key = IngredientKey.of(stack);
        // Mutate the ORIGINAL stack's tag after the key was built.
        stack.getTag().putInt("Damage", 999);

        // The key snapshotted the tag, so it must still match the original state.
        ItemStack originalState = new ItemStack(Items.DIAMOND_SWORD);
        CompoundTag orig = new CompoundTag();
        orig.putInt("Damage", 5);
        originalState.setTag(orig);
        assertEquals(key, IngredientKey.of(originalState),
                "IngredientKey must copy the tag, not alias it");
    }

    @Test
    void matchesRespectsItemAndNbt() {
        ItemStack tagged = new ItemStack(Items.DIAMOND_SWORD);
        CompoundTag tag = new CompoundTag();
        tag.putInt("Damage", 5);
        tagged.setTag(tag);
        IngredientKey key = IngredientKey.of(tagged);

        assertTrue(key.matches(tagged.copy()), "same item+NBT matches");
        assertFalse(key.matches(new ItemStack(Items.DIAMOND_SWORD)),
                "missing NBT does not match");
        assertFalse(key.matches(new ItemStack(Items.IRON_INGOT)),
                "different item does not match");
    }

    @Test
    void stackRoundTripsItemAndNbtButAppliesCount() {
        ItemStack tagged = new ItemStack(Items.DIAMOND_SWORD);
        CompoundTag tag = new CompoundTag();
        tag.putInt("Damage", 5);
        tagged.setTag(tag);

        IngredientKey key = IngredientKey.of(tagged);
        ItemStack rebuilt = key.stack(16);
        assertTrue(rebuilt.is(Items.DIAMOND_SWORD));
        assertEquals(16, rebuilt.getCount(), "stack(n) applies the requested count");
        assertEquals(tag, rebuilt.getTag(), "NBT survives the round trip");
    }
}
