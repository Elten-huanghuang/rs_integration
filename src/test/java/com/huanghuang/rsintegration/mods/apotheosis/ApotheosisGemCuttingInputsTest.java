package com.huanghuang.rsintegration.mods.apotheosis;

import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.batch.CountedOperationMaterials;
import com.huanghuang.rsintegration.crafting.batch.CountedOperationMaterials.Origin;
import com.huanghuang.rsintegration.crafting.batch.CountedOperationMaterials.Pool;
import com.huanghuang.rsintegration.crafting.batch.MaterialPlan;
import com.huanghuang.rsintegration.crafting.batch.OperationStartContext.ReservedMaterial;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.common.crafting.PartialNBTIngredient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApotheosisGemCuttingInputsTest extends BootstrapTest {
    @Test
    void mixedDustCanSupplyBothCutsAndKeepExactReservationCounts() {
        ItemStack gem = gem("epic");
        ItemStack taggedDust = picked(Items.GLOWSTONE_DUST, 13);
        Pool stock = new Pool().add(Origin.PRODUCER, gem, 4)
                .add(Origin.INITIAL, new ItemStack(Items.GLOWSTONE_DUST), 1)
                .add(Origin.INITIAL, List.of(taggedDust, new ItemStack(Items.GOLD_INGOT, 2)));
        var inputs = inputs(gem, 1);
        var materials = allocate(inputs, stock, 2);

        assertEquals(1, materials.compatibleBatchSize(0, 2));
        for (int operation = 0; operation < 2; operation++) {
            var menu = inputs.forBatch(batch(materials, operation, 1), 1);
            assertNotNull(menu);
            assertEquals(gem.getTag(), menu.gem().getTag());
            assertEquals(7, menu.dust().getCount());
            assertFalse(menu.dust().hasTag());
            assertEquals(1, menu.rarityMaterial().getCount());
        }
        var reserved = materials.materials(Origin.INITIAL, 0, 2);
        assertEquals(1, dustCount(reserved, false));
        assertEquals(13, dustCount(reserved, true));
        assertEquals(4, materials.materials(Origin.PRODUCER, 0, 2).get(0).getCount());
        assertTrue(stock.remaining(Origin.INITIAL).isEmpty());
    }

    @Test
    void batchesMixedRarityMaterialsWithoutChangingTheOriginalTags() {
        ItemStack gem = gem("mythic");
        ItemStack markedRarity = picked(Items.GOLD_INGOT, 4);
        Pool stock = new Pool().add(Origin.INITIAL, gem, 4)
                .add(Origin.INITIAL, new ItemStack(Items.GLOWSTONE_DUST), 14)
                .add(Origin.INITIAL, markedRarity, 4)
                .add(Origin.INITIAL, new ItemStack(Items.GOLD_INGOT), 2);
        var inputs = inputs(gem, 3);
        var materials = allocate(inputs, stock, 2);

        assertEquals(2, materials.compatibleBatchSize(0, 2));
        var slots = batch(materials, 0, 2);
        var menu = inputs.forBatch(slots, 2);
        assertNotNull(menu);
        assertEquals(3, menu.rarityMaterial().getCount());
        assertFalse(menu.rarityMaterial().hasTag());
        assertTrue(slots.get(slots.size() - 2).getTag().getBoolean("DespawnTweakerPicked"));
        assertEquals(4, markedRarity.getCount());

        var incompleteBatch = new ArrayList<>(slots);
        incompleteBatch.set(1, slots.get(1).copyWithCount(1));
        assertNull(inputs.forBatch(incompleteBatch, 2));
    }

    @Test
    void otherGemRaritiesAndMissingDustDoNotSatisfyThePlan() {
        ItemStack gem = gem("epic");
        var inputs = inputs(gem, 1);
        assertThrows(IllegalArgumentException.class, () -> allocate(inputs,
                new Pool().add(Origin.INITIAL, gem, 1)
                        .add(Origin.INITIAL, gem("rare"), 1), 1));
        assertThrows(IllegalArgumentException.class, () -> allocate(inputs,
                new Pool().add(Origin.INITIAL, gem, 2)
                        .add(Origin.INITIAL, picked(Items.GLOWSTONE_DUST, 6), 6)
                        .add(Origin.INITIAL, new ItemStack(Items.GOLD_INGOT), 1), 1));
    }

    private static ApotheosisGemCuttingInputs inputs(ItemStack gem, int materialCost) {
        return new ApotheosisGemCuttingInputs(List.of(
                new IngredientSpec(PartialNBTIngredient.of(gem.getItem(), gem.getTag()), 2),
                new IngredientSpec(Ingredient.of(Items.GLOWSTONE_DUST), 7),
                new IngredientSpec(Ingredient.of(Items.GOLD_INGOT), materialCost)));
    }

    private static CountedOperationMaterials allocate(ApotheosisGemCuttingInputs inputs, Pool stock, int count) {
        var builder = new CountedOperationMaterials.Builder(count, 1);
        for (var entry : MaterialPlan.graphSlots(inputs.reservationSlots()).graphEntries()) builder.add(entry, stock);
        return builder.build();
    }

    private static List<ItemStack> batch(CountedOperationMaterials materials, int first, int count) {
        return materials.batchMaterials(0, first, count).stream().map(ReservedMaterial::stack).toList();
    }

    private static int dustCount(List<ItemStack> stacks, boolean tagged) {
        return stacks.stream().filter(stack -> stack.is(Items.GLOWSTONE_DUST) && stack.hasTag() == tagged)
                .mapToInt(ItemStack::getCount).sum();
    }

    private static ItemStack gem(String rarity) {
        ItemStack stack = new ItemStack(Items.DIAMOND);
        stack.getOrCreateTag().putString("gem", "apotheosis:core/combatant");
        stack.getOrCreateTagElement("affix_data").putString("rarity", "apotheosis:" + rarity);
        return stack;
    }

    private static ItemStack picked(Item item, int count) {
        ItemStack stack = new ItemStack(item, count);
        stack.getOrCreateTag().putBoolean("DespawnTweakerPicked", true);
        return stack;
    }
}
