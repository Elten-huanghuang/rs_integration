package com.huanghuang.rsintegration.compat.jei;

/** 书签和历史按钮各占 22 像素；为 EAEP 的库存按钮再留一格。 */
public final class JeiConfigButtonPlacement {
    private JeiConfigButtonPlacement() {}

    public static int x(int bookmarkX, boolean extendedAEPlusPresent) {
        return bookmarkX + (extendedAEPlusPresent ? 66 : 44);
    }

    public record Position(int x, int y) {}

    public static boolean needsExtraRow(int guiLeft, boolean extendedAEPlusPresent) {
        return x(6, extendedAEPlusPresent) + 20 > guiLeft - 2;
    }

    public static Position position(int bookmarkX, int guiLeft, int screenHeight, boolean extendedAEPlusPresent) {
        if (needsExtraRow(guiLeft, extendedAEPlusPresent)) return new Position(6, screenHeight - 48);
        return new Position(x(bookmarkX, extendedAEPlusPresent), screenHeight - 26);
    }
}
