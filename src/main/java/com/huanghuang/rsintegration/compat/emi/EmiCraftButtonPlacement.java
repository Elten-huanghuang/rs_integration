package com.huanghuang.rsintegration.compat.emi;

public final class EmiCraftButtonPlacement {
    private static final int SIDE_BUTTON_SIZE = 12;
    private static final int SIDE_BUTTON_PITCH = 14;
    private static final int SIDE_BUTTON_X = 5;

    private EmiCraftButtonPlacement() {}

    public static int columnOffset(int existingSideWidth) {
        return existingSideWidth <= 0 ? 0 : existingSideWidth + 1;
    }

    public static int sideWidth(int buttonCount, int rows) {
        if (buttonCount <= 0) return 0;
        int safeRows = Math.max(1, rows);
        int columns = (buttonCount + safeRows - 1) / safeRows;
        return columns * SIDE_BUTTON_PITCH - 1;
    }

    public static int[] position(int index, int buttonCount, int rows,
                                 int recipeWidth, int recipeHeight, int columnOffset) {
        int safeRows = Math.max(1, rows);
        int visibleRows = Math.min(safeRows, Math.max(1, buttonCount));
        int verticalInset = Math.min(8,
                recipeHeight + 8 - visibleRows * SIDE_BUTTON_PITCH - 2);
        int bottomY = recipeHeight + 4 - SIDE_BUTTON_SIZE - verticalInset / 2 + 1;
        int column = Math.max(0, index) / safeRows;
        int row = Math.max(0, index) % safeRows;
        int x = recipeWidth + SIDE_BUTTON_X + Math.max(0, columnOffset)
                + column * SIDE_BUTTON_PITCH;
        int y = Math.max(0, bottomY - row * SIDE_BUTTON_PITCH);
        return new int[]{x, y};
    }
}
