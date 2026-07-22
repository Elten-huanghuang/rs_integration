package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.crafting.plan.PlanResponse;
import com.huanghuang.rsintegration.crafting.tree.IngredientKey;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenericCraftPacketFailureReasonTest extends BootstrapTest {
    @Test
    void identifiesExactNbtShortageWhenEnoughSameItemExists() {
        ItemStack expected = new ItemStack(Items.POTION);
        expected.getOrCreateTag().putString("Potion", "minecraft:water");
        Map<IngredientKey, PlanResponse.Availability> materials = Map.of(
                IngredientKey.of(expected), new PlanResponse.Availability(3, 0));
        Map<Item, Integer> available = Map.of(Items.POTION, 3);

        assertTrue(GenericCraftPacket.hasNbtMismatch(materials, available));
    }

    @Test
    void ordinaryCountShortageIsNotReportedAsNbtMismatch() {
        ItemStack expected = new ItemStack(Items.POTION);
        expected.getOrCreateTag().putString("Potion", "minecraft:water");
        Map<IngredientKey, PlanResponse.Availability> materials = Map.of(
                IngredientKey.of(expected), new PlanResponse.Availability(3, 1));

        assertFalse(GenericCraftPacket.hasNbtMismatch(materials, Map.of(Items.POTION, 2)));
    }
}
