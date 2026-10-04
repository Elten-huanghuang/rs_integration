package com.huanghuang.rsintegration.client.config;

import com.mojang.blaze3d.platform.InputConstants;
import mezz.jei.common.Internal;
import mezz.jei.common.gui.elements.DrawableNineSliceTexture;
import mezz.jei.common.input.IInternalKeyMappings;
import mezz.jei.common.util.ImmutableRect2i;
import mezz.jei.gui.input.IUserInputHandler;
import mezz.jei.gui.input.UserInput;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;

import java.util.Optional;

public final class JeiConfigButton implements IUserInputHandler {
    private ImmutableRect2i bounds = ImmutableRect2i.EMPTY;
    private boolean visible;

    public void updateBounds(int x, int y) { bounds = new ImmutableRect2i(x, y, 20, 20); }
    public void beginFrame() { visible = false; }

    public void draw(GuiGraphics graphics, int mouseX, int mouseY) {
        visible = true;
        boolean hovered = bounds.contains(mouseX, mouseY);
        DrawableNineSliceTexture background = Internal.getTextures().getButtonForState(false, true, hovered);
        background.draw(graphics, bounds.x(), bounds.y(), bounds.width(), bounds.height());
        Internal.getTextures().getConfigButtonIcon().draw(graphics, bounds.x() + 2, bounds.y() + 2);
    }

    public void drawTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        if (visible && bounds.contains(mouseX, mouseY)) {
            graphics.renderTooltip(Minecraft.getInstance().font, RSIntegrationConfigScreen.tr("title"), mouseX, mouseY);
        }
    }

    @Override
    public Optional<IUserInputHandler> handleUserInput(Screen screen, UserInput input, IInternalKeyMappings keys) {
        if (!visible || !bounds.contains(input.getMouseX(), input.getMouseY())
                || input.getKey().getType() != InputConstants.Type.MOUSE || input.getKey().getValue() != 0) {
            return Optional.empty();
        }
        if (!input.isSimulate()) Minecraft.getInstance().setScreen(new RSIntegrationConfigScreen(screen));
        return Optional.of(this);
    }
}
