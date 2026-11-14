package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.graph.MaterialSource;
import com.huanghuang.rsintegration.crafting.graph.NodeId;
import com.huanghuang.rsintegration.crafting.graph.OutputPortId;
import net.minecraft.nbt.TagParser;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.common.crafting.PartialNBTIngredient;
import net.minecraftforge.common.crafting.StrictNBTIngredient;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ResolutionContextInventoryLookupTest extends BootstrapTest {
    @BeforeAll
    static void initializeModLogger() throws ClassNotFoundException {
        var context = mock(FMLJavaModLoadingContext.class);
        when(context.getModEventBus()).thenReturn(mock(IEventBus.class));
        try (var loading = mockStatic(FMLJavaModLoadingContext.class)) {
            loading.when(FMLJavaModLoadingContext::get).thenReturn(context);
            Class.forName(RSIntegrationMod.class.getName());
        }
    }

    @Test
    void completeNbtConsumptionOnlySortsRelatedVariants() throws Exception {
        var sizes = new ArrayList<Integer>();
        for (int unrelated : List.of(0, 3770)) {
            var inventory = new ArrayList<ItemStack>();
            for (int i = 0; i < unrelated; i++) {
                ItemStack dirt = new ItemStack(Items.DIRT, 64);
                dirt.getOrCreateTag().putInt("variant", i);
                inventory.add(dirt);
            }
            for (int i = 0; i < 3; i++) {
                ItemStack iron = new ItemStack(Items.IRON_INGOT, 2);
                iron.getOrCreateTag().putInt("grade", i == 2 ? 2 : 1);
                iron.getOrCreateTag().putInt("variant", i);
                inventory.add(iron);
            }
            Ingredient partial = PartialNBTIngredient.of(Items.IRON_INGOT, TagParser.parseTag("{grade:1}"));
            var context = new ResolutionContext(null, Map.of(), inventory, null);
            var selection = ResolutionContext.class.getDeclaredMethod("sortedMatchingKeys", Ingredient.class);
            selection.setAccessible(true);
            int first = ((List<?>) selection.invoke(context, partial)).size();
            int repeated = ((List<?>) selection.invoke(context, partial)).size();
            System.out.printf("typed_candidates unrelated=%d first=%d repeated=%d%n", unrelated, first, repeated);
            sizes.add(first);
            sizes.add(repeated);
            assertEquals(4, context.countMatching(partial));
            var consumed = context.consumeMatchingDetailed(partial, 4);
            assertTrue(consumed.complete());
            assertEquals(2, consumed.slices().size());
            assertEquals(0, consumed.slices().get(0).material().toStack(1).getTag().getInt("variant"));
            assertEquals(1, consumed.slices().get(1).material().toStack(1).getTag().getInt("variant"));
        }
        assertEquals(List.of(3, 3, 3, 3), sizes,
                "完整物品清单的 NBT 输入只应收集对应物品族，不重复排序无关库存");
    }

    @Test
    void overlappingTemplatesAndNestedRollbackKeepLiveQuantitiesAndSupply() throws Exception {
        ItemStack first = new ItemStack(Items.IRON_INGOT, 2);
        first.setTag(TagParser.parseTag("{grade:1,variant:1}"));
        ItemStack second = first.copyWithCount(3);
        second.getTag().putInt("variant", 2);
        Ingredient demand = Ingredient.of(first, first.copy(), second);
        var source = new MaterialSource.ProducerOutput(new OutputPortId(new NodeId(0), 0));
        var context = new ResolutionContext(null, Map.of(), List.of(first), null);
        assertEquals(2, context.countMatching(demand));
        context.beginUndo();
        assertTrue(context.consumeMatchingDetailed(demand, 2).complete());
        context.addProduced(second, source);
        context.beginUndo();
        assertTrue(context.consumeMatchingDetailed(demand, 3).complete());
        context.addProduced(first.copyWithCount(5), source);
        assertEquals(5, context.countMatching(demand));
        context.rollback();
        assertEquals(3, context.countMatching(demand));
        context.rollback();
        assertEquals(2, context.countMatching(demand));
        assertTrue(context.consumeMatchingDetailed(demand, 2).complete());
        context.addProduced(second, source);
        assertEquals(3, context.countMatching(demand));
        assertTrue(context.consumeMatchingDetailed(demand, 3).complete());
    }

    @Test
    void nbtConsumptionRetainsFullScanTieOrderAfterReinsertionAndRollback() throws Exception {
        ItemStack first = new ItemStack(Items.IRON_INGOT, 2);
        first.setTag(TagParser.parseTag("{grade:1,variant:1}"));
        ItemStack second = first.copy();
        second.getTag().putInt("variant", 2);
        var context = new ResolutionContext(null, Map.of(), List.of(first, second), null);
        assertTrue(context.consumeMatchingDetailed(StrictNBTIngredient.of(first), 2).complete());
        context.addProduced(first, new MaterialSource.ProducerOutput(new OutputPortId(new NodeId(0), 0)));
        var demand = PartialNBTIngredient.of(Items.IRON_INGOT, TagParser.parseTag("{grade:1}"));
        context.beginUndo();
        var expected = context.counts.keySet().iterator().next();
        assertEquals(2, expected.toStack().getTag().getInt("variant"));
        assertEquals(expected.toStack().getTag(),
                context.consumeMatchingDetailed(demand, 2).slices().get(0).material().toStack(1).getTag());
        context.rollback();
        expected = context.counts.keySet().iterator().next();
        assertEquals(expected.toStack().getTag(),
                context.consumeMatchingDetailed(demand, 2).slices().get(0).material().toStack(1).getTag());
    }

    @Test
    void ordinaryItemConsumptionRetainsIndexedTieOrderAfterReinsertion() throws Exception {
        ItemStack first = new ItemStack(Items.IRON_INGOT, 2);
        first.setTag(TagParser.parseTag("{variant:1}"));
        ItemStack second = first.copy();
        second.getTag().putInt("variant", 2);
        var context = new ResolutionContext(null, Map.of(), List.of(first, second), null);
        assertTrue(context.consumeMatchingDetailed(StrictNBTIngredient.of(first), 2).complete());
        context.addProduced(first, new MaterialSource.ProducerOutput(new OutputPortId(new NodeId(0), 0)));
        assertEquals(first.getTag(), context.consumeMatchingDetailed(Ingredient.of(Items.IRON_INGOT), 1)
                .slices().get(0).material().toStack(1).getTag());
    }

    @Test
    void overlappingTemplatesStillSaturateCountsWithoutCountingAKeyTwice() throws Exception {
        ItemStack first = new ItemStack(Items.IRON_INGOT);
        first.setTag(TagParser.parseTag("{variant:1}"));
        ItemStack second = first.copy();
        second.getTag().putInt("variant", 2);
        var context = new ResolutionContext(null, Map.of(), Map.of(
                CraftingResolver.StackKey.of(first, true), Long.MAX_VALUE - 2,
                CraftingResolver.StackKey.of(second, true), 10L), null, false, null);
        assertEquals(Long.MAX_VALUE, context.countMatching(Ingredient.of(first, first.copy(), second)));
        assertEquals(Long.MAX_VALUE - 2, context.countMatching(StrictNBTIngredient.of(first)));
    }

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
