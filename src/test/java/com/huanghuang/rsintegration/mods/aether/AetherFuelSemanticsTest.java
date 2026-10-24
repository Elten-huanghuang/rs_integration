package com.huanghuang.rsintegration.mods.aether;

import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.ShapedRecipe;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class AetherFuelSemanticsTest extends BootstrapTest {
    @Test
    void selectsOnePracticalFuelInsteadOfRequiringEveryValidFuel() {
        Map<Item, Integer> fuels = Map.of(
                Items.COAL, 400,
                Items.CHARCOAL, 200);

        AetherFurnaceBatchDelegate.FuelChoice preferred =
                AetherFurnaceBatchDelegate.chooseFuel(
                        fuels, Map.of(), Items.COAL, 1000);
        AetherFurnaceBatchDelegate.FuelChoice affordable =
                AetherFurnaceBatchDelegate.chooseFuel(
                        fuels, Map.<Item, Long>of(Items.COAL, 2L, Items.CHARCOAL, 9L),
                        Items.COAL, 1000);
        AetherFurnaceBatchDelegate.FuelChoice partial =
                AetherFurnaceBatchDelegate.chooseFuel(
                        fuels, Map.<Item, Long>of(Items.COAL, 1L, Items.CHARCOAL, 2L),
                        Items.COAL, 1000);

        assertNotNull(preferred);
        assertEquals(Items.COAL, preferred.item());
        assertEquals(3, preferred.requiredItems());
        assertNotNull(affordable);
        assertEquals(Items.CHARCOAL, affordable.item());
        assertEquals(5, affordable.requiredItems());
        assertNotNull(partial);
        assertEquals(Items.CHARCOAL, partial.item());
    }

    @Test
    void craftTweakerFuelEntriesParticipateInSelection() {
        AetherFurnaceBatchDelegate.FuelChoice choice =
                AetherFurnaceBatchDelegate.chooseFuel(
                        Map.of(Items.BLAZE_ROD, 1000),
                        Map.<Item, Long>of(Items.BLAZE_ROD, 1L),
                        Items.COAL, 1750);

        assertNotNull(choice);
        assertEquals(Items.BLAZE_ROD, choice.item());
        assertEquals(2, choice.requiredItems());
    }

    @Test
    void recognizesEveryAetherFurnaceFuelFamily() {
        assertSame(AetherFurnaceBatchDelegate.FuelFamily.FREEZER,
                AetherFurnaceBatchDelegate.fuelFamilyForRecipe(new FreezingRecipe()));
        assertSame(AetherFurnaceBatchDelegate.FuelFamily.ALTAR,
                AetherFurnaceBatchDelegate.fuelFamilyForRecipe(new EnchantingRecipe()));
        assertSame(AetherFurnaceBatchDelegate.FuelFamily.ALTAR,
                AetherFurnaceBatchDelegate.fuelFamilyForRecipe(new AltarRepairRecipe()));
        assertSame(AetherFurnaceBatchDelegate.FuelFamily.INCUBATOR,
                AetherFurnaceBatchDelegate.fuelFamilyForRecipe(new IncubationRecipe()));
    }

    @Test
    void mergesInputFragmentsIntoTheExactPlannedBatchCount() {
        IngredientSpec input = new IngredientSpec(Ingredient.of(Items.APPLE), 1);

        AetherFurnaceBatchDelegate.BatchInput batch =
                AetherFurnaceBatchDelegate.mergeBatchInput(
                        List.of(new ItemStack(Items.APPLE, 32),
                                new ItemStack(Items.APPLE, 32)),
                        64, input, 64, Set.of(Items.COAL));

        assertNotNull(batch);
        assertEquals(64, batch.stack().getCount());
        assertEquals(64, batch.operations());
    }

    @Test
    void ignoresReservedFuelButRejectsShortOrDifferentInputs() {
        IngredientSpec input = new IngredientSpec(Ingredient.of(Items.APPLE), 1);

        assertNotNull(AetherFurnaceBatchDelegate.mergeBatchInput(
                List.of(new ItemStack(Items.APPLE, 16),
                        new ItemStack(Items.COAL, 8)),
                16, input, 64, Set.of(Items.COAL)));
        assertNull(AetherFurnaceBatchDelegate.mergeBatchInput(
                List.of(new ItemStack(Items.APPLE, 15)),
                16, input, 64, Set.of(Items.COAL)));
        assertNull(AetherFurnaceBatchDelegate.mergeBatchInput(
                List.of(new ItemStack(Items.APPLE, 16),
                        new ItemStack(Items.CARROT, 1)),
                16, input, 64, Set.of(Items.COAL)));
    }

    @Test
    void rejectsMixedNbtAndBatchesLargerThanTheInputSlot() {
        IngredientSpec input = new IngredientSpec(Ingredient.of(Items.APPLE), 1);
        ItemStack tagged = new ItemStack(Items.APPLE, 4);
        CompoundTag tag = new CompoundTag();
        tag.putString("variant", "test");
        tagged.setTag(tag);

        assertNull(AetherFurnaceBatchDelegate.mergeBatchInput(
                List.of(new ItemStack(Items.APPLE, 4), tagged),
                8, input, 64, Set.of(Items.COAL)));
        assertNull(AetherFurnaceBatchDelegate.mergeBatchInput(
                List.of(new ItemStack(Items.APPLE, 65)),
                65, input, 64, Set.of(Items.COAL)));
    }

    private static class FreezingRecipe extends TestRecipe {
        FreezingRecipe() {
            super("freezing");
        }
    }

    private static class EnchantingRecipe extends TestRecipe {
        EnchantingRecipe() {
            super("enchanting");
        }
    }

    private static class AltarRepairRecipe extends TestRecipe {
        AltarRepairRecipe() {
            super("altar_repair");
        }
    }

    private static class IncubationRecipe extends TestRecipe {
        IncubationRecipe() {
            super("incubation");
        }
    }

    private static class TestRecipe extends ShapedRecipe {
        TestRecipe(String path) {
            super(new ResourceLocation("test", path), "", CraftingBookCategory.MISC,
                    1, 1, NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.STONE)),
                    new ItemStack(Items.STONE));
        }
    }
}
