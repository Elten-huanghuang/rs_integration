package com.huanghuang.rsintegration;

import com.huanghuang.rsintegration.mods.casualnessdelight.CasualnessDelightRSModule;
import com.huanghuang.rsintegration.mods.farmandcharm.FarmAndCharmRSModule;
import com.huanghuang.rsintegration.recipe.DeepFryingRecipeHandler;
import com.huanghuang.rsintegration.recipe.LetsDoCookingPotRecipeHandler;
import com.huanghuang.rsintegration.util.ModIds;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 验证实例分类无法仅靠普通前缀完成的两类配方。 */
class ModTypeRecipeClassTest {

    @BeforeAll
    static void registerTypes() {
        FarmAndCharmRSModule.INSTANCE.registerModType();
        CasualnessDelightRSModule.INSTANCE.registerModType();
    }

    @Test
    void deepFryingClassRoutesBeforeJeiClassification() {
        assertEquals(ModType.byId(ModIds.ID_CD_DEEP_FRYING_PAN),
                ModType.classifyRecipeClass(DeepFryingRecipeHandler.RECIPE_CLASS));
    }

    @Test
    void sharedLetsDoRecipeClassUsesCanonicalMachineType() {
        assertEquals(ModType.byId(ModIds.ID_FC_COOKING_POT),
                ModType.classifyRecipeClass(LetsDoCookingPotRecipeHandler.RECIPE_CLASS));
    }

    @Test
    void unrelatedRecipeClassDoesNotUseClassRouting() {
        assertNull(ModType.classifyRecipeClass("example.recipe.OtherRecipe"));
    }
}
