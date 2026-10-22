package com.huanghuang.rsintegration.mods.jei;

import com.huanghuang.rsintegration.feature.craftingstation.CraftingStationMode;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.refinedmods.refinedstorage.api.network.grid.GridType;
import com.refinedmods.refinedstorage.api.network.grid.IGrid;
import com.refinedmods.refinedstorage.container.GridContainerMenu;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandlerHelper;
import mezz.jei.api.recipe.vanilla.IJeiAnvilRecipe;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.crafting.SmithingRecipe;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SmithingJeiTransferHandlerTest extends BootstrapTest {

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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void patternGridAlwaysDelegatesSpecialRecipesToNativeTransfer(boolean doTransfer) {
        GridContainerMenu menu = mock(GridContainerMenu.class);
        IGrid grid = mock(IGrid.class);
        when(menu.getGrid()).thenReturn(grid);
        when(grid.getGridType()).thenReturn(GridType.PATTERN);
        IRecipeSlotsView slots = mock(IRecipeSlotsView.class);
        Player player = mock(Player.class);
        IRecipeTransferHandlerHelper helper = mock(IRecipeTransferHandlerHelper.class);
        SmithingJeiTransferHandler handler = new SmithingJeiTransferHandler(helper);

        for (Object recipe : new Object[]{mock(SmithingRecipe.class),
                mock(StonecutterRecipe.class), mock(IJeiAnvilRecipe.class)}) {
            clearInvocations(menu);

            assertNull(handler.transferRecipe(menu, recipe, slots, player, false, doTransfer));

            // 没有已初始化界面时，RS 原生处理器在此返回；仍必须走到这个入口。
            verify(menu).getScreenInfoProvider();
            verifyNoInteractions(slots, player, helper);
        }
    }
}
