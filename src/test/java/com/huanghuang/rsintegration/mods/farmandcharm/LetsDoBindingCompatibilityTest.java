package com.huanghuang.rsintegration.mods.farmandcharm;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry;
import com.huanghuang.rsintegration.util.ModIds;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证共用 CookingPotRecipe 的三种 Let's Do 锅能正确路由到实际委托。 */
class LetsDoBindingCompatibilityTest {

    @BeforeAll
    static void registerTypes() {
        FarmAndCharmRSModule.INSTANCE.registerModType();
    }

    @Test
    void sharedRecipeTypeAcceptsCandlelightAndBakeryBindings() {
        ModType farmAndCharm = ModType.byId(ModIds.ID_FC_COOKING_POT);
        ModType candlelight = ModType.byId(ModIds.ID_CANDLELIGHT_COOKING_POT);
        ModType bakery = ModType.byId(ModIds.ID_BAKERY_COOKING_POT);

        assertTrue(AltarBindingRegistry.isCompatibleMachineType(farmAndCharm, candlelight));
        assertTrue(AltarBindingRegistry.isCompatibleMachineType(farmAndCharm, bakery));
        assertFalse(AltarBindingRegistry.isCompatibleMachineType(candlelight, bakery));
    }

    @Test
    void compatibleRouteKeepsConcreteMachineDelegate() {
        ModType farmAndCharm = ModType.byId(ModIds.ID_FC_COOKING_POT);
        ModType candlelight = ModType.byId(ModIds.ID_CANDLELIGHT_COOKING_POT);
        ModType bakery = ModType.byId(ModIds.ID_BAKERY_COOKING_POT);

        assertEquals(candlelight,
                AltarBindingRegistry.executionTypeForBinding(farmAndCharm, candlelight));
        assertEquals(bakery,
                AltarBindingRegistry.executionTypeForBinding(farmAndCharm, bakery));
    }

    @Test
    void physicalBindingKeysStillResolveToConcreteTypes() {
        assertEquals(ModType.byId(ModIds.ID_CANDLELIGHT_COOKING_POT),
                ModType.fromBlockKey(ModIds.ID_CANDLELIGHT_COOKING_POT + "||block.candlelight.cooking_pot"));
        assertEquals(ModType.byId(ModIds.ID_BAKERY_COOKING_POT),
                ModType.fromBlockKey(ModIds.ID_BAKERY_COOKING_POT + "||block.bakery.small_cooking_pot"));
    }

    @Test
    void subtypeHintOnlyUsesOwnedRecipePaths() {
        assertNull(AltarBindingRegistry.recipeSubTypeHint(
                new ResourceLocation("crafttweaker", "candlelight/tea")));
        assertNull(AltarBindingRegistry.recipeSubTypeHint(
                new ResourceLocation("bakery", "kjs/sweet_bread")));
        assertNull(AltarBindingRegistry.recipeSubTypeHint(
                new ResourceLocation("bakery", "flat_recipe")));
        assertEquals("candlelight", AltarBindingRegistry.recipeSubTypeHint(
                new ResourceLocation("bakery", "candlelight/tea")));
    }
}
