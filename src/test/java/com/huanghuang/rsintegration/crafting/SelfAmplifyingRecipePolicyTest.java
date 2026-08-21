package com.huanghuang.rsintegration.crafting;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelfAmplifyingRecipePolicyTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void executionIngredientExcludesNeutralSelfOutput() {
        IngredientSpec broadChest = new IngredientSpec(
                Ingredient.of(Items.CHEST, Items.TRAPPED_CHEST), 1);

        IngredientSpec filtered = SelfAmplifyingRecipePolicy
                .excludeNonProductiveSelfCandidate(
                        broadChest, new ItemStack(Items.TRAPPED_CHEST));

        assertTrue(filtered.ingredient().test(new ItemStack(Items.CHEST)));
        assertFalse(filtered.ingredient().test(new ItemStack(Items.TRAPPED_CHEST)));
    }

    @Test
    void amplifyingSelfInputRemainsAvailable() {
        IngredientSpec self = new IngredientSpec(Ingredient.of(Items.DIAMOND), 1);
        List<IngredientSpec> scaled = SelfAmplifyingRecipePolicy.scaleTargetInputs(
                List.of(self), new ItemStack(Items.DIAMOND, 2), 6);

        assertTrue(scaled.get(0).ingredient().test(new ItemStack(Items.DIAMOND)));
        assertTrue(scaled.get(0).count() == 1);
    }
}
