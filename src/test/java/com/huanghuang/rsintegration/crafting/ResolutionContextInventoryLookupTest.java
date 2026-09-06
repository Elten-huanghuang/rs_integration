package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.nbt.TagParser;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.common.crafting.PartialNBTIngredient;
import net.minecraftforge.common.crafting.StrictNBTIngredient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ResolutionContextInventoryLookupTest extends BootstrapTest {
    @Test
    void customTaglessDisplayDoesNotHideOtherAcceptedStockFromCountingOrConsumption() {
        var context = new ResolutionContext(null, Map.of(),
                List.of(new ItemStack(Items.IRON_INGOT, 2), new ItemStack(Items.GOLD_INGOT, 3)), null);
        Ingredient custom = new Ingredient(Stream.of(new Ingredient.ItemValue(new ItemStack(Items.IRON_INGOT)))) {
            @Override
            public boolean test(ItemStack stack) {
                return stack.is(Items.IRON_INGOT) || stack.is(Items.GOLD_INGOT);
            }
        };
        assertEquals(5, context.countMatching(custom));
        assertTrue(context.consumeMatchingDetailed(custom, 5).complete());
        assertEquals(0, context.countMatching(custom));
    }

    @Test
    void customEmptyDisplayStillUsesPredicate() {
        var context = new ResolutionContext(null, Map.of(), List.of(new ItemStack(Items.GOLD_INGOT, 3)), null);
        Ingredient custom = new Ingredient(Stream.empty()) {
            @Override
            public boolean isEmpty() {
                return false;
            }

            @Override
            public boolean test(ItemStack stack) {
                return stack.is(Items.GOLD_INGOT);
            }
        };
        assertEquals(3, context.countMatching(custom));
        assertTrue(context.consumeMatchingDetailed(custom, 3).complete());
    }

    @Test
    void thirdPartyPredicateCannotMutateCachedMaterialDuringCountingOrConsumption() {
        var context = new ResolutionContext(null, Map.of(), List.of(new ItemStack(Items.IRON_INGOT, 2)), null);
        Ingredient custom = new Ingredient(Stream.of(new Ingredient.ItemValue(new ItemStack(Items.IRON_INGOT)))) {
            @Override
            public boolean test(ItemStack stack) {
                boolean clean = !stack.getOrCreateTag().contains("poison");
                stack.getOrCreateTag().putBoolean("poison", true);
                return clean && stack.is(Items.IRON_INGOT);
            }
        };
        assertEquals(2, context.countMatching(custom));
        assertEquals(2, context.countMatching(custom));
        var consumed = context.consumeMatchingDetailed(custom, 2);
        assertTrue(consumed.complete());
        assertFalse(consumed.slices().get(0).material().toStack(1).hasTag());
    }

    @Test
    void worldDependentPredicateIsRecheckedRatherThanMemoized() {
        var enabled = new AtomicBoolean(true);
        var context = new ResolutionContext(null, Map.of(), List.of(new ItemStack(Items.IRON_INGOT, 2)), null);
        Ingredient custom = new Ingredient(Stream.of(new Ingredient.ItemValue(new ItemStack(Items.IRON_INGOT)))) {
            @Override
            public boolean test(ItemStack stack) {
                return enabled.get() && stack.is(Items.IRON_INGOT);
            }
        };
        assertEquals(2, context.countMatching(custom));
        enabled.set(false);
        assertEquals(0, context.countMatching(custom));
        enabled.set(true);
        assertEquals(2, context.countMatching(custom));
    }

    @Test
    void builtinModesAgreeWithFullScanAndPristineToolsRejectDamage() throws Exception {
        ItemStack pristine = new ItemStack(Items.WOODEN_SWORD);
        pristine.setTag(TagParser.parseTag("{Unbreakable:1b,Damage:0}"));
        ItemStack modified = pristine.copy();
        modified.getTag().putString("itemModifier", "celestial_forge:vicious");
        ItemStack damaged = pristine.copy();
        damaged.getTag().putInt("Damage", 9);
        var inventory = List.of(pristine, modified, damaged, new ItemStack(Items.STICK, 64));
        var context = new ResolutionContext(null, Map.of(), inventory, null);
        ItemStack required = new ItemStack(Items.WOODEN_SWORD);
        required.setTag(TagParser.parseTag("{Unbreakable:1}"));
        Ingredient exact = StrictNBTIngredient.of(required);
        Ingredient partial = PartialNBTIngredient.of(Items.WOODEN_SWORD, required.getTag());
        for (Ingredient demand : List.of(Ingredient.of(required), exact, partial)) {
            int expected = inventory.stream().filter(stack -> IngredientMatcher.test(demand, stack.copy()))
                    .mapToInt(ItemStack::getCount).sum();
            assertEquals(expected, context.countMatching(demand));
        }
        assertEquals(2, context.countMatching(exact));
        assertEquals(2, context.countMatching(partial));
        context.beginUndo();
        assertTrue(context.consumeMatchingDetailed(partial, 2).complete());
        assertEquals(0, context.countMatching(partial));
        context.rollback();
        assertEquals(2, context.countMatching(partial));
    }

    @Test
    void indexedQuantitiesStayCurrentAcrossAddConsumeAndRollback() throws Exception {
        ItemStack material = new ItemStack(Items.IRON_INGOT, 2);
        material.setTag(TagParser.parseTag("{grade:1}"));
        Ingredient demand = StrictNBTIngredient.of(material);
        var context = new ResolutionContext(null, Map.of(), List.of(material), null);
        assertEquals(2, context.countMatching(demand));
        context.beginUndo();
        assertTrue(context.consumeMatchingDetailed(demand, 2).complete());
        assertEquals(0, context.countMatching(demand));
        context.add(material.copyWithCount(5));
        assertEquals(5, context.countMatching(demand));
        context.rollback();
        assertEquals(2, context.countMatching(demand));
        context.add(material.copyWithCount(3));
        assertEquals(5, context.countMatching(demand));
    }
}
