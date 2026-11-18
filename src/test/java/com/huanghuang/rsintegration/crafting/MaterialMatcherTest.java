package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.crafting.graph.MaterialKey;
import com.huanghuang.rsintegration.crafting.recovery.FluidTokenItem;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidTestFixtures;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaterialMatcherTest extends BootstrapTest {
    @Test
    void normalizesLegacyIntegerQuantitiesAtStorageBoundary() {
        var key = new CraftingResolver.StackKey(Items.DIAMOND, null);
        var other = new CraftingResolver.StackKey(Items.GOLD_INGOT, null);
        var legacy = new HashMap<Object, Object>();
        legacy.put(key, Integer.valueOf(3));
        legacy.put(other, Long.valueOf(5L));

        Map<CraftingResolver.StackKey, Long> normalized = MaterialSources.toLongCounts(legacy);

        assertEquals(Long.valueOf(3L), normalized.get(key));
        assertEquals(Long.valueOf(5L), normalized.get(other));
        assertEquals(Long.class, normalized.get(key).getClass());
        assertEquals(Long.class, normalized.get(other).getClass());
    }

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
    void exactIdentityIgnoresCompoundOrderButRetainsListOrderAndNumericTypes() {
        ItemStack first = new ItemStack(Items.DIAMOND);
        CompoundTag nested = new CompoundTag();
        nested.putInt("second", 2);
        nested.putInt("first", 1);
        first.getOrCreateTag().put("nested", nested);
        ListTag ordered = new ListTag();
        ordered.add(nested.copy());
        CompoundTag last = new CompoundTag();
        last.putInt("last", 3);
        ordered.add(last);
        first.getOrCreateTag().put("ordered", ordered);
        MaterialKey expected = MaterialKey.of(first);

        ItemStack reordered = first.copy();
        CompoundTag reversedKeys = new CompoundTag();
        reversedKeys.putInt("first", 1);
        reversedKeys.putInt("second", 2);
        reordered.getOrCreateTag().put("nested", reversedKeys);
        assertTrue(MaterialMatcher.matchesExact(expected, reordered));

        ItemStack differentType = first.copy();
        differentType.getOrCreateTag().getCompound("nested").putByte("first", (byte) 1);
        assertFalse(MaterialMatcher.matchesExact(expected, differentType));

        ItemStack differentOrder = first.copy();
        ListTag reversedList = new ListTag();
        reversedList.add(last.copy());
        reversedList.add(nested.copy());
        differentOrder.getOrCreateTag().put("ordered", reversedList);
        assertFalse(MaterialMatcher.matchesExact(expected, differentOrder));
    }

    @Test
    void exactIdentityTreatsEmptyTagAsAbsentAndRejectsMalformedDeclarations() {
        ItemStack empty = new ItemStack(Items.DIAMOND);
        empty.setTag(new CompoundTag());

        assertTrue(MaterialMatcher.matchesExact(new MaterialKey(Items.DIAMOND, null), empty));
        assertFalse(MaterialMatcher.matchesExact(new MaterialKey(Items.DIAMOND, "{invalid"), empty));
        assertFalse(MaterialMatcher.matchesExact(new MaterialKey(Items.IRON_INGOT, null), empty));
    }

    @Test
    void runtimeIdentityTreatsOnlyEmptyTagAsAbsent() {
        ItemStack tagless = new ItemStack(Items.DIAMOND);
        ItemStack emptyTag = tagless.copy();
        emptyTag.setTag(new CompoundTag());
        ItemStack nonEmptyTag = tagged("variant");

        assertTrue(MaterialMatcher.equivalentRuntimeFragment(tagless, emptyTag));
        assertFalse(MaterialMatcher.sameRuntimeFragment(tagless, nonEmptyTag));
        assertFalse(MaterialMatcher.sameRuntimeFragment(nonEmptyTag, emptyTag));
        assertFalse(MaterialMatcher.sameRuntimeFragment(emptyTag, new ItemStack(Items.IRON_INGOT)));
    }

    @Test
    void nonFiniteNumericTagsKeepTheLegacyNormalizedIdentity() {
        ItemStack actual = new ItemStack(Items.DIAMOND);
        actual.getOrCreateTag().putFloat("nan", Float.NaN);
        actual.getOrCreateTag().putDouble("infinity", Double.POSITIVE_INFINITY);

        assertTrue(MaterialMatcher.matchesExact(MaterialKey.of(actual), actual));
    }

    @Test
    void preparedOutputMatcherStillChecksMutatedStacksAndSpecialRuntimeState() {
        ItemStack expected = tagged("one");
        var matcher = MaterialMatcher.outputMatcher(MaterialKey.of(expected));
        ItemStack actual = expected.copy();

        assertTrue(matcher.test(actual));
        actual.getOrCreateTag().putString("variant", "two");
        assertFalse(matcher.test(actual));
        assertFalse(matcher.test(new ItemStack(Items.IRON_INGOT)));
        assertFalse(matcher.test(ItemStack.EMPTY));
        assertFalse(matcher.test(null));

        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        sword.getOrCreateTag().putInt("Damage", 0);
        var swordMatcher = MaterialMatcher.outputMatcher(MaterialKey.of(sword));
        sword.setDamageValue(4);
        assertEquals(MaterialMatcher.matchesOutputDeclaration(MaterialKey.of(
                new ItemStack(Items.DIAMOND_SWORD)), sword), swordMatcher.test(sword));

        ItemStack nonFinite = new ItemStack(Items.DIAMOND);
        nonFinite.getOrCreateTag().putDouble("value", Double.NaN);
        assertTrue(MaterialMatcher.outputMatcher(MaterialKey.of(nonFinite)).test(nonFinite));
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
    void alignsRuntimeNbtToDeclaredVariant() {
        ItemStack declared = tagged("heart");
        ItemStack actual = tagged("heart");
        actual.getOrCreateTag().putString("runtime-state", "tainted");

        ItemStack aligned = MaterialMatcher.alignToDeclaration(MaterialKey.of(declared), actual);

        assertTrue(MaterialMatcher.matchesOutputDeclaration(MaterialKey.of(declared), aligned));
        assertEquals("heart", aligned.getTag().getString("variant"));
        assertFalse(aligned.getTag().contains("runtime-state"));
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

        assertNotNull(declaration.tag());
        assertTrue(MaterialMatcher.matchesOutputDeclaration(declaration, runtimeOutput));
        assertFalse(MaterialMatcher.matchesExact(declaration, runtimeOutput));
        // 声明只带默认伤害时不再约束实产的磨损值：底材带进产物的耐久是配方的真实行为。
        assertTrue(MaterialMatcher.matchesOutputDeclaration(declaration, damaged));
        assertFalse(MaterialMatcher.matchesExact(declaration, damaged));
        assertNotNull(MaterialKey.of(damaged).tag());
    }

    @Test
    void smithingProductInheritingBaseDamageSatisfiesPristineDeclaration() {
        ItemStack pristine = new ItemStack(Items.DIAMOND_SWORD);
        pristine.getOrCreateTag().putInt("Damage", 0);
        ItemStack inherited = pristine.copy();
        inherited.setDamageValue(4);

        assertTrue(IngredientMatcher.matchesProducedOutput(pristine, inherited));
        assertTrue(MaterialMatcher.matchesOutputDeclaration(MaterialKey.of(pristine), inherited));
        assertFalse(MaterialMatcher.matchesExact(MaterialKey.of(pristine), inherited));
        assertEquals(4, MaterialKey.of(inherited).toStack(1).getDamageValue());
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

    @Test
    void fluidTokenIdentityIgnoresSerializedAmountMetadata() {
        FluidTokenItem tokenItem = InkFluidTestFixtures.tokenItem();
        ItemStack declared = InkFluidSupport.token(tokenItem,
                new FluidStack(Fluids.WATER, 1000));
        ItemStack actual = declared.copy();
        actual.getOrCreateTag().putInt("Amount", 1000);

        assertTrue(MaterialMatcher.matchesExact(MaterialKey.of(declared), actual));
        assertTrue(MaterialMatcher.sameRuntimeFragment(declared, actual));

        ItemStack otherFluid = InkFluidSupport.token(tokenItem,
                new FluidStack(Fluids.LAVA, 1000));
        assertFalse(MaterialMatcher.matchesExact(MaterialKey.of(declared), otherFluid));
    }

    private static ItemStack tagged(String value) {
        ItemStack stack = new ItemStack(Items.DIAMOND);
        CompoundTag tag = new CompoundTag();
        tag.putString("variant", value);
        stack.setTag(tag);
        return stack;
    }
}
