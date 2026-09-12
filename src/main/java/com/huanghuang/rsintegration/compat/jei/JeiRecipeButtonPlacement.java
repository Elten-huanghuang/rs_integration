package com.huanghuang.rsintegration.compat.jei;

import net.minecraft.client.renderer.Rect2i;

/** Stable screen-bounded placement for RSI controls beside a JEI recipe. */
public final class JeiRecipeButtonPlacement {
    private static final int BUTTON_GAP = 2;
    private static final int SCREEN_MARGIN = 2;

    private JeiRecipeButtonPlacement() {}

    public static int[] place(Rect2i transferArea, int width, int height,
                              int screenWidth, int screenHeight) {
        int maxX = Math.max(SCREEN_MARGIN, screenWidth - SCREEN_MARGIN - width);
        int maxY = Math.max(SCREEN_MARGIN, screenHeight - SCREEN_MARGIN - height);
        int x = clamp(transferArea.getX(), SCREEN_MARGIN, maxX);

        int below = transferArea.getY() + transferArea.getHeight() + BUTTON_GAP;
        int above = transferArea.getY() - height - BUTTON_GAP;
        int y;
        if (below <= maxY) {
            y = below;
        } else if (above >= SCREEN_MARGIN) {
            y = above;
        } else {
            y = clamp(below, SCREEN_MARGIN, maxY);
        }
        return new int[]{x, y};
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }
}
