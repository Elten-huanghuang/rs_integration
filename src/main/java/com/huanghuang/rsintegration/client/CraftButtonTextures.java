package com.huanghuang.rsintegration.client;

import com.huanghuang.rsintegration.crafting.availability.MaterialAvailability;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/** Texture-backed reconstruction of the original variable-size button geometry. */
public final class CraftButtonTextures {
    private static final ResourceLocation BACKGROUNDS = texture("backgrounds");
    private static final ResourceLocation SYMBOLS = texture("symbols");
    private static final ResourceLocation MACHINE = texture("machine_parts");

    private CraftButtonTextures() {}

    private static ResourceLocation texture(String name) {
        return new ResourceLocation("rs_integration", "textures/gui/recipe_buttons/" + name + ".png");
    }

    public static void craft(GuiGraphics graphics, int x, int y, int width, int height,
                              MaterialAvailability state, boolean hovered) {
        int index = switch (state) {
            case UNKNOWN -> 0;
            case MISSING -> 2;
            case READY -> 4;
        } + (hovered ? 1 : 0);
        background(graphics, x, y, width, height, index);
        int advance = state == MaterialAvailability.READY ? 7 : 6;
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
        blit(graphics, SYMBOLS, x + (width - advance) / 2, y + (height - 9) / 2,
                8, 8, index * 16, 0, 16, 16, 96, 16);
        com.mojang.blaze3d.systems.RenderSystem.disableBlend();
    }

    public static void machine(GuiGraphics graphics, int x, int y, int width, int height,
                                boolean hovered) {
        background(graphics, x, y, width, height, hovered ? 7 : 6);
        int u = hovered ? 6 : 0;
        blit(graphics, MACHINE, x + 1, y + 1, width - 2, height - 4, u, 0, 2, 2, 12, 2);
        blit(graphics, MACHINE, x + 2, y + 2, width - 4, height - 6, u + 2, 0, 2, 2, 12, 2);
        blit(graphics, MACHINE, x + width / 2 - 1, y + height - 2, 2, 1, u + 4, 0, 2, 2, 12, 2);
        blit(graphics, MACHINE, x + width / 2 - 2, y + height - 1, 4, 1, u + 4, 0, 2, 2, 12, 2);
    }

    private static void background(GuiGraphics g, int x, int y, int w, int h, int state) {
        int u = state * 6;
        blit(g, BACKGROUNDS, x, y, w, 1, u, 0, 2, 2, 48, 6);
        blit(g, BACKGROUNDS, x, y + h - 1, w, 1, u, 0, 2, 2, 48, 6);
        blit(g, BACKGROUNDS, x, y + 1, 1, h - 2, u, 0, 2, 2, 48, 6);
        blit(g, BACKGROUNDS, x + w - 1, y + 1, 1, h - 2, u, 0, 2, 2, 48, 6);
        blit(g, BACKGROUNDS, x + 1, y + 1, w - 2, h - 2, u + 2, 2, 2, 2, 48, 6);
    }

    private static void blit(GuiGraphics g, ResourceLocation texture, int x, int y, int w, int h,
                              int u, int v, int sw, int sh, int tw, int th) {
        g.blit(texture, x, y, w, h, (float) u, (float) v, sw, sh, tw, th);
    }
}
