package com.huanghuang.rsintegration.network.binding;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.mods.farmandcharm.FarmAndCharmRSModule;
import com.huanghuang.rsintegration.util.ModIds;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Recipe;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 共享锅具配方目录不能作为物理机器绑定过滤器。 */
class LetsDoBindingSubtypeTest {

    @BeforeAll
    static void registerTypes() {
        FarmAndCharmRSModule.INSTANCE.registerModType();
    }

    @Test
    void potCookingFolderDoesNotRejectSharedPotBindings() {
        Recipe<?> recipe = mock(Recipe.class);
        when(recipe.getId()).thenReturn(
                new ResourceLocation("farm_and_charm", "pot_cooking/tomato_soup"));

        assertNull(AltarBindingRegistry.recipeMachineSubType(
                recipe, ModType.byId(ModIds.ID_FC_COOKING_POT)));
        assertNull(AltarBindingRegistry.recipeMachineSubType(
                recipe, ModType.byId(ModIds.ID_CANDLELIGHT_COOKING_POT)));
        assertNull(AltarBindingRegistry.recipeMachineSubType(
                recipe, ModType.byId(ModIds.ID_BAKERY_COOKING_POT)));
    }
}
