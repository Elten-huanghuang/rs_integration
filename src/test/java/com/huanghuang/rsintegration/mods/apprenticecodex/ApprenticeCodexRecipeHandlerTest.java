package com.huanghuang.rsintegration.mods.apprenticecodex;

import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApprenticeCodexRecipeHandlerTest {

    @Test
    void smokerSharesSmallOrdersInsteadOfFirstWorkerClaimingEverything() {
        var delegate = new ApprenticeCodexEssenceSmokerBatchDelegate();
        assertEquals(1, delegate.preferredParallelBatchSize(2, 2));
        assertEquals(3, delegate.preferredParallelBatchSize(8, 3));
        assertEquals(8, delegate.preferredParallelBatchSize(100, 2));
        assertEquals(1, delegate.preferredParallelBatchSize(1, 4));
    }

    @Test
    void catalystDemandRoundsUpAtEightMaterialSlots() {
        assertEquals(0, ApprenticeCodexRecipeHandler.requiredCatalystCount(0));
        assertEquals(1, ApprenticeCodexRecipeHandler.requiredCatalystCount(1));
        assertEquals(1, ApprenticeCodexRecipeHandler.requiredCatalystCount(8));
        assertEquals(2, ApprenticeCodexRecipeHandler.requiredCatalystCount(9));
        assertEquals(2, ApprenticeCodexRecipeHandler.requiredCatalystCount(16));
        assertEquals(3, ApprenticeCodexRecipeHandler.requiredCatalystCount(17));
    }

    @Test
    void recipeDemandHookScalesOnlyTheCatalystByPhysicalCycles() {
        ApprenticeCodexRecipeHandler handler = ApprenticeCodexRecipeHandler.essenceSmoker();
        Ingredient ingredient = Ingredient.of(Items.STONE);

        assertEquals(2, handler.requiredIngredientCount(null,
                new IngredientSpec(ingredient, 1, DemandRole.CATALYST), 0, 9));
        assertEquals(9, handler.requiredIngredientCount(null,
                new IngredientSpec(ingredient, 1), 1, 9));
    }
}
