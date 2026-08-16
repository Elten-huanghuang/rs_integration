package com.huanghuang.rsintegration.mods.rs.recentsearch;

import com.refinedmods.refinedstorage.screen.grid.GridScreen;
import com.refinedmods.refinedstorage.screen.widget.SearchWidget;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.List;

final class RecentSearchOverlay {
    private static final int ROW_HEIGHT = 14;
    private static final int PADDING = 2;
    private static final int ACTION_SIZE = 10;
    private static final int CURRENT_FAVORITE_GAP = 6;
    private static final int SEPARATOR_HEIGHT = 3;
    private static final int FOOTER_HEIGHT = 12;

    private static final int PANEL_BORDER = 0xFF373737;
    private static final int PANEL_BACKGROUND = 0xFFC6C6C6;
    private static final int ROW_HOVER = 0x80FFFFFF;
    private static final int ROW_SELECTED = 0xA06A9ED6;
    private static final int TEXT_COLOR = 0xFF404040;
    private static final int FAVORITE_COLOR = 0xFFFFC107;
    private static final int FAVORITE_INACTIVE = 0xFF6F6F6F;
    private static final int DELETE_COLOR = 0xFF8B3030;
    private static final int DELETE_HOVER_BACKGROUND = 0xFFD04A4A;
    private static final int DELETE_HOVER_COLOR = 0xFFFFFFFF;

    enum Action {
        APPLY,
        TOGGLE_FAVORITE,
        DELETE,
        TOGGLE_CURRENT_FAVORITE,
        CLEAR_ALL
    }

    record Hit(Action action, int entryIndex) {}

    record Layout(int x, int y, int width, int height,
                  int favoriteCount, boolean separator,
                  boolean favoritesEnabled, boolean deleteButtonsEnabled,
                  List<RecentSearchEntry> entries,
                  int currentFavoriteX, int currentFavoriteY) {
        boolean hasPanel() {
            return !entries.isEmpty() && height > 0;
        }

        boolean containsPanel(double mouseX, double mouseY) {
            return hasPanel() && mouseX >= x && mouseX < x + width
                    && mouseY >= y && mouseY < y + height;
        }

        int rowY(int index) {
            int extra = separator && index >= favoriteCount ? SEPARATOR_HEIGHT : 0;
            return y + PADDING + index * ROW_HEIGHT + extra;
        }

        int footerY() {
            return y + PADDING + entries.size() * ROW_HEIGHT
                    + (separator ? SEPARATOR_HEIGHT : 0);
        }
    }

    private RecentSearchOverlay() {}

    static Layout layout(GridScreen screen, SearchWidget field,
                         List<RecentSearchEntry> requestedEntries,
                         boolean favoritesEnabled, boolean deleteButtonsEnabled) {
        int currentFavoriteX = field.getX() + field.getWidth() + CURRENT_FAVORITE_GAP;
        int currentFavoriteY = field.getY();
        int x = field.getX();
        int width = field.getWidth();
        int y = field.getY() + field.getHeight() + 2;
        int availableHeight = Math.max(0, screen.height - y - 4);

        int count = requestedEntries.size();
        while (count > 0) {
            List<RecentSearchEntry> candidate = requestedEntries.subList(0, count);
            int favoriteCount = favoritesEnabled ? countFavorites(candidate) : 0;
            boolean separator = favoritesEnabled && favoriteCount > 0 && favoriteCount < count;
            int height = panelHeight(count, separator, deleteButtonsEnabled);
            if (height <= availableHeight) {
                return new Layout(x, y, width, height, favoriteCount, separator,
                        favoritesEnabled, deleteButtonsEnabled, List.copyOf(candidate),
                        currentFavoriteX, currentFavoriteY);
            }
            count--;
        }
        return new Layout(x, y, width, 0, 0, false,
                favoritesEnabled, deleteButtonsEnabled, List.of(),
                currentFavoriteX, currentFavoriteY);
    }

    static void render(GuiGraphics graphics, Font font, SearchWidget field,
                       Layout layout, int selectedIndex, int mouseX, int mouseY) {
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 450);
        try {
            renderCurrentFavorite(graphics, field, layout, mouseX, mouseY);
            if (!layout.hasPanel()) {
                renderTooltip(graphics, font, field, layout, mouseX, mouseY);
                return;
            }

            graphics.fill(layout.x(), layout.y(), layout.x() + layout.width(),
                    layout.y() + layout.height(), PANEL_BORDER);
            graphics.fill(layout.x() + 1, layout.y() + 1,
                    layout.x() + layout.width() - 1, layout.y() + layout.height() - 1,
                    PANEL_BACKGROUND);

            for (int index = 0; index < layout.entries().size(); index++) {
                RecentSearchEntry entry = layout.entries().get(index);
                int rowY = layout.rowY(index);
                boolean hovered = contains(mouseX, mouseY,
                        layout.x() + 1, rowY, layout.width() - 2, ROW_HEIGHT);
                if (index == selectedIndex) {
                    graphics.fill(layout.x() + 1, rowY,
                            layout.x() + layout.width() - 1, rowY + ROW_HEIGHT,
                            ROW_SELECTED);
                } else if (hovered) {
                    graphics.fill(layout.x() + 1, rowY,
                            layout.x() + layout.width() - 1, rowY + ROW_HEIGHT,
                            ROW_HOVER);
                }

                int textX = layout.x() + 4;
                if (layout.favoritesEnabled()) {
                    int starX = layout.x() + 3;
                    drawStar(graphics, starX, rowY + 3,
                            entry.favorite() ? FAVORITE_COLOR : FAVORITE_INACTIVE);
                    textX += ACTION_SIZE;
                }
                int deleteSpace = layout.deleteButtonsEnabled() ? ACTION_SIZE + 2 : 0;
                int textWidth = layout.x() + layout.width() - 4 - deleteSpace - textX;
                String displayed = fit(font, entry.query(), Math.max(4, textWidth));
                graphics.drawString(font, displayed, textX, rowY + 3, TEXT_COLOR, false);

                if (layout.deleteButtonsEnabled()) {
                    int deleteX = layout.x() + layout.width() - ACTION_SIZE - 2;
                    boolean deleteHovered = contains(mouseX, mouseY, deleteX - 1,
                            rowY + 1, ACTION_SIZE + 2, ROW_HEIGHT - 2);
                    if (deleteHovered) {
                        graphics.fill(deleteX - 1, rowY + 1,
                                deleteX + ACTION_SIZE + 1, rowY + ROW_HEIGHT - 1,
                                DELETE_HOVER_BACKGROUND);
                    }
                    drawX(graphics, deleteX, rowY + 3,
                            deleteHovered ? DELETE_HOVER_COLOR : DELETE_COLOR);
                }
            }

            if (layout.separator()) {
                int separatorY = layout.rowY(layout.favoriteCount()) - 2;
                graphics.fill(layout.x() + 3, separatorY,
                        layout.x() + layout.width() - 3, separatorY + 1, 0xFF8B8B8B);
            }

            if (layout.deleteButtonsEnabled()) {
                int footerY = layout.footerY();
                graphics.fill(layout.x() + 3, footerY,
                        layout.x() + layout.width() - 3, footerY + 1, 0xFF8B8B8B);
                int trashX = layout.x() + layout.width() - ACTION_SIZE - 2;
                boolean trashHovered = contains(mouseX, mouseY, trashX - 1,
                        footerY + 1, ACTION_SIZE + 2, FOOTER_HEIGHT - 1);
                if (trashHovered) {
                    graphics.fill(trashX - 1, footerY + 1,
                            trashX + ACTION_SIZE + 1, footerY + FOOTER_HEIGHT,
                            DELETE_HOVER_BACKGROUND);
                }
                drawTrash(graphics, trashX, footerY + 3,
                        trashHovered ? DELETE_HOVER_COLOR : DELETE_COLOR,
                        trashHovered ? DELETE_HOVER_BACKGROUND : PANEL_BACKGROUND);
            }

            renderTooltip(graphics, font, field, layout, mouseX, mouseY);
        } finally {
            graphics.pose().popPose();
        }
    }

    static Hit hitTest(SearchWidget field, Layout layout, double mouseX, double mouseY) {
        if (layout.favoritesEnabled() && !field.getValue().isBlank()
                && contains(mouseX, mouseY, layout.currentFavoriteX(),
                layout.currentFavoriteY(), ACTION_SIZE, field.getHeight())) {
            return new Hit(Action.TOGGLE_CURRENT_FAVORITE, -1);
        }
        if (!layout.containsPanel(mouseX, mouseY)) return null;

        for (int index = 0; index < layout.entries().size(); index++) {
            int rowY = layout.rowY(index);
            if (!contains(mouseX, mouseY, layout.x() + 1, rowY,
                    layout.width() - 2, ROW_HEIGHT)) continue;
            if (layout.favoritesEnabled() && mouseX < layout.x() + 3 + ACTION_SIZE) {
                return new Hit(Action.TOGGLE_FAVORITE, index);
            }
            if (layout.deleteButtonsEnabled()
                    && mouseX >= layout.x() + layout.width() - ACTION_SIZE - 3) {
                return new Hit(Action.DELETE, index);
            }
            return new Hit(Action.APPLY, index);
        }

        if (layout.deleteButtonsEnabled()
                && contains(mouseX, mouseY,
                layout.x() + layout.width() - ACTION_SIZE - 3,
                layout.footerY() + 1, ACTION_SIZE + 2, FOOTER_HEIGHT - 1)) {
            return new Hit(Action.CLEAR_ALL, -1);
        }
        return null;
    }

    static boolean containsOverlay(SearchWidget field, Layout layout,
                                   double mouseX, double mouseY) {
        if (layout.containsPanel(mouseX, mouseY)) return true;
        return layout.favoritesEnabled() && !field.getValue().isBlank()
                && contains(mouseX, mouseY, layout.currentFavoriteX(),
                layout.currentFavoriteY(), ACTION_SIZE, field.getHeight());
    }

    private static void renderCurrentFavorite(GuiGraphics graphics, SearchWidget field,
                                              Layout layout, int mouseX, int mouseY) {
        if (!layout.favoritesEnabled() || field.getValue().isBlank()) return;
        boolean hovered = contains(mouseX, mouseY, layout.currentFavoriteX(),
                layout.currentFavoriteY(), ACTION_SIZE, field.getHeight());
        graphics.fill(layout.currentFavoriteX(), layout.currentFavoriteY(),
                layout.currentFavoriteX() + ACTION_SIZE,
                layout.currentFavoriteY() + field.getHeight(),
                hovered ? 0xFFCFCFCF : PANEL_BACKGROUND);
        drawStar(graphics, layout.currentFavoriteX() + 1,
                layout.currentFavoriteY() + 1,
                RecentSearchClient.isCurrentQueryFavorite()
                        ? FAVORITE_COLOR : FAVORITE_INACTIVE);
    }

    private static void renderTooltip(GuiGraphics graphics, Font font, SearchWidget field,
                                      Layout layout, int mouseX, int mouseY) {
        Hit hit = hitTest(field, layout, mouseX, mouseY);
        if (hit != null) {
            Component tooltip = switch (hit.action()) {
                case TOGGLE_FAVORITE, TOGGLE_CURRENT_FAVORITE ->
                        Component.translatable("rsi.recent_search.favorite");
                case DELETE -> Component.translatable("rsi.recent_search.delete");
                case CLEAR_ALL -> Component.translatable("rsi.recent_search.clear_all");
                case APPLY -> null;
            };
            if (tooltip != null) {
                graphics.renderTooltip(font, tooltip, mouseX, mouseY);
                return;
            }
        }

        for (int index = 0; index < layout.entries().size(); index++) {
            RecentSearchEntry entry = layout.entries().get(index);
            int rowY = layout.rowY(index);
            if (contains(mouseX, mouseY, layout.x() + 1, rowY,
                    layout.width() - 2, ROW_HEIGHT)) {
                graphics.renderTooltip(font, Component.literal(entry.query()), mouseX, mouseY);
                return;
            }
        }
    }

    private static String fit(Font font, String text, int width) {
        if (font.width(text) <= width) return text;
        int suffixWidth = font.width("...");
        return font.plainSubstrByWidth(text, Math.max(0, width - suffixWidth)) + "...";
    }

    private static int countFavorites(List<RecentSearchEntry> entries) {
        int count = 0;
        for (RecentSearchEntry entry : entries) {
            if (entry.favorite()) count++;
        }
        return count;
    }

    private static int panelHeight(int rows, boolean separator, boolean footer) {
        return PADDING * 2 + rows * ROW_HEIGHT
                + (separator ? SEPARATOR_HEIGHT : 0)
                + (footer ? FOOTER_HEIGHT : 0);
    }

    private static boolean contains(double mouseX, double mouseY,
                                    int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width
                && mouseY >= y && mouseY < y + height;
    }

    private static void drawStar(GuiGraphics graphics, int x, int y, int color) {
        graphics.fill(x + 3, y, x + 5, y + 2, color);
        graphics.fill(x, y + 2, x + 8, y + 3, color);
        graphics.fill(x + 1, y + 3, x + 7, y + 4, color);
        graphics.fill(x + 2, y + 4, x + 6, y + 5, color);
        graphics.fill(x + 1, y + 5, x + 3, y + 6, color);
        graphics.fill(x + 5, y + 5, x + 7, y + 6, color);
        graphics.fill(x, y + 6, x + 2, y + 7, color);
        graphics.fill(x + 6, y + 6, x + 8, y + 7, color);
    }

    private static void drawX(GuiGraphics graphics, int x, int y, int color) {
        for (int offset = 0; offset < 7; offset++) {
            graphics.fill(x + 1 + offset, y + offset,
                    x + 2 + offset, y + offset + 1, color);
            graphics.fill(x + 7 - offset, y + offset,
                    x + 8 - offset, y + offset + 1, color);
        }
    }

    private static void drawTrash(GuiGraphics graphics, int x, int y,
                                  int color, int backgroundColor) {
        graphics.fill(x + 2, y, x + 7, y + 1, color);
        graphics.fill(x + 1, y + 2, x + 8, y + 3, color);
        graphics.fill(x + 2, y + 3, x + 7, y + 8, color);
        graphics.fill(x + 3, y + 4, x + 4, y + 7, backgroundColor);
        graphics.fill(x + 5, y + 4, x + 6, y + 7, backgroundColor);
    }
}
