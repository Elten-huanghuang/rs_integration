package com.huanghuang.rsintegration.mods.jei;

import com.huanghuang.rsintegration.feature.craftingstation.CraftingStationMode;
import mezz.jei.api.recipe.vanilla.IJeiAnvilRecipe;
import net.minecraft.world.item.crafting.SmithingRecipe;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

class SmithingJeiTransferHandlerTest {

    @Test
    void mapsNativeJeiAnvilRecipesToTheAnvilStation() {
        assertEquals(CraftingStationMode.ANVIL,
                SmithingJeiTransferHandler.targetMode(mock(IJeiAnvilRecipe.class)));
    }

    @Test
    void keepsSmithingStonecutterAndUnrelatedRecipesDistinct() {
        assertEquals(CraftingStationMode.SMITHING,
                SmithingJeiTransferHandler.targetMode(mock(SmithingRecipe.class)));
        assertEquals(CraftingStationMode.STONECUTTER,
                SmithingJeiTransferHandler.targetMode(mock(StonecutterRecipe.class)));
        assertNull(SmithingJeiTransferHandler.targetMode(new Object()));
    }
}
