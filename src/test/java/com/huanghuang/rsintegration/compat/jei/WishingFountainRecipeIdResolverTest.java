package com.huanghuang.rsintegration.compat.jei;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class WishingFountainRecipeIdResolverTest {
    @Test
    void recoversNativeRecipeIdFromWrapperTranslationKey() {
        assertEquals(new ResourceLocation("wishing_fountain", "end_city"),
                WishingFountainRecipeIdResolver.resolveLangKey(
                        "jei.wishing_fountain.altar_craft.end_city.result"));
    }

    @Test
    void supportsDatapackNamespaces() {
        assertEquals(new ResourceLocation("example", "custom_wish"),
                WishingFountainRecipeIdResolver.resolveLangKey(
                        "jei.example.altar_craft.custom_wish.result"));
    }

    @Test
    void rejectsUnrelatedTranslationKeys() {
        assertNull(WishingFountainRecipeIdResolver.resolveLangKey(
                "jei.wishing_fountain.recipe.end_city.result"));
    }
}
