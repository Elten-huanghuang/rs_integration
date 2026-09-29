package com.huanghuang.rsintegration.mixin.refinedstorage;

import com.huanghuang.rsintegration.craftingstation.CraftingStationAccess;
import com.huanghuang.rsintegration.craftingstation.CraftingStationMode;
import com.huanghuang.rsintegration.craftingstation.StonecutterScreenAccess;
import com.huanghuang.rsintegration.craftingstation.StonecutterTerminalState;
import com.refinedmods.refinedstorage.container.GridContainerMenu;
import com.refinedmods.refinedstorage.api.network.grid.GridType;
import com.refinedmods.refinedstorage.screen.BaseScreen;
import com.refinedmods.refinedstorage.screen.grid.GridScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** BaseScreen 是 RS 通用物品 Tooltip 的实际实现位置。 */
@Mixin(value = BaseScreen.class, remap = false)
public abstract class BaseScreenStonecutterTooltipMixin {
    @Unique
    private static final int RSI_PANEL_X = 50;
    @Unique
    private static final int RSI_PANEL_Y = 12;

    @Inject(method = "m_280003_", at = @At("HEAD"), remap = false)
    private void rsi$renderStonecutterRecipeTooltip(GuiGraphics graphics, int mouseX, int mouseY,
                                                     CallbackInfo ci) {
        if (!((Object) this instanceof GridScreen screen)
                || !(screen instanceof StonecutterScreenAccess access)
                || screen.getMenu().getGrid().getGridType()
                != GridType.CRAFTING) return;
        GridContainerMenu menu = screen.getMenu();
        if (CraftingStationAccess.access(menu).rsi$getCraftingStationMode()
                != CraftingStationMode.STONECUTTER
                || !(CraftingStationAccess.access(menu).rsi$getCraftingStationState()
                instanceof StonecutterTerminalState state)
                || Minecraft.getInstance().level == null) return;

        int start = Math.max(0, access.rsi$getStonecutterStartIndex());
        int top = screen.getGuiTop() + screen.getTopHeight() + screen.getVisibleRows() * 18;
        int localX = mouseX - screen.getGuiLeft();
        int localY = mouseY - top;
        if (localX < RSI_PANEL_X || localX >= RSI_PANEL_X + 64
                || localY < RSI_PANEL_Y || localY >= RSI_PANEL_Y + 54) return;

        int column = (localX - RSI_PANEL_X - 1) / 16;
        int row = (localY - RSI_PANEL_Y - 1) / 18;
        if (column < 0 || column >= 4 || row < 0 || row >= 3) return;
        int index = start + row * 4 + column;
        if (index < 0 || index >= state.recipes().size()) return;
        ItemStack result = state.recipes().get(index).getResultItem(
                Minecraft.getInstance().level.registryAccess());
        if (!result.isEmpty()) {
            // BaseScreen 的原版实现会先减去 guiLeft/guiTop，再把局部坐标传给
            // GuiGraphics；这里必须保持同一坐标系，否则 Tooltip 会向右下漂移。
            graphics.renderTooltip(Minecraft.getInstance().font, result,
                    mouseX - screen.getGuiLeft(), mouseY - screen.getGuiTop());
        }
    }
}
