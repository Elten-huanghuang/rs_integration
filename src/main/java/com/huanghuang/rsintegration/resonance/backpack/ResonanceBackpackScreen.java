package com.huanghuang.rsintegration.resonance.backpack;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

public class ResonanceBackpackScreen extends AbstractContainerScreen<ResonanceBackpackContainer> {

    private static final ResourceLocation GENERIC_54 =
            new ResourceLocation("textures/gui/container/generic_54.png");

    public ResonanceBackpackScreen(ResonanceBackpackContainer menu, Inventory playerInv,
                                   Component title) {
        super(menu, playerInv, title);
        this.imageWidth = 176;
        this.imageHeight = 190;
        this.inventoryLabelY = this.imageHeight - 94;
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(gfx);
        super.render(gfx, mouseX, mouseY, partialTick);
        this.renderTooltip(gfx, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics gfx, float partialTick, int mouseX, int mouseY) {
        RenderSystem.setShaderTexture(0, GENERIC_54);

        // Slice 1: top 3 rows of backpack inventory (72px)
        gfx.blit(GENERIC_54, leftPos, topPos, 0, 0, imageWidth, 72);

        // Slice 2: backpack hotbar row with 4px gap (22px)
        gfx.blit(GENERIC_54, leftPos, topPos + 72, 0, 193, imageWidth, 22);

        // Slice 3: player inventory + hotbar (96px)
        gfx.blit(GENERIC_54, leftPos, topPos + 94, 0, 126, imageWidth, 96);
    }

    @Override
    protected void renderLabels(GuiGraphics gfx, int mouseX, int mouseY) {
        super.renderLabels(gfx, mouseX, mouseY);
    }
}
