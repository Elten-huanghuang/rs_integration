package com.huanghuang.rsintegration.mods.casualnessdelight;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.recipe.DeepFryingRecipeHandler;
import com.huanghuang.rsintegration.util.ModIds;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** 炸锅必须按真实配方类注册，不能退回 generic。 */
class DeepFryingClassificationTest {

    @BeforeAll
    static void registerCasualnessDelightTypes() {
        CasualnessDelightRSModule.INSTANCE.registerModType();
    }

    @Test
    void deepFryingRecipeClassResolvesToDeepFryingPan() {
        assertEquals(ModIds.ID_CD_DEEP_FRYING_PAN,
                ModType.filterForRecipeClass(DeepFryingRecipeHandler.RECIPE_CLASS));
        assertEquals(ModIds.ID_CD_DEEP_FRYING_PAN,
                ModType.filterForJeiUid("casualness_delight:deep_frying"));
        assertNotEquals(ModType.GENERIC,
                ModType.byId(ModIds.ID_CD_DEEP_FRYING_PAN));
    }
}
