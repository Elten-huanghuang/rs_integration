package com.huanghuang.rsintegration.mixin.jei;
import java.lang.reflect.Method;

import com.huanghuang.rsintegration.mods.jei.TetraJeiSortExclusion;
import com.huanghuang.rsintegration.mods.tetra.client.TetraMaterialSortMode;
import com.huanghuang.rsintegration.mods.tetra.client.TetraWorkbenchMaterialState;
import com.mojang.blaze3d.platform.InputConstants;
import mezz.jei.common.input.IInternalKeyMappings;
import mezz.jei.common.util.ImmutableRect2i;
import mezz.jei.gui.input.GuiTextFieldFilter;
import mezz.jei.gui.input.IUserInputHandler;
import mezz.jei.gui.input.UserInput;
import mezz.jei.gui.input.handlers.CombinedInputHandler;
import mezz.jei.gui.overlay.IngredientListOverlay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.Optional;

@Mixin(value = IngredientListOverlay.class, remap = false)
public abstract class IngredientListOverlayTetraSortMixin implements IUserInputHandler {
    @Unique
    private static final int RSI_BUTTON_SIZE = 20;
    @Unique
    private static final int RSI_PANEL_WIDTH = 190;
    @Unique
    private static final int RSI_ROW_HEIGHT = 18;
    @Unique
    private static final int RSI_CONTROL_START = 112;
    @Unique
    private static final int RSI_CONTROL_WIDTH = 18;
    @Unique
    private static ImmutableRect2i rsi$sortArea = ImmutableRect2i.EMPTY;
    @Unique
    private static ImmutableRect2i rsi$baseSearchArea = ImmutableRect2i.EMPTY;
    @Unique
    private static boolean rsi$menuOpen;

    @Inject(method = "updateBounds", at = @At("RETURN"))
    private void rsi$updateSortBounds(CallbackInfo ci) {
        IngredientListOverlayAccessor overlay = (IngredientListOverlayAccessor) this;
        GuiTextFieldFilter search = overlay.rsi$getSearchField();
        ImmutableRect2i searchArea = ((GuiTextFieldFilterAccessor) search).rsi$getArea();
        if (searchArea == null || searchArea.isEmpty()) {
            rsi$sortArea = ImmutableRect2i.EMPTY;
            return;
        }
        rsi$baseSearchArea = searchArea;
        if (TetraWorkbenchMaterialState.isActive()) {
            rsi$applySortBounds(search, searchArea);
        } else {
            rsi$sortArea = ImmutableRect2i.EMPTY;
            rsi$menuOpen = false;
            TetraJeiSortExclusion.clear();
        }
    }

    @Unique
    private void rsi$applySortBounds(GuiTextFieldFilter search, ImmutableRect2i searchArea) {
        rsi$sortArea = new ImmutableRect2i(
                searchArea.getX(), searchArea.getY(), RSI_BUTTON_SIZE, searchArea.getHeight());
        TetraJeiSortExclusion.updateSortArea(searchArea.getX(), searchArea.getY());
        search.updateBounds(searchArea.cropLeft(RSI_BUTTON_SIZE));
    }

    @Unique
    private static ImmutableRect2i rsi$getPanelArea() {
        int panelHeight = TetraMaterialSortMode.values().length * RSI_ROW_HEIGHT + 4;
        int screenWidth = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        int panelX = Math.max(0, Math.min(rsi$sortArea.getX(), screenWidth - RSI_PANEL_WIDTH));
        int panelY = Math.max(0, rsi$sortArea.getY() - panelHeight);
        return new ImmutableRect2i(panelX, panelY, RSI_PANEL_WIDTH, panelHeight);
    }

    @Unique
    private void rsi$ensureSortBounds() {
        IngredientListOverlayAccessor overlay = (IngredientListOverlayAccessor) this;
        GuiTextFieldFilter search = overlay.rsi$getSearchField();
        ImmutableRect2i currentArea = ((GuiTextFieldFilterAccessor) search).rsi$getArea();
        if (!TetraWorkbenchMaterialState.isActive()) {
            if (!rsi$sortArea.isEmpty() && !rsi$baseSearchArea.isEmpty()) {
                search.updateBounds(rsi$baseSearchArea);
            }
            rsi$sortArea = ImmutableRect2i.EMPTY;
            rsi$menuOpen = false;
            TetraJeiSortExclusion.clear();
            return;
        }
        if (rsi$sortArea.isEmpty() && currentArea != null && !currentArea.isEmpty()) {
            rsi$baseSearchArea = currentArea;
            rsi$applySortBounds(search, currentArea);
        }
    }

    @Inject(method = "drawScreen", at = @At("RETURN"))
    private void rsi$drawSortControl(Minecraft minecraft, GuiGraphics graphics, int mouseX,
                                     int mouseY, float partialTick, CallbackInfo ci) {
        rsi$ensureSortBounds();
        if (!TetraWorkbenchMaterialState.isActive()) return;
        if (rsi$sortArea.isEmpty()) return;
        int x = rsi$sortArea.getX();
        int y = rsi$sortArea.getY();
        graphics.fill(x, y, x + rsi$sortArea.getWidth(), y + rsi$sortArea.getHeight(), 0xFF202020);
        graphics.fill(x, y, x + rsi$sortArea.getWidth(), y + 1, 0xFF555555);
        graphics.fill(x, y + rsi$sortArea.getHeight() - 1,
                x + rsi$sortArea.getWidth(), y + rsi$sortArea.getHeight(), 0xFF101010);
        Component label = Component.literal("S");
        graphics.drawCenteredString(minecraft.font, label,
                x + rsi$sortArea.getWidth() / 2, y + 6, 0xFFFFFFFF);
        if (!rsi$menuOpen) {
            return;
        }
        ImmutableRect2i panelArea = rsi$getPanelArea();
        int panelX = panelArea.getX();
        int panelY = panelArea.getY();
        int panelBottom = panelY + panelArea.getHeight();
        graphics.fill(panelX, panelY, panelX + RSI_PANEL_WIDTH, panelBottom, 0xF0181818);
        graphics.fill(panelX, panelY, panelX + RSI_PANEL_WIDTH, panelY + 1, 0xFF777777);
        graphics.fill(panelX, panelBottom - 1, panelX + RSI_PANEL_WIDTH, panelBottom, 0xFF777777);
        for (int index = 0; index < TetraMaterialSortMode.values().length; index++) {
            TetraMaterialSortMode mode = TetraMaterialSortMode.values()[index];
            int rowY = panelY + 3 + index * RSI_ROW_HEIGHT;
            boolean selected = TetraWorkbenchMaterialState.isSortFieldSelected(mode);
            if (selected) {
                graphics.fill(panelX + 1, rowY - 1, panelX + RSI_PANEL_WIDTH - 1,
                        rowY + 15, 0xFF303030);
            }
            graphics.drawString(minecraft.font, mode.label(), panelX + 5, rowY, 0xFFFFFFFF);
            rsi$drawSortButton(graphics, panelX + RSI_CONTROL_START, rowY,
                    "▲", selected && ((mode == TetraWorkbenchMaterialState.getPrimarySortMode()
                            && TetraWorkbenchMaterialState.isPrimaryAscendingSelected())
                            || (mode == TetraWorkbenchMaterialState.getSecondarySortMode()
                            && TetraWorkbenchMaterialState.isSecondaryAscendingSelected())));
            rsi$drawSortButton(graphics, panelX + RSI_CONTROL_START + RSI_CONTROL_WIDTH, rowY,
                    "▼", selected && ((mode == TetraWorkbenchMaterialState.getPrimarySortMode()
                            && TetraWorkbenchMaterialState.isPrimaryDescendingSelected())
                            || (mode == TetraWorkbenchMaterialState.getSecondarySortMode()
                            && TetraWorkbenchMaterialState.isSecondaryDescendingSelected())));
            rsi$drawSortButton(graphics, panelX + RSI_CONTROL_START + RSI_CONTROL_WIDTH * 2, rowY,
                    selected ? "☑" : "☐", selected);
        }
    }

    @Unique
    private static void rsi$drawSortButton(GuiGraphics graphics, int x, int y,
                                           String symbol, boolean selected) {
        graphics.fill(x, y - 2, x + RSI_CONTROL_WIDTH - 2, y + 14,
                selected ? 0xFF4A4A4A : 0xFF242424);
        graphics.fill(x, y - 2, x + RSI_CONTROL_WIDTH - 2, y - 1, 0xFF666666);
        graphics.drawString(Minecraft.getInstance().font, symbol, x + 4, y, 0xFFFFFFFF);
    }

    @Inject(method = "drawTooltips", at = @At("RETURN"))
    private void rsi$drawSortTooltip(Minecraft minecraft, GuiGraphics graphics, int mouseX,
                                     int mouseY, CallbackInfo ci) {
        rsi$ensureSortBounds();
        if (TetraWorkbenchMaterialState.isActive() && !rsi$sortArea.isEmpty()
                && rsi$sortArea.contains(mouseX, mouseY)) {
            graphics.renderComponentTooltip(minecraft.font, List.of(
                    Component.translatable("gui.rs_integration.tetra_sort.tooltip.title"),
                    Component.translatable("gui.rs_integration.tetra_sort.tooltip.dual_priority"),
                    Component.translatable("gui.rs_integration.tetra_sort.tooltip.primary"),
                    Component.translatable("gui.rs_integration.tetra_sort.tooltip.secondary"),
                    Component.translatable("gui.rs_integration.tetra_sort.tooltip.controls")),
                    mouseX, mouseY);
        }
    }

    @Inject(method = "createInputHandler", at = @At("RETURN"), cancellable = true)
    private void rsi$addSortInputHandler(CallbackInfoReturnable<IUserInputHandler> cir) {
        IUserInputHandler original = cir.getReturnValue();
        cir.setReturnValue(new CombinedInputHandler(
                "IngredientListOverlayWithTetraSort", this, original));
    }

    @Override
    public Optional<IUserInputHandler> handleUserInput(Screen screen, UserInput input,
                                                       IInternalKeyMappings keyMappings) {
        rsi$ensureSortBounds();
        if (!TetraWorkbenchMaterialState.isActive() || rsi$sortArea.isEmpty()
                || !input.getKey().getType().equals(InputConstants.Type.MOUSE)
                || input.getKey().getValue() != 0) {
            return Optional.empty();
        }
        double mouseX = input.getMouseX();
        double mouseY = input.getMouseY();
        ImmutableRect2i panelArea = rsi$getPanelArea();
        int panelY = panelArea.getY();
        boolean inSortButton = rsi$sortArea.contains(mouseX, mouseY);
        boolean inMenu = rsi$menuOpen && panelArea.contains(mouseX, mouseY);
        if (input.isSimulate()) {
            // JEI performs a simulation pass before the real click. Returning a
            // handler here marks the control as hit and allows the execute pass
            // to reach this method. Do not mutate menu state during simulation.
            return inSortButton || inMenu ? Optional.of(this) : Optional.empty();
        }
        if (rsi$menuOpen) {
            if (inMenu) {
                int index = (int) ((mouseY - panelY - 3) / RSI_ROW_HEIGHT);
                if (index >= 0 && index < TetraMaterialSortMode.values().length) {
                    TetraMaterialSortMode mode = TetraMaterialSortMode.values()[index];
                    int rowY = panelY + 3 + index * RSI_ROW_HEIGHT;
                    double relativeX = mouseX - panelArea.getX();
                    double relativeY = mouseY - rowY;
                    if (relativeY >= -2 && relativeY < 14
                            && relativeX >= RSI_CONTROL_START
                            && relativeX < RSI_CONTROL_START + RSI_CONTROL_WIDTH * 3) {
                        int control = (int) ((relativeX - RSI_CONTROL_START) / RSI_CONTROL_WIDTH);
                        if (control == 0) {
                            TetraWorkbenchMaterialState.toggleSortDirection(mode, true);
                        } else if (control == 1) {
                            TetraWorkbenchMaterialState.toggleSortDirection(mode, false);
                        } else {
                            TetraWorkbenchMaterialState.toggleSortField(mode);
                        }
                    }
                    return Optional.of(this);
                }
            }
        }
        if (inSortButton) {
            rsi$menuOpen = !rsi$menuOpen;
            TetraJeiSortExclusion.setMenuOpen(rsi$menuOpen);
            return Optional.of(this);
        }
        if (rsi$menuOpen) {
            rsi$menuOpen = false;
            TetraJeiSortExclusion.setMenuOpen(false);
        }
        return Optional.empty();
    }
}
