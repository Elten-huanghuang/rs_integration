package com.huanghuang.rsintegration.recipe;

import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MalumRecipeHandlerFocusingInputTest extends BootstrapTest {

    @Test
    void durabilityBearingInputIsAPerOperationReplacement() {
        Ingredient input = Ingredient.of(Items.IRON_PICKAXE);
        DemandRole role = MalumRecipeHandler.focusingInputRole(
                new FakeFocusingRecipe(60_000), input);

        assertEquals(DemandRole.CONTAINER_RETURNING, role);
        assertEquals(2, CraftPacketUtils.requiredCount(new IngredientSpec(input, 1, role), 2));
    }

    @Test
    void zeroCostToolRemainsReusable() {
        assertEquals(DemandRole.CATALYST,
                MalumRecipeHandler.focusingInputRole(
                        new FakeFocusingRecipe(0), Ingredient.of(Items.IRON_PICKAXE)));
    }

    @Test
    void threeUseToolNeedsOnlyOneCatalystForThreeOperations() {
        Ingredient input = Ingredient.of(Items.IRON_PICKAXE);
        int maxDamage = input.getItems()[0].getMaxDamage();
        DemandRole role = MalumRecipeHandler.focusingInputRole(
                new FakeFocusingRecipe(Math.max(1, (maxDamage + 2) / 3)), input);

        assertEquals(DemandRole.CATALYST, role);
        assertEquals(1, CraftPacketUtils.requiredCount(new IngredientSpec(input, 1, role), 3));
    }

    @Test
    void positiveCostDoesNotConsumeAnUndamageableInput() {
        assertEquals(DemandRole.CATALYST,
                MalumRecipeHandler.focusingInputRole(
                        new FakeFocusingRecipe(60_000), Ingredient.of(Items.IRON_INGOT)));
    }

    private static final class FakeFocusingRecipe {
        private final int durabilityCost;

        private FakeFocusingRecipe(int durabilityCost) {
            this.durabilityCost = durabilityCost;
        }
    }
}
