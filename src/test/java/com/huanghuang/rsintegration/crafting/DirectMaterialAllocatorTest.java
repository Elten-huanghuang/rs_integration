package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.common.crafting.StrictNBTIngredient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DirectMaterialAllocatorTest extends BootstrapTest {
    @Test
    void assignsBroadIngredientWithoutStealingExactIngredientStock() {
        Ingredient broad = Ingredient.of(Items.IRON_INGOT, Items.GOLD_INGOT);
        Ingredient exactIron = Ingredient.of(Items.IRON_INGOT);

        DirectMaterialAllocator.Result result = DirectMaterialAllocator.allocate(
                List.of(new IngredientSpec(broad, 1), new IngredientSpec(exactIron, 1)),
                Map.of(new StackKey(Items.IRON_INGOT, null), 1,
                        new StackKey(Items.GOLD_INGOT, null), 1));

        assertTrue(result.feasible());
        assertEquals(2, result.allocations().stream()
                .mapToInt(DirectMaterialAllocator.Allocation::count).sum());
        assertTrue(result.allocations().stream().anyMatch(allocation ->
                allocation.ingredientIndex() == 1
                        && allocation.material().item() == Items.IRON_INGOT));
    }

    @Test
    void doesNotCountOneStackForTwoRecipeSlots() {
        DirectMaterialAllocator.Result result = DirectMaterialAllocator.allocate(
                List.of(new IngredientSpec(Ingredient.of(Items.DIAMOND), 1),
                        new IngredientSpec(Ingredient.of(Items.DIAMOND), 1)),
                Map.of(new StackKey(Items.DIAMOND, null), 1));

        assertFalse(result.feasible());
        assertEquals(1, result.missingCount());
    }

    @Test
    void preservesExactNbtDuringAllocation() {
        ItemStack expected = new ItemStack(Items.DIAMOND_SWORD);
        CompoundTag expectedTag = new CompoundTag();
        expectedTag.putInt("level", 1);
        expected.setTag(expectedTag);
        ItemStack wrong = expected.copy();
        wrong.getOrCreateTag().putInt("level", 2);

        DirectMaterialAllocator.Result result = DirectMaterialAllocator.allocate(
                List.of(new IngredientSpec(StrictNBTIngredient.of(expected), 1)),
                Map.of(new StackKey(Items.DIAMOND_SWORD, wrong.getTag().toString()), 1));

        assertFalse(result.feasible());
    }
}
