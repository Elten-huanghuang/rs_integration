package com.huanghuang.rsintegration.mods.apotheosis;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApotheosisGemCuttingRecipeTest extends BootstrapTest {

    @Test
    void runtimeRecipesExposeStableRecipeType() {
        assertEquals("rs_integration:gem_cutting",
                ApotheosisGemCuttingRecipe.TYPE.toString());
    }
}
