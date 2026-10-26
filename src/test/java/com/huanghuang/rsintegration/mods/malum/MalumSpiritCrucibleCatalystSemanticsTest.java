package com.huanghuang.rsintegration.mods.malum;

import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.consumption.InputConsumption;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MalumSpiritCrucibleCatalystSemanticsTest {

    @Test
    void durabilityCatalystStaysInstalledWhileItStillMatches() {
        Ingredient input = Ingredient.of(Items.IRON_PICKAXE);
        List<IngredientSpec> specs = List.of(new IngredientSpec(
                input, 1, InputConsumption.durability(1)));
        ItemStack worn = new ItemStack(Items.IRON_PICKAXE);
        worn.setDamageValue(1);

        assertTrue(MalumSpiritCrucibleBatchDelegate.hasReusableCatalystInput(specs));
        assertTrue(MalumSpiritCrucibleBatchDelegate.matchesCatalystInput(specs, worn));
        assertTrue(MalumSpiritCrucibleBatchDelegate.keepsCatalystInSlot(specs, worn));
    }

    @Test
    void brokenOrConsumedCatalystIsNotKeptInTheSlot() {
        List<IngredientSpec> consumed = List.of(new IngredientSpec(
                Ingredient.of(Items.IRON_INGOT), 1,
                InputConsumption.consumed()));

        assertFalse(MalumSpiritCrucibleBatchDelegate.hasReusableCatalystInput(consumed));
        assertFalse(MalumSpiritCrucibleBatchDelegate.matchesCatalystInput(
                consumed, new ItemStack(Items.IRON_PICKAXE)));
        assertFalse(MalumSpiritCrucibleBatchDelegate.keepsCatalystInSlot(
                consumed, new ItemStack(Items.IRON_INGOT)));
    }
}
