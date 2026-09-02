package com.huanghuang.rsintegration.mods.wishingfountain;

import com.huanghuang.rsintegration.ModType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class WishingFountainIntegrationRegistrationTest {
    @BeforeAll
    static void registerType() {
        WishingFountainRSModule.INSTANCE.registerModType();
    }

    @Test
    void mapsNativeAndJeiRecipesToTheFountain() {
        assertEquals(WishingFountainRSModule.TYPE_ID,
                ModType.filterForJeiUid("wishing_fountain:wishing_fountain"));
        assertEquals(WishingFountainRSModule.TYPE_ID,
                ModType.filterForRecipeClass(
                        "io.github.poisonsheep.wishingfountain.compat.jei.WFRecipeWrapper"));
        assertSame(ModType.byId(WishingFountainRSModule.TYPE_ID),
                ModType.findByRecipeClass(WishingFountainRecipeHandler.RECIPE_CLASS));
    }
}
